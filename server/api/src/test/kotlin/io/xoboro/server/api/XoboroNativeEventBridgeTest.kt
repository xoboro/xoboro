package io.xoboro.server.api

import io.xoboro.core.application.ArtworkEvent
import io.xoboro.core.application.CatalogImportEvent
import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.application.CatalogMutationKind
import io.xoboro.core.application.LibraryEvent
import io.xoboro.core.application.OrganizationEvent
import io.xoboro.core.application.ReadProgressEvent
import io.xoboro.core.application.UserEvent
import io.xoboro.core.domain.Artwork
import io.xoboro.core.domain.ArtworkId
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XoboroNativeEventBridgeTest {
  @Test
  fun `maps library events to library-scoped names carrying just the library id`() {
    listOf(
      LibraryEvent.Added(LIBRARY) to "library.added",
      LibraryEvent.Updated(LIBRARY) to "library.changed",
      LibraryEvent.Deleted(LIBRARY) to "library.removed",
    ).forEach { (event, expectedName) ->
      val mapped = XoboroNativeEventBridge.map(event)
      assertEquals(expectedName, mapped.name)
      assertEquals(XoboroNativeEventScope.Library(LIBRARY_ID), mapped.scope)
      assertEquals("""{"ids":["library-1"]}""", mapped.payload)
    }
  }

  @Test
  fun `maps every catalog mutation kind for both media items and series`() {
    CatalogMutationKind.entries.forEach { kind ->
      val bookMapped =
        XoboroNativeEventBridge.map(CatalogMutationEvent.Book(kind, BOOK_ID, SERIES_ID, LIBRARY_ID))
      assertEquals("media-item.${kind.suffix()}", bookMapped.name)
      assertEquals(
        XoboroNativeEventScope.MediaItem(LIBRARY_ID, BOOK_ID, removed = kind == CatalogMutationKind.DELETED),
        bookMapped.scope,
      )
      assertEquals(
        """{"ids":["book-1"],"libraryId":"library-1","seriesId":"series-1"}""",
        bookMapped.payload,
      )

      val seriesMapped =
        XoboroNativeEventBridge.map(CatalogMutationEvent.Series(kind, SERIES_ID, LIBRARY_ID))
      assertEquals("series.${kind.suffix()}", seriesMapped.name)
      assertEquals(
        XoboroNativeEventScope.Series(LIBRARY_ID, SERIES_ID, removed = kind == CatalogMutationKind.DELETED),
        seriesMapped.scope,
      )
      assertEquals("""{"ids":["series-1"],"libraryId":"library-1"}""", seriesMapped.payload)
    }
  }

  @Test
  fun `never maps catalog import events, since there is no native import endpoint`() {
    val event =
      CatalogImportEvent(
        bookId = BOOK_ID,
        sourceFile = "/synthetic/import.cbz",
        success = true,
      )
    assertNull(XoboroNativeEventBridge.map(event))
  }

  @Test
  fun `maps every organization event to membership-scoped collection and read-list names`() {
    listOf(
      OrganizationEvent.CollectionAdded(COLLECTION) to "collection.added",
      OrganizationEvent.CollectionUpdated(COLLECTION) to "collection.changed",
      OrganizationEvent.CollectionDeleted(COLLECTION) to "collection.removed",
    ).forEach { (event, expectedName) ->
      val mapped = XoboroNativeEventBridge.map(event)
      assertEquals(expectedName, mapped.name)
      assertEquals(XoboroNativeEventScope.SeriesMembers(listOf(SERIES_ID)), mapped.scope)
      assertEquals("""{"ids":["series-1"]}""", mapped.payload)
    }

    listOf(
      OrganizationEvent.ReadListAdded(READ_LIST) to "read-list.added",
      OrganizationEvent.ReadListUpdated(READ_LIST) to "read-list.changed",
      OrganizationEvent.ReadListDeleted(READ_LIST) to "read-list.removed",
    ).forEach { (event, expectedName) ->
      val mapped = XoboroNativeEventBridge.map(event)
      assertEquals(expectedName, mapped.name)
      assertEquals(XoboroNativeEventScope.MediaItemMembers(listOf(BOOK_ID)), mapped.scope)
      assertEquals("""{"ids":["book-1"]}""", mapped.payload)
    }
  }

  @Test
  fun `maps every read progress event to an owner-scoped name`() {
    val userId = UserId("reader-1")

    val changed =
      XoboroNativeEventBridge.map(
        ReadProgressEvent.Changed(
          ReadProgress(bookId = BOOK_ID, userId = userId, page = 3, completed = false, readAtMillis = 1),
        ),
      )
    assertEquals("read-progress.changed", changed.name)
    assertEquals(XoboroNativeEventScope.Owner(userId), changed.scope)
    assertEquals("""{"ids":["book-1"]}""", changed.payload)

    val deleted = XoboroNativeEventBridge.map(ReadProgressEvent.Deleted(BOOK_ID, userId))
    assertEquals("read-progress.removed", deleted.name)
    assertEquals(XoboroNativeEventScope.Owner(userId), deleted.scope)
    assertEquals("""{"ids":["book-1"]}""", deleted.payload)

    val seriesChanged = XoboroNativeEventBridge.map(ReadProgressEvent.SeriesChanged(SERIES_ID, userId))
    assertEquals("series-progress.changed", seriesChanged.name)
    assertEquals(XoboroNativeEventScope.Owner(userId), seriesChanged.scope)
    assertEquals("""{"ids":["series-1"]}""", seriesChanged.payload)

    val seriesDeleted = XoboroNativeEventBridge.map(ReadProgressEvent.SeriesDeleted(SERIES_ID, userId))
    assertEquals("series-progress.removed", seriesDeleted.name)
    assertEquals(XoboroNativeEventScope.Owner(userId), seriesDeleted.scope)
    assertEquals("""{"ids":["series-1"]}""", seriesDeleted.payload)
  }

  @Test
  fun `maps media item and series artwork owners to poster changed`() {
    val mediaItemMapped = XoboroNativeEventBridge.map(ArtworkEvent.Added(artwork(ArtworkOwnerKind.MEDIA_ITEM)))
    assertNotNullAnd(mediaItemMapped) { mapped ->
      assertEquals("poster.changed", mapped.name)
      assertEquals(XoboroNativeEventScope.MediaItemMembers(listOf(BOOK_ID)), mapped.scope)
      assertEquals("""{"ids":["book-1"],"ownerKind":"MEDIA_ITEM"}""", mapped.payload)
    }

    val seriesMapped = XoboroNativeEventBridge.map(ArtworkEvent.Deleted(artwork(ArtworkOwnerKind.SERIES)))
    assertNotNullAnd(seriesMapped) { mapped ->
      assertEquals("poster.changed", mapped.name)
      assertEquals(XoboroNativeEventScope.SeriesMembers(listOf(SERIES_ID)), mapped.scope)
      assertEquals("""{"ids":["series-1"],"ownerKind":"SERIES"}""", mapped.payload)
    }
  }

  @Test
  fun `scopes grouping artwork to the members the event carries`() {
    val collection =
      XoboroNativeEventBridge.map(
        ArtworkEvent.Added(
          artwork(ArtworkOwnerKind.COLLECTION),
          groupingMembers = listOf("series-1", "series-2"),
        ),
      )
    assertNotNullAnd(collection) { mapped ->
      assertEquals("poster.changed", mapped.name)
      assertEquals(
        XoboroNativeEventScope.SeriesMembers(listOf(SERIES_ID, SeriesId("series-2"))),
        mapped.scope,
      )
      // The payload still names the grouping, not its members: a client is being told which cover
      // changed, and the members are only how the server decided who may hear it.
      assertEquals("""{"ids":["collection-1"],"ownerKind":"COLLECTION"}""", mapped.payload)
    }

    val readList =
      XoboroNativeEventBridge.map(
        ArtworkEvent.Deleted(
          artwork(ArtworkOwnerKind.READ_LIST),
          groupingMembers = listOf("book-1"),
        ),
      )
    assertNotNullAnd(readList) { mapped ->
      assertEquals(XoboroNativeEventScope.MediaItemMembers(listOf(BOOK_ID)), mapped.scope)
      assertEquals("""{"ids":["read-list-1"],"ownerKind":"READ_LIST"}""", mapped.payload)
    }
  }

  @Test
  fun `drops grouping artwork with no members rather than widening its scope`() {
    // An empty grouping has no visible members, and XoboroNativeEventScope.SeriesMembers already
    // defines that as not visible. Announcing it would require a scope broader than the truth, so
    // this pins the absence: a future fail-open fallback cannot slip in unnoticed.
    assertNull(XoboroNativeEventBridge.map(ArtworkEvent.Added(artwork(ArtworkOwnerKind.COLLECTION))))
    assertNull(XoboroNativeEventBridge.map(ArtworkEvent.Deleted(artwork(ArtworkOwnerKind.COLLECTION))))
    assertNull(XoboroNativeEventBridge.map(ArtworkEvent.Added(artwork(ArtworkOwnerKind.READ_LIST))))
    assertNull(XoboroNativeEventBridge.map(ArtworkEvent.Deleted(artwork(ArtworkOwnerKind.READ_LIST))))
  }

  @Test
  fun `never maps user events, since sessions-expired is not published from every sign-out path`() {
    assertNull(XoboroNativeEventBridge.map(UserEvent.SessionsExpired(UserId("reader-1"))))
  }

  private inline fun <T> assertNotNullAnd(
    value: T?,
    block: (T) -> Unit,
  ) {
    requireNotNull(value) { "Expected a mapped native event but got null" }
    block(value)
  }

  private fun CatalogMutationKind.suffix(): String =
    when (this) {
      CatalogMutationKind.ADDED -> "added"
      CatalogMutationKind.UPDATED -> "changed"
      CatalogMutationKind.DELETED -> "removed"
    }

  private fun artwork(kind: ArtworkOwnerKind): Artwork =
    Artwork(
      id = ArtworkId("artwork-${kind.name.lowercase()}"),
      owner =
        ArtworkOwner(
          kind,
          when (kind) {
            ArtworkOwnerKind.MEDIA_ITEM -> BOOK_ID.value
            ArtworkOwnerKind.SERIES -> SERIES_ID.value
            ArtworkOwnerKind.COLLECTION -> COLLECTION.id.value
            ArtworkOwnerKind.READ_LIST -> READ_LIST.id.value
          },
        ),
      type = ArtworkType.USER_UPLOADED,
      selected = true,
      mediaType = "image/jpeg",
      fileSize = 1,
      width = 1,
      height = 1,
      createdAtMillis = 1,
    )

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
    val LIBRARY =
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation("synthetic", "root"),
        createdAtMillis = 1,
      )
    val COLLECTION =
      SeriesCollection(
        id = CollectionId("collection-1"),
        name = "Synthetic collection",
        ordered = true,
        seriesIds = listOf(SERIES_ID),
        createdAtMillis = 1,
      )
    val READ_LIST =
      ReadList(
        id = ReadListId("read-list-1"),
        name = "Synthetic read list",
        bookIds = listOf(BOOK_ID),
        createdAtMillis = 1,
      )
  }
}
