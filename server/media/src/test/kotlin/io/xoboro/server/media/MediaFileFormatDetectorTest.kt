package io.xoboro.server.media

import io.xoboro.core.domain.MediaKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.io.TempDir

class MediaFileFormatDetectorTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  private val detector = MediaFileFormatDetector()

  @Test
  fun `identifies a document by content rather than by name`() {
    val path = temporaryDirectory.resolve("mislabelled.cbz")
    PDDocument().use { document ->
      document.addPage(PDPage())
      document.save(path.toFile())
    }

    // The file extension repair exists for. The narrower archive detector sees only "not an archive I
    // recognise" here, which is why repair used to leave documents alone.
    assertEquals(MediaFileFormat.PDF, detector.detect(path))
    assertEquals(MediaKind.PDF, detector.detect(path)?.mediaKind)
  }

  @Test
  fun `tells a publication from a comic archive inside the same container format`() {
    val publication =
      writeZip(
        "mislabelled.cbz",
        mapOf("mimetype" to "application/epub+zip", "OPS/package.opf" to "<package/>"),
      )
    val comic = writeZip("comic.zip", mapOf("001.png" to "synthetic"))

    // An EPUB is a ZIP, so a signature table cannot separate these two. Both fixtures are valid ZIP
    // containers; only the mimetype entry distinguishes them.
    assertEquals(MediaFileFormat.EPUB, detector.detect(publication))
    assertEquals(MediaFileFormat.COMIC_ZIP, detector.detect(comic))
    assertEquals("cbz", MediaFileFormat.COMIC_ZIP.canonicalExtension)
  }

  @Test
  fun `declines to identify a container it cannot read`() {
    val encrypted =
      writeSyntheticZip(
        temporaryDirectory.resolve("encrypted.cbz"),
        mapOf("001.jpg" to "ciphertext".encodeToByteArray()),
        encrypted = true,
      )
    val truncated = temporaryDirectory.resolve("truncated.cbz")
    Files.write(truncated, Files.readAllBytes(encrypted).copyOfRange(0, 30))
    val garbage = temporaryDirectory.resolve("garbage.cbz")
    Files.writeString(garbage, "not any known format")

    // An encrypted archive has a valid ZIP signature and unreadable contents, so its mimetype entry
    // cannot be checked. Calling it a comic archive would rename a locked EPUB to `.cbz`; the answer
    // is that the format was not established.
    assertNull(detector.detect(encrypted))
    assertNull(detector.detect(truncated))
    assertNull(detector.detect(garbage))
  }

  @Test
  fun `identifies a rar archive regardless of its name`() {
    val archive =
      writeSyntheticRar4(
        temporaryDirectory.resolve("mislabelled.cbz"),
        mapOf("001.png" to "synthetic".encodeToByteArray()),
      )

    assertEquals(MediaFileFormat.COMIC_RAR, detector.detect(archive))
    assertEquals("cbr", MediaFileFormat.COMIC_RAR.canonicalExtension)
  }

  private fun writeZip(
    name: String,
    entries: Map<String, String>,
  ): Path {
    val path = temporaryDirectory.resolve(name)
    ZipOutputStream(Files.newOutputStream(path)).use { archive ->
      entries.forEach { (entryName, content) ->
        archive.putNextEntry(ZipEntry(entryName))
        archive.write(content.encodeToByteArray())
        archive.closeEntry()
      }
    }
    return path
  }
}
