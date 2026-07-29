package io.xoboro.server.api

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
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Maps domain lifecycle events onto [XoboroNativeEvent]s, the shape [XoboroNativeEventHub]
 * consumes.
 *
 * Naming deliberately differs from the Komga-shaped stream ([io.xoboro.compatibility.komga.api]):
 * - `Book*` becomes `media-item.*` because the native catalog surface is `media-items`, and the
 *   roadmap includes video and audio, so `book` becomes false the day a video is scanned.
 * - Komga's eight thumbnail event names collapse into one [POSTER_CHANGED] carrying
 *   `{ownerKind, ids}` — eight strings that all mean "bust the cached poster" is a client-side
 *   switch for nothing. Komga's `selected` flag is dropped too: the client re-fetches regardless.
 * - `*Deleted` becomes `*.removed`: this project has trash and reconciliation, so an item can
 *   leave a catalog by being trashed, moved, or losing its library. `removed` means exactly "no
 *   longer in your catalog", not "the row was deleted".
 *
 * Two families map to nothing, deliberately:
 * - [CatalogImportEvent]: there is no native import endpoint, so no native client can be waiting
 *   on one, and its `sourceFile` is an absolute server path that would put filesystem layout on
 *   the stream.
 * - [UserEvent.SessionsExpired]: published from only one of three session-invalidation paths
 *   (explicit sign-out and idle expiry publish nothing), so closing a stream on it would leave
 *   those other two paths' sessions streaming regardless. The stream re-authenticates itself on
 *   every request instead, which makes the event redundant rather than merely dropped.
 *
 * Every payload carries identifiers only, per [XoboroNativeEvent.payload]: `ids` is always an
 * array even for a single identifier, and routing keys (`libraryId`, `seriesId`, `ownerKind`) are
 * included only when the domain event already carries them for free.
 */
object XoboroNativeEventBridge {
  fun map(event: LibraryEvent): XoboroNativeEvent {
    val libraryId = event.library.id
    val suffix =
      when (event) {
        is LibraryEvent.Added -> "added"
        is LibraryEvent.Updated -> "changed"
        is LibraryEvent.Deleted -> "removed"
      }
    return XoboroNativeEvent(
      name = "library.$suffix",
      scope = XoboroNativeEventScope.Library(libraryId),
      payload = payload(ids = listOf(libraryId.value)),
    )
  }

  /**
   * Library and catalog mutations carry a [io.xoboro.core.domain.LibraryId] on the domain event
   * itself, so the scope stays exact even for removals — a deleted row does not make its own
   * removal event undeliverable. `removed = true` on DELETED skips the per-item restriction
   * check ([XoboroNativeEventScope.MediaItem], [XoboroNativeEventScope.Series]); see their doc
   * comments for why that is required rather than sloppy: the row is already gone by the time a
   * removal is published, so re-checking it would deliver the removal to nobody.
   */
  fun map(event: CatalogMutationEvent): XoboroNativeEvent =
    when (event) {
      is CatalogMutationEvent.Book ->
        XoboroNativeEvent(
          name = "media-item.${event.kind.suffix()}",
          scope =
            XoboroNativeEventScope.MediaItem(
              libraryId = event.libraryId,
              mediaItemId = event.bookId,
              removed = event.kind == CatalogMutationKind.DELETED,
            ),
          payload =
            payload(
              ids = listOf(event.bookId.value),
              libraryId = event.libraryId.value,
              seriesId = event.seriesId.value,
            ),
        )
      is CatalogMutationEvent.Series ->
        XoboroNativeEvent(
          name = "series.${event.kind.suffix()}",
          scope =
            XoboroNativeEventScope.Series(
              libraryId = event.libraryId,
              seriesId = event.seriesId,
              removed = event.kind == CatalogMutationKind.DELETED,
            ),
          payload = payload(ids = listOf(event.seriesId.value), libraryId = event.libraryId.value),
        )
    }

  /**
   * Always `null` — see the class doc for why a native import stream event would be both unused
   * and a filesystem-layout leak.
   */
  fun map(event: CatalogImportEvent): XoboroNativeEvent? =
    null.also { require(event.sourceFile.isNotBlank()) }

  /**
   * Collections and read lists carry their members and have no library of their own — a
   * collection may legitimately span libraries — so [XoboroNativeEventScope.SeriesMembers] /
   * [XoboroNativeEventScope.MediaItemMembers] is the only honest scope, matching what the
   * collection and read-list read paths already return.
   */
  fun map(event: OrganizationEvent): XoboroNativeEvent =
    when (event) {
      is OrganizationEvent.CollectionAdded -> event.collection.toNativeEvent("added")
      is OrganizationEvent.CollectionUpdated -> event.collection.toNativeEvent("changed")
      is OrganizationEvent.CollectionDeleted -> event.collection.toNativeEvent("removed")
      is OrganizationEvent.ReadListAdded -> event.readList.toNativeEvent("added")
      is OrganizationEvent.ReadListUpdated -> event.readList.toNativeEvent("changed")
      is OrganizationEvent.ReadListDeleted -> event.readList.toNativeEvent("removed")
    }

  /** Progress events belong to one user, regardless of library grants. */
  fun map(event: ReadProgressEvent): XoboroNativeEvent =
    when (event) {
      is ReadProgressEvent.Changed ->
        XoboroNativeEvent(
          name = "read-progress.changed",
          scope = XoboroNativeEventScope.Owner(event.userId),
          payload = payload(ids = listOf(event.progress.bookId.value)),
        )
      is ReadProgressEvent.Deleted ->
        XoboroNativeEvent(
          name = "read-progress.removed",
          scope = XoboroNativeEventScope.Owner(event.userId),
          payload = payload(ids = listOf(event.bookId.value)),
        )
      is ReadProgressEvent.SeriesChanged ->
        XoboroNativeEvent(
          name = "series-progress.changed",
          scope = XoboroNativeEventScope.Owner(event.userId),
          payload = payload(ids = listOf(event.seriesId.value)),
        )
      is ReadProgressEvent.SeriesDeleted ->
        XoboroNativeEvent(
          name = "series-progress.removed",
          scope = XoboroNativeEventScope.Owner(event.userId),
          payload = payload(ids = listOf(event.seriesId.value)),
        )
    }

  /**
   * `poster.changed` for owner kinds whose visibility can be decided from what [ArtworkEvent]
   * actually carries — only `owner.kind` and `owner.id`, never the owning grouping's membership.
   *
   * [ArtworkOwnerKind.MEDIA_ITEM] and [ArtworkOwnerKind.SERIES] scope honestly to that one
   * identifier via [XoboroNativeEventScope.MediaItemMembers] / [XoboroNativeEventScope.SeriesMembers]:
   * a singleton membership list is exactly "visible if this one item is visible", the same check
   * the read paths already run, and it costs the bridge no repository lookup of its own.
   *
   * [ArtworkOwnerKind.COLLECTION] and [ArtworkOwnerKind.READ_LIST] would need the collection's or
   * read list's member series/books to scope the same way, and the event does not carry them.
   * Resolving that here would mean the bridge doing its own repository lookup — inventing a
   * broader-than-true fallback (or no scope at all) would look right and not be, so these two
   * owner kinds are left unmapped for now instead.
   */
  fun map(event: ArtworkEvent): XoboroNativeEvent? {
    val owner = event.artwork.owner
    val scope =
      when (owner.kind) {
        ArtworkOwnerKind.MEDIA_ITEM ->
          XoboroNativeEventScope.MediaItemMembers(listOf(BookId(owner.id)))
        ArtworkOwnerKind.SERIES ->
          XoboroNativeEventScope.SeriesMembers(listOf(SeriesId(owner.id)))
        ArtworkOwnerKind.COLLECTION, ArtworkOwnerKind.READ_LIST -> return null
      }
    return XoboroNativeEvent(
      name = POSTER_CHANGED,
      scope = scope,
      payload = payload(ids = listOf(owner.id), ownerKind = owner.kind.name),
    )
  }

  /**
   * Always `null` — see the class doc for why closing native streams on this event would be
   * incomplete rather than merely redundant.
   */
  fun map(event: UserEvent): XoboroNativeEvent? =
    when (event) {
      is UserEvent.SessionsExpired -> null
    }

  private fun CatalogMutationKind.suffix(): String =
    when (this) {
      CatalogMutationKind.ADDED -> "added"
      CatalogMutationKind.UPDATED -> "changed"
      CatalogMutationKind.DELETED -> "removed"
    }

  private fun SeriesCollection.toNativeEvent(suffix: String): XoboroNativeEvent =
    XoboroNativeEvent(
      name = "collection.$suffix",
      scope = XoboroNativeEventScope.SeriesMembers(seriesIds),
      payload = payload(ids = seriesIds.map { it.value }),
    )

  private fun ReadList.toNativeEvent(suffix: String): XoboroNativeEvent =
    XoboroNativeEvent(
      name = "read-list.$suffix",
      scope = XoboroNativeEventScope.MediaItemMembers(bookIds),
      payload = payload(ids = bookIds.map { it.value }),
    )

  private fun payload(
    ids: List<String>,
    libraryId: String? = null,
    seriesId: String? = null,
    ownerKind: String? = null,
  ): String =
    PAYLOAD_JSON.encodeToString(
      NativeEventPayload(ids = ids, libraryId = libraryId, seriesId = seriesId, ownerKind = ownerKind),
    )

  private const val POSTER_CHANGED = "poster.changed"

  private val PAYLOAD_JSON = Json { explicitNulls = false }
}

/**
 * Identifiers-only payload shape shared by every native event. See [XoboroNativeEvent.payload]
 * for why no entity snapshot ever belongs here, and the class doc above for why no timestamp
 * does either — the client's clock is authoritative, not the server's emit clock.
 */
@Serializable
private data class NativeEventPayload(
  val ids: List<String>,
  val libraryId: String? = null,
  val seriesId: String? = null,
  val ownerKind: String? = null,
)
