package io.xoboro.core.application

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookPage

enum class PageImageFormat(
  val mediaType: String,
) {
  JPEG("image/jpeg"),
  PNG("image/png"),
}

data class PageImageRequest(
  val format: PageImageFormat? = null,
  val maximumDimension: Int? = null,
) {
  init {
    require(maximumDimension == null || maximumDimension > 0) {
      "Maximum image dimension must be positive"
    }
  }
}

interface MediaContentStream {
  val fileName: String?
    get() = null
  val mediaType: String
  val contentLength: Long?

  fun read(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size,
  ): Int

  fun skip(byteCount: Long): Long {
    require(byteCount >= 0) { "Skip byte count must not be negative" }
    var remaining = byteCount
    val buffer = ByteArray(minOf(8 * 1_024L, remaining.coerceAtLeast(1)).toInt())
    while (remaining > 0) {
      val read = read(buffer, length = minOf(buffer.size.toLong(), remaining).toInt())
      if (read < 0) break
      remaining -= read
    }
    return byteCount - remaining
  }

  fun close()
}

interface BookContentAccess {
  fun pages(bookId: BookId): List<BookPage>?

  fun openPage(
    bookId: BookId,
    pageNumber: Int,
    request: PageImageRequest = PageImageRequest(),
  ): MediaContentStream?

  fun openBook(bookId: BookId): MediaContentStream?
}
