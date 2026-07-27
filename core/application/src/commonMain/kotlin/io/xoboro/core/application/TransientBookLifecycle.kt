package io.xoboro.core.application

import io.xoboro.core.domain.BookMedia

data class TransientBook(
  val id: String,
  val path: String,
  val name: String,
  val sizeBytes: Long,
  val fileLastModifiedMillis: Long,
  val media: BookMedia? = null,
) {
  init {
    require(id.isNotBlank()) { "Transient book ID must not be blank" }
    require(path.isNotBlank()) { "Transient book path must not be blank" }
    require(name.isNotBlank()) { "Transient book name must not be blank" }
    require(sizeBytes >= 0) { "Transient book size must not be negative" }
    require(fileLastModifiedMillis >= 0) {
      "Transient book modification timestamp must not be negative"
    }
  }
}

interface TransientBookLifecycle {
  fun scan(path: String): List<TransientBook>

  fun findByIdOrNull(id: String): TransientBook?

  fun analyze(id: String): TransientBook?

  fun openPage(
    id: String,
    pageNumber: Int,
  ): MediaContentStream?
}
