package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.jupiter.api.io.TempDir

class PdfMediaAnalyzerTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `analyzes pdf page count and dimensions`() {
    val path = tempDirectory.resolve("synthetic.pdf")
    PDDocument().use { document ->
      document.addPage(PDPage(PDRectangle(320F, 640F)))
      document.addPage(PDPage(PDRectangle(480F, 720F)))
      document.save(path.toFile())
    }

    val media =
      PdfMediaAnalyzer().analyze(
        bookId = BookId("book-pdf"),
        path = path,
        analyzeDimensions = true,
        createdAtMillis = 1,
      )

    assertEquals(MediaStatus.READY, media.status)
    assertEquals(MediaProfile.PDF, media.profile)
    assertEquals(2, media.pageCount)
    assertEquals(320, media.pages.first().dimension?.width)
    assertEquals(720, media.pages.last().dimension?.height)
  }

  @Test
  fun `marks invalid pdf as analysis error`() {
    val path = tempDirectory.resolve("invalid.pdf")
    Files.writeString(path, "not a pdf")

    val media =
      PdfMediaAnalyzer().analyze(
        bookId = BookId("book-invalid"),
        path = path,
        analyzeDimensions = false,
        createdAtMillis = 2,
      )

    assertEquals(MediaStatus.ERROR, media.status)
    assertEquals(PdfMediaAnalyzer.ERROR_DOCUMENT, media.comment)
  }
}
