package io.xoboro.core.domain

data class CollectionId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Collection ID must not be blank" }
  }
}

data class SeriesCollection(
  val id: CollectionId,
  val name: String,
  val ordered: Boolean,
  val seriesIds: List<SeriesId>,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(name.isNotBlank()) { "Collection name must not be blank" }
    require(name == name.trim()) { "Collection name must be trimmed" }
    require(seriesIds.distinct().size == seriesIds.size) {
      "Collection series must be unique"
    }
    require(createdAtMillis >= 0) { "Collection creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Collection update timestamp must not precede creation"
    }
  }
}

data class ReadListId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Read-list ID must not be blank" }
  }
}

data class ReadList(
  val id: ReadListId,
  val name: String,
  val summary: String = "",
  val ordered: Boolean = true,
  val bookIds: List<BookId>,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(name.isNotBlank()) { "Read-list name must not be blank" }
    require(name == name.trim()) { "Read-list name must be trimmed" }
    require(bookIds.distinct().size == bookIds.size) { "Read-list books must be unique" }
    require(createdAtMillis >= 0) { "Read-list creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Read-list update timestamp must not precede creation"
    }
  }
}

interface SeriesCollectionRepository {
  fun findByIdOrNull(id: CollectionId): SeriesCollection?

  fun findAll(): List<SeriesCollection>

  fun findAllBySeriesId(seriesId: SeriesId): List<SeriesCollection>

  fun findByNameIgnoreCaseOrNull(name: String): SeriesCollection?

  fun insert(collection: SeriesCollection)

  fun update(collection: SeriesCollection)

  fun delete(id: CollectionId)
}

interface ReadListRepository {
  fun findByIdOrNull(id: ReadListId): ReadList?

  fun findAll(): List<ReadList>

  fun findAllByBookId(bookId: BookId): List<ReadList>

  fun findByNameIgnoreCaseOrNull(name: String): ReadList?

  fun insert(readList: ReadList)

  fun update(readList: ReadList)

  fun delete(id: ReadListId)
}
