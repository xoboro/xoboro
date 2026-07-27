package io.xoboro.compatibility.komga.api

import io.xoboro.core.application.ArtworkEvent
import io.xoboro.core.application.CatalogImportEvent
import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.application.CatalogMutationKind
import io.xoboro.core.application.LibraryEvent
import io.xoboro.core.application.OrganizationEvent
import io.xoboro.core.application.ReadProgressEvent
import io.xoboro.core.application.UserEvent
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId

class KomgaSseEventBridge(
  private val events: KomgaSseEventHub,
  private val bookSeriesId: (BookId) -> SeriesId? = { null },
) {
  fun publish(event: LibraryEvent) {
    events.publishJson(
      name =
        when (event) {
          is LibraryEvent.Added -> "LibraryAdded"
          is LibraryEvent.Updated -> "LibraryChanged"
          is LibraryEvent.Deleted -> "LibraryDeleted"
        },
      data = KomgaLibrarySseDto(event.library.id.value),
    )
  }

  fun publish(event: CatalogMutationEvent) {
    when (event) {
      is CatalogMutationEvent.Book ->
        events.publishJson(
          name = event.kind.eventName("Book"),
          data =
            KomgaBookSseDto(
              bookId = event.bookId.value,
              seriesId = event.seriesId.value,
              libraryId = event.libraryId.value,
            ),
        )
      is CatalogMutationEvent.Series ->
        events.publishJson(
          name = event.kind.eventName("Series"),
          data =
            KomgaSeriesSseDto(
              seriesId = event.seriesId.value,
              libraryId = event.libraryId.value,
            ),
        )
    }
  }

  fun publish(event: CatalogImportEvent) {
    events.publishJson(
      name = "BookImported",
      data =
        KomgaBookImportSseDto(
          bookId = event.bookId?.value,
          sourceFile = event.sourceFile,
          success = event.success,
          message = event.message,
        ),
      adminOnly = true,
    )
  }

  fun publish(event: OrganizationEvent) {
    when (event) {
      is OrganizationEvent.CollectionAdded ->
        events.publishJson("CollectionAdded", event.collection.toSseDto())
      is OrganizationEvent.CollectionUpdated ->
        events.publishJson("CollectionChanged", event.collection.toSseDto())
      is OrganizationEvent.CollectionDeleted ->
        events.publishJson("CollectionDeleted", event.collection.toSseDto())
      is OrganizationEvent.ReadListAdded ->
        events.publishJson("ReadListAdded", event.readList.toSseDto())
      is OrganizationEvent.ReadListUpdated ->
        events.publishJson("ReadListChanged", event.readList.toSseDto())
      is OrganizationEvent.ReadListDeleted ->
        events.publishJson("ReadListDeleted", event.readList.toSseDto())
    }
  }

  fun publish(event: ReadProgressEvent) {
    when (event) {
      is ReadProgressEvent.Changed ->
        events.publishJson(
          name = "ReadProgressChanged",
          data = KomgaReadProgressSseDto(event.progress.bookId.value, event.userId.value),
          userIdOnly = event.userId.value,
        )
      is ReadProgressEvent.Deleted ->
        events.publishJson(
          name = "ReadProgressDeleted",
          data = KomgaReadProgressSseDto(event.bookId.value, event.userId.value),
          userIdOnly = event.userId.value,
        )
      is ReadProgressEvent.SeriesChanged ->
        events.publishJson(
          name = "ReadProgressSeriesChanged",
          data = KomgaReadProgressSeriesSseDto(event.seriesId.value, event.userId.value),
          userIdOnly = event.userId.value,
        )
      is ReadProgressEvent.SeriesDeleted ->
        events.publishJson(
          name = "ReadProgressSeriesDeleted",
          data = KomgaReadProgressSeriesSseDto(event.seriesId.value, event.userId.value),
          userIdOnly = event.userId.value,
        )
    }
  }

  fun publish(event: ArtworkEvent) {
    val item = event.artwork
    val added = event is ArtworkEvent.Added
    when (item.owner.kind) {
      ArtworkOwnerKind.MEDIA_ITEM ->
        events.publishJson(
          name = if (added) "ThumbnailBookAdded" else "ThumbnailBookDeleted",
          data =
            KomgaThumbnailBookSseDto(
              bookId = item.owner.id,
              seriesId = bookSeriesId(BookId(item.owner.id))?.value.orEmpty(),
              selected = item.selected,
            ),
        )
      ArtworkOwnerKind.SERIES ->
        events.publishJson(
          name = if (added) "ThumbnailSeriesAdded" else "ThumbnailSeriesDeleted",
          data = KomgaThumbnailSeriesSseDto(item.owner.id, item.selected),
        )
      ArtworkOwnerKind.COLLECTION ->
        events.publishJson(
          name =
            if (added) {
              "ThumbnailSeriesCollectionAdded"
            } else {
              "ThumbnailSeriesCollectionDeleted"
            },
          data = KomgaThumbnailCollectionSseDto(item.owner.id, item.selected),
        )
      ArtworkOwnerKind.READ_LIST ->
        events.publishJson(
          name = if (added) "ThumbnailReadListAdded" else "ThumbnailReadListDeleted",
          data = KomgaThumbnailReadListSseDto(item.owner.id, item.selected),
        )
    }
  }

  fun publish(event: UserEvent) {
    when (event) {
      is UserEvent.SessionsExpired ->
        events.publishJson(
          name = "SessionExpired",
          data = KomgaSessionExpiredSseDto(event.userId.value),
          userIdOnly = event.userId.value,
        )
    }
  }

  private fun CatalogMutationKind.eventName(entity: String): String =
    when (this) {
      CatalogMutationKind.ADDED -> "${entity}Added"
      CatalogMutationKind.UPDATED -> "${entity}Changed"
      CatalogMutationKind.DELETED -> "${entity}Deleted"
    }

  private fun io.xoboro.core.domain.SeriesCollection.toSseDto(): KomgaCollectionSseDto =
    KomgaCollectionSseDto(id.value, seriesIds.map { it.value })

  private fun io.xoboro.core.domain.ReadList.toSseDto(): KomgaReadListSseDto =
    KomgaReadListSseDto(id.value, bookIds.map { it.value })
}
