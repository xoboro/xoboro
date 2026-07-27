package io.xoboro.core.domain

data class SeriesId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Series ID must not be blank" }
  }
}

data class BookId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Book ID must not be blank" }
  }
}

data class Series(
  val id: SeriesId,
  val libraryId: LibraryId,
  val name: String,
  val relativePath: String,
  val sourceItemId: String,
  val fileModifiedAtMillis: Long,
  val bookCount: Int = 0,
  val deletedAtMillis: Long? = null,
  val oneshot: Boolean = false,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(name.isNotBlank()) { "Series name must not be blank" }
    require(relativePath.isNotBlank()) { "Series relative path must not be blank" }
    require(sourceItemId.isNotBlank()) { "Series source item ID must not be blank" }
    require(fileModifiedAtMillis >= 0) { "Series file timestamp must not be negative" }
    require(bookCount >= 0) { "Series book count must not be negative" }
    require(deletedAtMillis == null || deletedAtMillis >= 0) {
      "Series deletion timestamp must not be negative"
    }
    require(createdAtMillis >= 0) { "Series creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Series update timestamp must not precede creation"
    }
  }
}

data class Book(
  val id: BookId,
  val libraryId: LibraryId,
  val seriesId: SeriesId,
  val name: String,
  val relativePath: String,
  val sourceItemId: String,
  val sourceIdentity: String? = null,
  val mediaKind: MediaKind,
  val fileModifiedAtMillis: Long,
  val fileSize: Long = 0,
  val fileHash: String = "",
  val fileHashKoreader: String = "",
  val number: Int = 0,
  val deletedAtMillis: Long? = null,
  val oneshot: Boolean = false,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(name.isNotBlank()) { "Book name must not be blank" }
    require(relativePath.isNotBlank()) { "Book relative path must not be blank" }
    require(sourceItemId.isNotBlank()) { "Book source item ID must not be blank" }
    require(sourceIdentity == null || sourceIdentity.isNotBlank()) {
      "Book source identity must be null or non-blank"
    }
    require(fileModifiedAtMillis >= 0) { "Book file timestamp must not be negative" }
    require(fileSize >= 0) { "Book file size must not be negative" }
    require(number >= 0) { "Book number must not be negative" }
    require(deletedAtMillis == null || deletedAtMillis >= 0) {
      "Book deletion timestamp must not be negative"
    }
    require(createdAtMillis >= 0) { "Book creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Book update timestamp must not precede creation"
    }
  }
}

interface SeriesRepository {
  fun findByIdOrNull(id: SeriesId): Series?

  fun findAllByLibraryId(libraryId: LibraryId): List<Series>

  fun findByLibraryIdAndRelativePath(
    libraryId: LibraryId,
    relativePath: String,
  ): Series?

  fun insert(series: Series)

  fun insertAll(series: Collection<Series>)

  fun update(series: Series)

  fun updateAll(series: Collection<Series>)

  fun delete(id: SeriesId)

  fun count(): Long
}

interface BookRepository {
  fun findByIdOrNull(id: BookId): Book?

  fun findAllByLibraryId(libraryId: LibraryId): List<Book>

  fun findAllBySeriesId(seriesId: SeriesId): List<Book>

  fun findByLibraryIdAndRelativePath(
    libraryId: LibraryId,
    relativePath: String,
  ): Book?

  fun insert(book: Book)

  fun insertAll(books: Collection<Book>)

  fun update(book: Book)

  fun updateAll(books: Collection<Book>)

  fun delete(id: BookId)

  fun count(): Long
}
