package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ZipMediaAnalyzerTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `indexes image pages in Komga compatible natural order`() {
    val archive =
      createZip(
        "natural.cbz",
        mapOf(
          "pages/10.png" to png(width = 10, height = 20),
          "pages/2.png" to png(width = 20, height = 30),
          "pages/1.png" to png(width = 30, height = 40),
          "empty/" to null,
          "ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray(),
        ),
      )

    val media =
      ZipMediaAnalyzer().analyze(
        bookId = BookId("book-1"),
        path = archive,
        analyzeDimensions = true,
        createdAtMillis = 1_700_000_000_000L,
      )

    assertEquals(MediaStatus.READY, media.status)
    assertEquals(MediaProfile.DIVINA, media.profile)
    assertEquals("application/zip", media.mediaType)
    assertEquals(
      listOf("pages/1.png", "pages/2.png", "pages/10.png"),
      media.pages.map { it.fileName },
    )
    assertEquals(listOf(1, 2, 3), media.pages.map { it.number })
    assertEquals(30, media.pages.first().dimension?.width)
    assertEquals(40, media.pages.first().dimension?.height)
    assertEquals(listOf("ComicInfo.xml"), media.files.map { it.fileName })
    assertNull(media.comment)
  }

  @Test
  fun `avoids image decoding when dimensions are disabled`() {
    val archive = createZip("metadata-only.cbz", mapOf("001.png" to png(10, 20)))

    val media =
      ZipMediaAnalyzer().analyze(
        bookId = BookId("book-1"),
        path = archive,
        analyzeDimensions = false,
        createdAtMillis = 1,
      )

    assertEquals(MediaStatus.READY, media.status)
    assertNull(media.pages.single().dimension)
  }

  @Test
  fun `hashes only the configured leading and trailing pages`() {
    val image = png(10, 20)
    val archive =
      createZip(
        "hashes.cbz",
        (1..8).associate { number -> "%03d.png".format(number) to image },
      )

    val media =
      ZipMediaAnalyzer(pageHashing = 3).analyze(
        bookId = BookId("book-1"),
        path = archive,
        analyzeDimensions = false,
        hashPages = true,
        createdAtMillis = 1,
      )

    assertTrue(media.pages.take(3).all { it.fileHash.isNotBlank() })
    assertEquals(listOf("", ""), media.pages.drop(3).take(2).map { it.fileHash })
    assertTrue(media.pages.takeLast(3).all { it.fileHash.isNotBlank() })
    assertEquals(1, media.pages.map { it.fileHash }.filter(String::isNotBlank).distinct().size)
    assertNotEquals("", media.pages.first().fileHash)
  }

  @Test
  fun `returns stable Komga error codes for empty and corrupt archives`() {
    val empty = createZip("empty.cbz", mapOf("ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray()))
    val corrupt = tempDirectory.resolve("corrupt.cbz")
    Files.writeString(corrupt, "not a zip")

    val emptyMedia =
      ZipMediaAnalyzer().analyze(BookId("book-empty"), empty, false, createdAtMillis = 1)
    val corruptMedia =
      ZipMediaAnalyzer().analyze(BookId("book-corrupt"), corrupt, false, createdAtMillis = 1)

    assertEquals(MediaStatus.ERROR, emptyMedia.status)
    assertEquals(MediaAnalysisComment.NO_PAGES, emptyMedia.comment)
    assertEquals(MediaStatus.ERROR, corruptMedia.status)
    assertEquals(MediaAnalysisComment.UNREADABLE_CONTAINER, corruptMedia.comment)
  }

  @Test
  fun `reports an encrypted archive as unsupported rather than damaged`() {
    val archive =
      writeSyntheticZip(
        tempDirectory.resolve("locked.cbz"),
        mapOf("001.jpg" to "ciphertext".encodeToByteArray()),
        encrypted = true,
      )
    val truncated = tempDirectory.resolve("truncated.cbz")
    Files.write(truncated, Files.readAllBytes(archive).copyOfRange(0, 30))

    val locked =
      ZipMediaAnalyzer().analyze(BookId("book-locked"), archive, false, createdAtMillis = 1)
    val damaged =
      ZipMediaAnalyzer().analyze(BookId("book-truncated"), truncated, false, createdAtMillis = 1)

    // Both used to be ERROR with the container code, leaving an operator no way to tell "replace this
    // file" from "the storage hiccuped, try again". The pair is asserted together because the value
    // is in the difference, not in either verdict alone.
    assertEquals(MediaStatus.UNSUPPORTED, locked.status)
    assertEquals(MediaAnalysisComment.ENCRYPTED, locked.comment)
    assertEquals(MediaProfile.DIVINA, locked.profile)
    assertEquals(MediaStatus.ERROR, damaged.status)
    assertEquals(MediaAnalysisComment.UNREADABLE_CONTAINER, damaged.comment)
  }

  private fun createZip(
    name: String,
    entries: Map<String, ByteArray?>,
  ): Path {
    val path = tempDirectory.resolve(name)
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      entries.forEach { (entryName, content) ->
        output.putNextEntry(ZipEntry(entryName))
        content?.let(output::write)
        output.closeEntry()
      }
    }
    return path
  }

  private fun png(
    width: Int,
    height: Int,
  ): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
      ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output)
      output.toByteArray()
    }
}
