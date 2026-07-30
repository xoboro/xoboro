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
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException

/**
 * Analyzes PDF documents.
 *
 * Encryption policy: a document that requires a user password to open is [MediaStatus.UNSUPPORTED],
 * because Xoboro holds no credential for it and never will until the file is replaced. A document
 * encrypted with only an owner password opens on the empty user password - that empty password is
 * the credential its author chose to grant - so it is analyzed and served like any other document.
 * Owner restrictions describe what a conforming viewer should offer (printing, copying); they are
 * not an access barrier, and treating them as one would hide readable books from their owner.
 */
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
            comment = MediaAnalysisComment.NO_PAGES,
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
    } catch (_: InvalidPasswordException) {
      media(bookId, MediaStatus.UNSUPPORTED, MediaAnalysisComment.ENCRYPTED, createdAtMillis, updatedAtMillis)
    } catch (_: IOException) {
      errorMedia(bookId, createdAtMillis, updatedAtMillis)
    } catch (_: SecurityException) {
      errorMedia(bookId, createdAtMillis, updatedAtMillis)
    }

  private fun errorMedia(
    bookId: BookId,
    createdAtMillis: Long,
    updatedAtMillis: Long,
  ) = media(
    bookId,
    MediaStatus.ERROR,
    MediaAnalysisComment.UNREADABLE_CONTAINER,
    createdAtMillis,
    updatedAtMillis,
  )

  private fun media(
    bookId: BookId,
    status: MediaStatus,
    comment: String,
    createdAtMillis: Long,
    updatedAtMillis: Long,
  ) = BookMedia(
    bookId = bookId,
    status = status,
    mediaType = PDF_MEDIA_TYPE,
    profile = MediaProfile.PDF,
    comment = comment,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

  companion object {
    const val PDF_MEDIA_TYPE: String = "application/pdf"
  }
}
