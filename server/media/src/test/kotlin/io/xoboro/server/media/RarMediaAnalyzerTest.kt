package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RarMediaAnalyzerTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `indexes and hashes RAR pages in natural order`() {
    val image = png(18, 24)
    val archive =
      writeSyntheticRar4(
        temporaryDirectory.resolve("synthetic.cbr"),
        linkedMapOf(
          "pages/10.png" to png(10, 20),
          "pages/2.png" to image,
          "pages/1.png" to png(30, 40),
          "ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray(),
        ),
      )

    val media =
      RarMediaAnalyzer(pageHashing = 1).analyze(
        bookId = BookId("book-1"),
        path = archive,
        analyzeDimensions = true,
        hashPages = true,
        createdAtMillis = 1,
      )

    assertEquals(MediaStatus.READY, media.status)
    assertEquals(MediaProfile.DIVINA, media.profile)
    assertEquals(RarMediaAnalyzer.RAR_MEDIA_TYPE, media.mediaType)
    assertEquals(
      listOf("pages/1.png", "pages/2.png", "pages/10.png"),
      media.pages.map { it.fileName },
    )
    assertEquals(30, media.pages.first().dimension?.width)
    assertEquals(40, media.pages.first().dimension?.height)
    assertNotEquals("", media.pages.first().fileHash)
    assertEquals("", media.pages[1].fileHash)
    assertNotEquals("", media.pages.last().fileHash)
    assertEquals(listOf("ComicInfo.xml"), media.files.map { it.fileName })
    assertNull(media.comment)
  }

  @Test
  fun `returns stable errors for archives without readable pages`() {
    val archive =
      writeSyntheticRar4(
        temporaryDirectory.resolve("empty.cbr"),
        mapOf("metadata.txt" to "synthetic".encodeToByteArray()),
      )

    val media =
      RarMediaAnalyzer().analyze(
        bookId = BookId("book-1"),
        path = archive,
        analyzeDimensions = false,
        createdAtMillis = 1,
      )

    assertEquals(MediaStatus.ERROR, media.status)
    assertEquals(ZipMediaAnalyzer.ERROR_NO_PAGES, media.comment)
    assertTrue(media.files.single().fileName.endsWith(".txt"))
  }

  @Test
  fun `opens and indexes RAR5 stored entries`() {
    val archive = temporaryDirectory.resolve("synthetic-rar5.cbr")
    Files.write(
      archive,
      Base64.getDecoder().decode(
        "UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAATS800SUCAwuHAASHACC6fRl6gAAACUZJTEUxLlRYVAoDAgDwWYPlessBZmlsZTENCqOo3u8lAgMLhwAEhwAg48NfeIAAAAlGSUxFMi5UWFQKAwIAd+2G5XrLAWZpbGUyDQodd1ZRAwUEAA==",
      ),
    )

    val media =
      RarMediaAnalyzer().analyze(
        bookId = BookId("book-rar5"),
        path = archive,
        analyzeDimensions = false,
        createdAtMillis = 1,
      )

    assertEquals(MediaStatus.ERROR, media.status)
    assertEquals(ZipMediaAnalyzer.ERROR_NO_PAGES, media.comment)
    assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), media.files.map { it.fileName })
    assertTrue(media.files.all { it.fileSize == 7L })
  }

  private fun png(
    width: Int,
    height: Int,
  ): ByteArray =
    ByteArrayOutputStream().use { output ->
      ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output)
      output.toByteArray()
    }
}
