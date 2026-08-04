package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ZipDirectoryMediaAnalyzerTest {
  @TempDir
  lateinit var tempDirectory: Path

  /**
   * Pinned against [ZipMediaAnalyzer] on the same archive rather than against a written-out
   * expectation, because the two paths are chosen per library and a library that switches between
   * them must not see its page list reordered or renumbered.
   */
  @Test
  fun `orders and numbers pages the same way the materializing analyzer does`() {
    val archive =
      writeZip(
        "natural.cbz",
        mapOf(
          "pages/10.png" to png(),
          "pages/2.png" to png(),
          "pages/1.png" to png(),
          "empty/" to ByteArray(0),
          "ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray(),
        ),
      )
    val expected =
      ZipMediaAnalyzer().analyze(
        bookId = BookId("book-1"),
        path = archive,
        analyzeDimensions = false,
        createdAtMillis = CREATED_AT,
      )

    val media = ByteArrayRandomAccessMedia(Files.readAllBytes(archive))
    val actual =
      ZipDirectoryMediaAnalyzer().analyze(
        bookId = BookId("book-1"),
        media = media,
        createdAtMillis = CREATED_AT,
      )

    assertEquals(MediaStatus.READY, actual.status)
    assertEquals(MediaProfile.DIVINA, actual.profile)
    assertEquals(expected.pages.map { it.number to it.fileName }, actual.pages.map { it.number to it.fileName })
    assertEquals(listOf("pages/1.png", "pages/2.png", "pages/10.png"), actual.pages.map { it.fileName })
    assertEquals(expected.files.map { it.fileName }, actual.files.map { it.fileName })
    assertEquals(1, media.reads)
  }

  @Test
  fun `reports sizes but no dimensions, since dimensions would need the entry bytes`() {
    val archive = writeZip("sized.cbz", mapOf("pages/001.png" to png()))

    val media =
      ZipDirectoryMediaAnalyzer().analyze(
        bookId = BookId("book-1"),
        media = ByteArrayRandomAccessMedia(Files.readAllBytes(archive)),
        createdAtMillis = CREATED_AT,
      )

    val page = media.pages.single()
    assertEquals(png().size.toLong(), page.fileSize)
    assertNull(page.dimension)
    assertEquals("", page.fileHash)
  }

  @Test
  fun `types pages by extension, listing an unrecognized extension as a file`() {
    val archive =
      writeZip(
        "typed.cbz",
        mapOf(
          "pages/001.jpg" to ByteArray(16) { 0x1 },
          "pages/002.webp" to ByteArray(16) { 0x2 },
          "pages/003.unknownextension" to ByteArray(16) { 0x3 },
          "notes" to ByteArray(4) { 0x4 },
        ),
      )

    val media =
      ZipDirectoryMediaAnalyzer().analyze(
        bookId = BookId("book-1"),
        media = ByteArrayRandomAccessMedia(Files.readAllBytes(archive)),
        createdAtMillis = CREATED_AT,
      )

    assertEquals(
      listOf("pages/001.jpg" to "image/jpeg", "pages/002.webp" to "image/webp"),
      media.pages.map { it.fileName to it.mediaType },
    )
    assertEquals(listOf("notes", "pages/003.unknownextension"), media.files.map { it.fileName }.sorted())
    assertTrue(media.files.all { it.mediaType == null })
  }

  @Test
  fun `reports an encrypted archive as unsupported rather than as an error`() {
    val archive =
      writeSyntheticZip(
        tempDirectory.resolve("encrypted.cbz"),
        mapOf("pages/001.jpg" to ByteArray(32) { 0x5 }),
        encrypted = true,
      )

    val media =
      ZipDirectoryMediaAnalyzer().analyze(
        bookId = BookId("book-1"),
        media = ByteArrayRandomAccessMedia(Files.readAllBytes(archive)),
        createdAtMillis = CREATED_AT,
      )

    assertEquals(MediaStatus.UNSUPPORTED, media.status)
    assertEquals(MediaAnalysisComment.ENCRYPTED, media.comment)
    assertTrue(media.pages.isEmpty())
  }

  @Test
  fun `reports an archive with no image entries as having no pages`() {
    val archive = writeZip("empty.cbz", mapOf("ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray()))

    val media =
      ZipDirectoryMediaAnalyzer().analyze(
        bookId = BookId("book-1"),
        media = ByteArrayRandomAccessMedia(Files.readAllBytes(archive)),
        createdAtMillis = CREATED_AT,
      )

    assertEquals(MediaStatus.ERROR, media.status)
    assertEquals(MediaAnalysisComment.NO_PAGES, media.comment)
    assertEquals(listOf("ComicInfo.xml"), media.files.map { it.fileName })
  }

  private fun writeZip(
    fileName: String,
    entries: Map<String, ByteArray>,
  ): Path {
    val path = tempDirectory.resolve(fileName)
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      entries.forEach { (name, content) ->
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = content.size.toLong()
        entry.compressedSize = content.size.toLong()
        entry.crc = CRC32().apply { update(content) }.value
        output.putNextEntry(entry)
        output.write(content)
        output.closeEntry()
      }
    }
    return path
  }

  /** A minimal PNG, real enough that [ZipMediaAnalyzer] types it as an image for the cross-check. */
  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { output ->
        javax.imageio.ImageIO.write(
          java.awt.image.BufferedImage(4, 6, java.awt.image.BufferedImage.TYPE_INT_RGB),
          "png",
          output,
        )
      }.toByteArray()

  private companion object {
    const val CREATED_AT = 1_700_000_000_000L
  }
}
