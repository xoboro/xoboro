package io.xoboro.compatibility.komga.api

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
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class KomgaSseEventBridgeTest {
  @Test
  fun `scopes catalog and library events but still broadcasts unscoped families`() =
    runBlocking {
      val hub = KomgaSseEventHub()
      val bridge = KomgaSseEventBridge(hub)
      val excluded =
        user("excluded", setOf(UserRole.PAGE_STREAMING)).copy(
          sharedLibraryIds = setOf(LibraryId("other-library")),
          sharesAllLibraries = false,
        )
      hub.subscribe(excluded).use { events ->
        // Carries a LibraryId, so it is scoped away from this subscriber.
        bridge.publish(LibraryEvent.Added(LIBRARY))
        bridge.publish(CatalogMutationEvent.Series(CatalogMutationKind.ADDED, SERIES_ID, LIBRARY_ID))

        // Carries no library scope in its payload — a collection may legitimately span
        // libraries — so it is still broadcast. This pins a gap recorded in ADR 0087, not
        // desired behaviour: closing it needs a per-event lookup that cannot work for
        // deletions. The assertion exists so narrowing it later is a deliberate change.
        bridge.publish(OrganizationEvent.CollectionAdded(COLLECTION))
        assertEquals("CollectionAdded", events.receive().name)
      }
      hub.close()
    }

  @Test
  fun `maps every global lifecycle mutation to the Komga event contract`() =
    runBlocking {
      val hub = KomgaSseEventHub()
      val bridge =
        KomgaSseEventBridge(hub) { bookId ->
          SERIES_ID.takeIf { bookId == BOOK_ID }
        }
      hub.subscribe(user("admin", setOf(UserRole.ADMIN))).use { events ->
        listOf(
          LibraryEvent.Added(LIBRARY),
          LibraryEvent.Updated(LIBRARY),
          LibraryEvent.Deleted(LIBRARY),
        ).forEach(bridge::publish)
        CatalogMutationKind.entries.forEach { kind ->
          bridge.publish(CatalogMutationEvent.Book(kind, BOOK_ID, SERIES_ID, LIBRARY_ID))
          bridge.publish(CatalogMutationEvent.Series(kind, SERIES_ID, LIBRARY_ID))
        }
        bridge.publish(
          CatalogImportEvent(
            bookId = BOOK_ID,
            sourceFile = "/synthetic/import.cbz",
            success = true,
          ),
        )
        listOf(
          OrganizationEvent.CollectionAdded(COLLECTION),
          OrganizationEvent.CollectionUpdated(COLLECTION),
          OrganizationEvent.CollectionDeleted(COLLECTION),
          OrganizationEvent.ReadListAdded(READ_LIST),
          OrganizationEvent.ReadListUpdated(READ_LIST),
          OrganizationEvent.ReadListDeleted(READ_LIST),
        ).forEach(bridge::publish)
        ArtworkOwnerKind.entries.forEach { ownerKind ->
          val artwork = artwork(ownerKind)
          bridge.publish(ArtworkEvent.Added(artwork))
          bridge.publish(ArtworkEvent.Deleted(artwork))
        }

        val received = List(24) { events.receive() }
        assertEquals(
          listOf(
            "LibraryAdded",
            "LibraryChanged",
            "LibraryDeleted",
            "BookAdded",
            "SeriesAdded",
            "BookChanged",
            "SeriesChanged",
            "BookDeleted",
            "SeriesDeleted",
            "BookImported",
            "CollectionAdded",
            "CollectionChanged",
            "CollectionDeleted",
            "ReadListAdded",
            "ReadListChanged",
            "ReadListDeleted",
            "ThumbnailBookAdded",
            "ThumbnailBookDeleted",
            "ThumbnailSeriesAdded",
            "ThumbnailSeriesDeleted",
            "ThumbnailSeriesCollectionAdded",
            "ThumbnailSeriesCollectionDeleted",
            "ThumbnailReadListAdded",
            "ThumbnailReadListDeleted",
          ),
          received.map { it.name },
        )
        assertEquals(
          """{"bookId":"book-1","seriesId":"series-1","selected":true}""",
          received[16].dataJson,
        )
      }
      hub.close()
    }

  @Test
  fun `delivers progress and session events only to their owning user`() =
    runBlocking {
      val hub = KomgaSseEventHub()
      val bridge = KomgaSseEventBridge(hub)
      val readerId = UserId("reader")
      hub.subscribe(user(readerId.value, setOf(UserRole.PAGE_STREAMING))).use { events ->
        bridge.publish(
          ReadProgressEvent.Changed(
            ReadProgress(
              bookId = BOOK_ID,
              userId = readerId,
              page = 3,
              completed = false,
              readAtMillis = 1,
            ),
          ),
        )
        bridge.publish(ReadProgressEvent.Deleted(BOOK_ID, readerId))
        bridge.publish(ReadProgressEvent.SeriesChanged(SERIES_ID, readerId))
        bridge.publish(ReadProgressEvent.SeriesDeleted(SERIES_ID, readerId))
        bridge.publish(UserEvent.SessionsExpired(readerId))

        val received = List(5) { events.receive() }
        assertEquals(
          listOf(
            "ReadProgressChanged",
            "ReadProgressDeleted",
            "ReadProgressSeriesChanged",
            "ReadProgressSeriesDeleted",
            "SessionExpired",
          ),
          received.map { it.name },
        )
      }
      hub.close()
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

  private fun user(
    id: String,
    roles: Set<UserRole>,
  ): User =
    User(
      id = UserId(id),
      email = "$id@example.invalid",
      passwordHash = "synthetic-hash",
      roles = roles,
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
