package io.xoboro.core.domain

data class LibraryId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Library ID must not be blank" }
  }
}

data class Library(
  val id: LibraryId,
  val name: String,
  val source: SourceLocation,
) {
  init {
    require(name.isNotBlank()) { "Library name must not be blank" }
  }
}

data class SourceLocation(
  val sourceId: String,
  val itemId: String,
) {
  init {
    require(sourceId.isNotBlank()) { "Source ID must not be blank" }
    require(itemId.isNotBlank()) { "Source item ID must not be blank" }
  }
}

enum class MediaKind {
  COMIC_ARCHIVE,
  PDF,
  EPUB,
}

