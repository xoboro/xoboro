package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Dimension
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import java.io.IOException
import java.nio.file.Path
import kotlin.math.roundToInt
import org.apache.pdfbox.Loader

class PdfMediaAnalyzer {
  fun analyze(
    bookId: BookId,
    path: Path,
    analyzeDimensions: Boolean,
    createdAtMillis: Long,
    updatedAtMillis: Long = createdAtMillis,
  ): BookMedia =
    try {
      Loader.loadPDF(path.toFile()).use { document ->
        val pages =
          (0 until document.numberOfPages).map { index ->
            val page = document.getPage(index)
            BookPage(
              number = index + 1,
              fileName = "${index + 1}",
              mediaType = PDF_MEDIA_TYPE,
              dimension =
                if (analyzeDimensions) {
                  Dimension(
                    width = page.cropBox.width.roundToInt().coerceAtLeast(1),
                    height = page.cropBox.height.roundToInt().coerceAtLeast(1),
                  )
                } else {
                  null
                },
            )
          }
        if (pages.isEmpty()) {
          BookMedia(
            bookId = bookId,
            status = MediaStatus.ERROR,
            mediaType = PDF_MEDIA_TYPE,
            profile = MediaProfile.PDF,
            comment = ERROR_NO_PAGES,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
          )
        } else {
          BookMedia(
            bookId = bookId,
            status = MediaStatus.READY,
            mediaType = PDF_MEDIA_TYPE,
            profile = MediaProfile.PDF,
            pages = pages,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
          )
        }
      }
    } catch (_: IOException) {
      errorMedia(bookId, createdAtMillis, updatedAtMillis)
    } catch (_: SecurityException) {
      errorMedia(bookId, createdAtMillis, updatedAtMillis)
    }

  private fun errorMedia(
    bookId: BookId,
    createdAtMillis: Long,
    updatedAtMillis: Long,
  ) = BookMedia(
    bookId = bookId,
    status = MediaStatus.ERROR,
    mediaType = PDF_MEDIA_TYPE,
    profile = MediaProfile.PDF,
    comment = ERROR_DOCUMENT,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

  companion object {
    const val PDF_MEDIA_TYPE: String = "application/pdf"
    const val ERROR_DOCUMENT: String = "ERR_1008"
    const val ERROR_NO_PAGES: String = "ERR_1006"
  }
}
