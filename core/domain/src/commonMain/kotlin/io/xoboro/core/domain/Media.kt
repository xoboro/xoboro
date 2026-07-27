package io.xoboro.core.domain

enum class MediaStatus {
  UNKNOWN,
  ERROR,
  READY,
  UNSUPPORTED,
  OUTDATED,
}

enum class MediaProfile {
  DIVINA,
  PDF,
  EPUB,
}

data class Dimension(
  val width: Int,
  val height: Int,
) {
  init {
    require(width > 0) { "Page width must be positive" }
    require(height > 0) { "Page height must be positive" }
  }
}

data class BookPage(
  val number: Int,
  val fileName: String,
  val mediaType: String,
  val fileSize: Long? = null,
  val dimension: Dimension? = null,
  val fileHash: String = "",
) {
  init {
    require(number > 0) { "Page number must be positive" }
    require(fileName.isNotBlank()) { "Page file name must not be blank" }
    require(mediaType.isNotBlank()) { "Page media type must not be blank" }
    require(fileSize == null || fileSize >= 0) { "Page file size must not be negative" }
  }
}

data class MediaFile(
  val fileName: String,
  val mediaType: String? = null,
  val fileSize: Long? = null,
) {
  init {
    require(fileName.isNotBlank()) { "Media file name must not be blank" }
    require(mediaType == null || mediaType.isNotBlank()) {
      "Media file type must be null or non-blank"
    }
    require(fileSize == null || fileSize >= 0) { "Media file size must not be negative" }
  }
}

data class BookMedia(
  val bookId: BookId,
  val status: MediaStatus = MediaStatus.UNKNOWN,
  val mediaType: String? = null,
  val profile: MediaProfile? = null,
  val pages: List<BookPage> = emptyList(),
  val pageCount: Int = pages.size,
  val files: List<MediaFile> = emptyList(),
  val comment: String? = null,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(mediaType == null || mediaType.isNotBlank()) {
      "Media type must be null or non-blank"
    }
    require(pageCount >= 0) { "Media page count must not be negative" }
    require(pages.size <= pageCount) { "Indexed pages must not exceed media page count" }
    require(pages.map(BookPage::number) == (1..pages.size).toList()) {
      "Indexed pages must be contiguous and one-based"
    }
    require(comment == null || comment.isNotBlank()) {
      "Media comment must be null or non-blank"
    }
    require(createdAtMillis >= 0) { "Media creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Media update timestamp must not precede creation"
    }
  }
}

interface BookMediaRepository {
  fun findByBookIdOrNull(bookId: BookId): BookMedia?

  fun upsert(media: BookMedia)

  fun deleteByBookId(bookId: BookId)
}
