package io.xoboro.core.domain

enum class PageHashAction {
  DELETE_AUTO,
  DELETE_MANUAL,
  IGNORE,
}

data class KnownPageHash(
  val hash: String,
  val size: Long? = null,
  val action: PageHashAction,
  val deleteCount: Int = 0,
  val matchCount: Int = 0,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(hash.isNotBlank()) { "Page hash must not be blank" }
    require(size == null || size >= 0) { "Page hash size must not be negative" }
    require(deleteCount >= 0) { "Page hash delete count must not be negative" }
    require(matchCount >= 0) { "Page hash match count must not be negative" }
    require(createdAtMillis >= 0) { "Page hash creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Page hash update timestamp must not precede creation"
    }
  }
}

data class UnknownPageHash(
  val hash: String,
  val size: Long? = null,
  val matchCount: Int,
) {
  init {
    require(hash.isNotBlank()) { "Page hash must not be blank" }
    require(size == null || size >= 0) { "Page hash size must not be negative" }
    require(matchCount > 1) { "Unknown page hash must describe duplicate pages" }
  }
}

data class PageHashMatch(
  val mediaItemId: MediaItemId,
  val sourceItemId: String,
  val pageNumber: Int,
  val fileName: String,
  val fileSize: Long,
  val mediaType: String,
) {
  init {
    require(sourceItemId.isNotBlank()) { "Page hash source item ID must not be blank" }
    require(pageNumber > 0) { "Page hash page number must be positive" }
    require(fileName.isNotBlank()) { "Page hash file name must not be blank" }
    require(fileSize >= 0) { "Page hash file size must not be negative" }
    require(mediaType.isNotBlank()) { "Page hash media type must not be blank" }
  }
}
