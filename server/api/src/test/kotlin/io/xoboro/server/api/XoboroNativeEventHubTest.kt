package io.xoboro.server.api

import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookMetadataAggregation
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogGroupCount
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XoboroNativeEventHubTest {
  @Test
  fun `resume replays only what the reconnecting subscriber may see, sharing the live filter`() {
    // Same event, two subscribers with different access. The library grant is identical for
    // both, so only the per-item restriction check can separate them — which is exactly what
    // isVisibleTo shares between the live path (below) and the replay path (on reconnect).
    val catalog = FakeEventCatalog(books = listOf(syntheticBook(MEDIA_ITEM, ageRating = ADULT_AGE)))
    val hub = hub(catalog = catalog)
    val full = readerOf(LIBRARY_ONE)
    val restricted = restrictedReaderOf(LIBRARY_ONE)

    val fullSub = requireNotNull(hub.subscribe(full, lastEventId = null))
    val restrictedSub = requireNotNull(hub.subscribe(restricted, lastEventId = null))
    receive(fullSub) // drain its own ready frame
    val restrictedReadyId = receive(restrictedSub).id

    val event =
      XoboroNativeEvent(
        "media-item.changed",
        XoboroNativeEventScope.MediaItem(LIBRARY_ONE, MEDIA_ITEM, removed = false),
        """{"mediaItemId":"${MEDIA_ITEM.value}"}""",
      )
    hub.publish(event)

    // Live: the fully-permitted subscriber gets it, unchanged.
    assertEquals(event, receive(fullSub).event)

    // Replay: the restricted subscriber reconnects at the point before the event, and the
    // buffered event is still sitting there — but must not come back, even as a burst.
    restrictedSub.close()
    val resumed = requireNotNull(hub.subscribe(restricted, lastEventId = restrictedReadyId))
    receive(resumed).assertControl("stream.ready", resumed = true)
  }

  @Test
  fun `a cold id yields a gap frame before any live event, never a replay`() {
    val hub = hub(bufferCapacity = 2)
    val reader = readerOf(LIBRARY_ONE)

    val probe = requireNotNull(hub.subscribe(reader, lastEventId = null))
    val coldId = receive(probe).id // "<epoch>:0" — nothing published yet
    probe.close()

    // Three publications against a two-slot buffer evict the first two: replaying from seq 0
    // would need seq 1, which is gone.
    hub.publish(libraryEvent(1))
    hub.publish(libraryEvent(2))
    hub.publish(libraryEvent(3))

    val sub = requireNotNull(hub.subscribe(reader, lastEventId = coldId))
    receive(sub).assertControl("stream.resync-required", reason = "gap")

    // The frame ordering is the point: gap first, domain events only afterward.
    val next = libraryEvent(4)
    hub.publish(next)
    assertEquals(next, receive(sub).event)
  }

  @Test
  fun `a stale epoch with an otherwise valid seq is a gap, never a replay`() {
    val hub = hub()
    val reader = readerOf(LIBRARY_ONE)

    val probe = requireNotNull(hub.subscribe(reader, lastEventId = null))
    probe.close()
    hub.publish(libraryEvent(1)) // seq 1, comfortably inside the buffer

    val sub = requireNotNull(hub.subscribe(reader, lastEventId = "some-other-process-epoch:1"))
    receive(sub).assertControl("stream.resync-required", reason = "gap")
  }

  @Test
  fun `no Last-Event-ID means a fresh connection with no resume claim`() {
    val hub = hub()
    val sub = requireNotNull(hub.subscribe(readerOf(LIBRARY_ONE), lastEventId = null))

    receive(sub).assertControl("stream.ready", resumed = false)
  }

  @Test
  fun `a subscriber filtered out of the middle sees a silent numbering gap, no resync`() {
    val hub = hub()
    val reader = readerOf(LIBRARY_ONE)
    val sub = requireNotNull(hub.subscribe(reader, lastEventId = null))
    receive(sub) // drain ready

    val visible1 = XoboroNativeEvent("library.changed", XoboroNativeEventScope.Library(LIBRARY_ONE), """{"n":1}""")
    val hidden = XoboroNativeEvent("library.changed", XoboroNativeEventScope.Library(LIBRARY_TWO), """{"n":2}""")
    val visible3 = XoboroNativeEvent("library.changed", XoboroNativeEventScope.Library(LIBRARY_ONE), """{"n":3}""")
    hub.publish(visible1)
    hub.publish(hidden)
    hub.publish(visible3)

    val first = receive(sub)
    val second = receive(sub)
    assertEquals(visible1, first.event)
    assertEquals(visible3, second.event)
    // The gap in the shared sequence numbering (seq 1, then seq 3) is the expected shape of
    // filtering, not a sign of loss, and must never surface as a resync frame.
    assertEquals(first.seq() + 2, second.seq())
  }

  @Test
  fun `a slow consumer never blocks publish and is told to resync instead of getting a truncated run`() {
    val hub = hub(subscriberCapacity = 4)
    val sub = requireNotNull(hub.subscribe(readerOf(LIBRARY_ONE), lastEventId = null)) // ready occupies 1 of 4

    val events = (1..5).map { libraryEvent(it) }
    // None of these suspend or throw despite the consumer never reading: trySend only.
    events.forEach(hub::publish)

    // The queue overflowed partway through; the whole partial run is discarded in favour of one
    // explicit signal, so the consumer is never handed a burst that looks complete but isn't.
    receive(sub).assertControl("stream.resync-required", reason = "overflow")
    assertEquals(events.last(), receive(sub).event)
  }

  @Test
  fun `a fifth stream for one user supersedes and closes the oldest`() {
    val hub = hub()
    val reader = readerOf(LIBRARY_ONE)
    val streams = (1..4).map { requireNotNull(hub.subscribe(reader, lastEventId = null)) }
    val fifth = requireNotNull(hub.subscribe(reader, lastEventId = null))

    receive(streams[0]).assertControl("stream.resync-required", reason = "superseded")
    assertFailsWith<XoboroNativeEventHubClosedException> { receive(streams[0]) }

    // The new stream is unaffected by the eviction of someone else's oldest connection.
    receive(fifth).assertControl("stream.ready", resumed = false)
  }

  @Test
  fun `the server refuses a subscription beyond its total capacity`() {
    val hub = hub(maxTotalStreams = 2)

    assertNotNull(hub.subscribe(readerOf(LIBRARY_ONE), lastEventId = null))
    assertNotNull(hub.subscribe(readerOf(LIBRARY_TWO), lastEventId = null))
    assertNull(hub.subscribe(readerOf(LIBRARY_ONE), lastEventId = null))
  }

  private fun hub(
    catalog: CatalogReadRepository = FakeEventCatalog(),
    bufferCapacity: Int = XoboroNativeEventHub.DEFAULT_BUFFER_CAPACITY,
    subscriberCapacity: Int = XoboroNativeEventHub.DEFAULT_SUBSCRIBER_CAPACITY,
    maxStreamsPerUser: Int = XoboroNativeEventHub.DEFAULT_MAX_STREAMS_PER_USER,
    maxTotalStreams: Int = XoboroNativeEventHub.DEFAULT_MAX_TOTAL_STREAMS,
  ): XoboroNativeEventHub =
    XoboroNativeEventHub(catalog, bufferCapacity, subscriberCapacity, maxStreamsPerUser, maxTotalStreams)

  private fun receive(subscription: XoboroNativeEventSubscription): XoboroNativeEventEnvelope =
    runBlocking { subscription.receive() }

  private fun libraryEvent(n: Int): XoboroNativeEvent =
    XoboroNativeEvent("library.changed", XoboroNativeEventScope.Library(LIBRARY_ONE), """{"n":$n}""")

  private fun XoboroNativeEventEnvelope.seq(): Long = id.substringAfterLast(":").toLong()

  private fun XoboroNativeEventEnvelope.assertControl(
    name: String,
    reason: String? = null,
    resumed: Boolean? = null,
  ) {
    assertEquals(name, event.name)
    val body = Json.decodeFromString<JsonObject>(event.payload)
    if (reason != null) assertEquals(reason, body["reason"]?.jsonPrimitive?.content)
    if (resumed != null) assertEquals(resumed, body["resumed"]?.jsonPrimitive?.boolean)
  }

  private fun readerOf(libraryId: LibraryId): User =
    User(
      id = UserId("reader-${libraryId.value}"),
      email = "reader@example.invalid",
      passwordHash = "synthetic-hash",
      roles = setOf(UserRole.PAGE_STREAMING),
      sharedLibraryIds = setOf(libraryId),
      sharesAllLibraries = false,
      createdAtMillis = 1,
    )

  private fun restrictedReaderOf(libraryId: LibraryId): User =
    readerOf(libraryId).copy(
      restrictions =
        ContentRestrictions(
          ageRestriction = AgeRestriction(age = ALLOWED_AGE, mode = RestrictionMode.ALLOW_ONLY),
        ),
    )

  private fun syntheticBook(
    id: BookId,
    ageRating: Int? = null,
  ): CatalogBook {
    val seriesId = SeriesId("series-of-${id.value}")
    val seriesMetadata =
      SeriesMetadata(
        seriesId = seriesId,
        title = "Fixture ${seriesId.value}",
        ageRating = ageRating,
        createdAtMillis = 1,
      )
    return CatalogBook(
      book =
        Book(
          id = id,
          libraryId = LIBRARY_ONE,
          seriesId = seriesId,
          name = "Fixture ${id.value}.cbz",
          relativePath = "Fixture/${id.value}.cbz",
          sourceItemId = "file:///synthetic/${id.value}.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          fileSize = 100,
          number = 1,
          createdAtMillis = 1,
        ),
      seriesTitle = seriesMetadata.title,
      seriesMetadata = seriesMetadata,
      metadata =
        BookMetadata(
          bookId = id,
          title = "Fixture ${id.value}",
          number = "1",
          numberSort = 1f,
          createdAtMillis = 1,
        ),
      media = null,
      readProgress = null,
    )
  }

  /**
   * Ported from `XoboroNativeEventVisibilityTest`: honours both `access.libraryIds` and
   * `access.restrictions`, so a fake that ignored either would let the resume/replay assertions
   * above pass with the filter deleted.
   */
  private class FakeEventCatalog(
    private val books: List<CatalogBook> = emptyList(),
    private val series: List<CatalogSeries> = emptyList(),
  ) : CatalogReadRepository {
    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = books.firstOrNull { it.book.id == id && it.isVisible(access) }

    override fun findSeriesByIdOrNull(
      id: SeriesId,
      access: CatalogAccess,
    ): CatalogSeries? = series.firstOrNull { it.series.id == id && it.isVisible(access) }

    override fun findBooks(
      query: BookCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogBook> = error("Event visibility must not page the catalog")

    override fun findSeries(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogSeries> = error("Event visibility must not page the catalog")

    override fun findPreviousBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = error("Not used")

    override fun findNextBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = error("Not used")

    override fun countSeriesByFirstCharacter(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
    ): List<CatalogGroupCount> = error("Not used")

    private fun CatalogSeries.isVisible(access: CatalogAccess): Boolean =
      access.includesLibrary(series.libraryId) &&
        restrictedUser(access).isContentAllowed(
          metadata.ageRating,
          metadata.normalizedSharingLabels,
        )

    private fun CatalogBook.isVisible(access: CatalogAccess): Boolean =
      access.includesLibrary(book.libraryId) &&
        restrictedUser(access).isContentAllowed(
          seriesMetadata.ageRating,
          seriesMetadata.normalizedSharingLabels,
        )

    private fun CatalogAccess.includesLibrary(id: LibraryId): Boolean {
      val granted = libraryIds
      return granted == null || id in granted
    }

    private fun restrictedUser(access: CatalogAccess): User =
      User(
        id = access.userId ?: UserId("catalog-user"),
        email = "catalog@example.invalid",
        passwordHash = "synthetic-hash",
        restrictions = access.restrictions,
        createdAtMillis = 1,
      )
  }

  private companion object {
    val LIBRARY_ONE: LibraryId = LibraryId("library-1")
    val LIBRARY_TWO: LibraryId = LibraryId("library-2")
    val MEDIA_ITEM: BookId = BookId("media-1")
    const val ALLOWED_AGE: Int = 12
    const val ADULT_AGE: Int = 18
  }
}
