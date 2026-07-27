package io.xoboro.core.application

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesRepository

sealed interface OrganizationEvent {
  data class CollectionAdded(
    val collection: SeriesCollection,
  ) : OrganizationEvent

  data class CollectionUpdated(
    val collection: SeriesCollection,
  ) : OrganizationEvent

  data class CollectionDeleted(
    val collection: SeriesCollection,
  ) : OrganizationEvent

  data class ReadListAdded(
    val readList: ReadList,
  ) : OrganizationEvent

  data class ReadListUpdated(
    val readList: ReadList,
  ) : OrganizationEvent

  data class ReadListDeleted(
    val readList: ReadList,
  ) : OrganizationEvent
}

fun interface OrganizationEventPublisher {
  fun publish(event: OrganizationEvent)
}

class OrganizationLifecycle(
  private val collections: SeriesCollectionRepository,
  private val readLists: ReadListRepository,
  private val series: SeriesRepository,
  private val books: BookRepository,
  private val collectionIdFactory: () -> String,
  private val readListIdFactory: () -> String,
  private val currentTimeMillis: () -> Long,
  private val eventPublisher: OrganizationEventPublisher = OrganizationEventPublisher {},
) {
  fun createCollection(
    name: String,
    ordered: Boolean,
    seriesIds: List<SeriesId>,
  ): SeriesCollection {
    val normalizedName = normalizeName(name)
    require(collections.findByNameIgnoreCaseOrNull(normalizedName) == null) {
      "Collection name already exists"
    }
    requireSeries(seriesIds)
    val now = now()
    return SeriesCollection(
      id = CollectionId(collectionIdFactory()),
      name = normalizedName,
      ordered = ordered,
      seriesIds = seriesIds,
      createdAtMillis = now,
    ).also(collections::insert)
      .also { eventPublisher.publish(OrganizationEvent.CollectionAdded(it)) }
  }

  fun updateCollection(
    id: CollectionId,
    name: String? = null,
    ordered: Boolean? = null,
    seriesIds: List<SeriesId>? = null,
  ): SeriesCollection {
    val existing = requireNotNull(collections.findByIdOrNull(id)) { "Collection not found" }
    val normalizedName = name?.let(::normalizeName) ?: existing.name
    val duplicate = collections.findByNameIgnoreCaseOrNull(normalizedName)
    require(duplicate == null || duplicate.id == id) { "Collection name already exists" }
    val updatedSeriesIds = seriesIds ?: existing.seriesIds
    requireSeries(updatedSeriesIds)
    return existing
      .copy(
        name = normalizedName,
        ordered = ordered ?: existing.ordered,
        seriesIds = updatedSeriesIds,
        updatedAtMillis = now(),
      ).also(collections::update)
      .also { eventPublisher.publish(OrganizationEvent.CollectionUpdated(it)) }
  }

  fun deleteCollection(id: CollectionId): Boolean {
    val existing = collections.findByIdOrNull(id) ?: return false
    collections.delete(id)
    eventPublisher.publish(OrganizationEvent.CollectionDeleted(existing))
    return true
  }

  fun createReadList(
    name: String,
    summary: String,
    ordered: Boolean,
    bookIds: List<BookId>,
  ): ReadList {
    val normalizedName = normalizeName(name)
    require(readLists.findByNameIgnoreCaseOrNull(normalizedName) == null) {
      "Read-list name already exists"
    }
    requireBooks(bookIds)
    val now = now()
    return ReadList(
      id = ReadListId(readListIdFactory()),
      name = normalizedName,
      summary = summary,
      ordered = ordered,
      bookIds = bookIds,
      createdAtMillis = now,
    ).also(readLists::insert)
      .also { eventPublisher.publish(OrganizationEvent.ReadListAdded(it)) }
  }

  fun updateReadList(
    id: ReadListId,
    name: String? = null,
    summary: String? = null,
    ordered: Boolean? = null,
    bookIds: List<BookId>? = null,
  ): ReadList {
    val existing = requireNotNull(readLists.findByIdOrNull(id)) { "Read-list not found" }
    val normalizedName = name?.let(::normalizeName) ?: existing.name
    val duplicate = readLists.findByNameIgnoreCaseOrNull(normalizedName)
    require(duplicate == null || duplicate.id == id) { "Read-list name already exists" }
    val updatedBookIds = bookIds ?: existing.bookIds
    requireBooks(updatedBookIds)
    return existing
      .copy(
        name = normalizedName,
        summary = summary ?: existing.summary,
        ordered = ordered ?: existing.ordered,
        bookIds = updatedBookIds,
        updatedAtMillis = now(),
      ).also(readLists::update)
      .also { eventPublisher.publish(OrganizationEvent.ReadListUpdated(it)) }
  }

  fun deleteReadList(id: ReadListId): Boolean {
    val existing = readLists.findByIdOrNull(id) ?: return false
    readLists.delete(id)
    eventPublisher.publish(OrganizationEvent.ReadListDeleted(existing))
    return true
  }

  private fun requireSeries(ids: List<SeriesId>) {
    require(ids.isNotEmpty()) { "Collection must contain at least one series" }
    require(ids.distinct().size == ids.size) { "Collection series must be unique" }
    require(ids.all { series.findByIdOrNull(it)?.deletedAtMillis == null }) {
      "Collection contains an unknown series"
    }
  }

  private fun requireBooks(ids: List<BookId>) {
    require(ids.isNotEmpty()) { "Read-list must contain at least one book" }
    require(ids.distinct().size == ids.size) { "Read-list books must be unique" }
    require(ids.all { books.findByIdOrNull(it)?.deletedAtMillis == null }) {
      "Read-list contains an unknown book"
    }
  }

  private fun normalizeName(name: String): String =
    name.trim().also { require(it.isNotEmpty()) { "Organization name must not be blank" } }

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Timestamp must not be negative" } }
}
