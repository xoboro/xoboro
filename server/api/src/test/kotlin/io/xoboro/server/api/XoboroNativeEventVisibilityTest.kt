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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XoboroNativeEventVisibilityTest {
  @Test
  fun `scopes library events to subscribers holding the grant`() {
    val catalog = FakeEventCatalog()
    val event = event("library.changed", XoboroNativeEventScope.Library(LIBRARY_ONE))

    assertTrue(event.isVisibleTo(readerOf(LIBRARY_ONE), catalog))
    assertFalse(event.isVisibleTo(readerOf(LIBRARY_TWO), catalog))
    assertEquals(0, catalog.lookups, "library scope must be decided without any query")
  }

  @Test
  fun `checks the item itself for subscribers carrying content restrictions`() {
    // Both subscribers hold the same library grant, so a filter that stopped at the grant would
    // pass this. Only the per-item check separates them.
    val catalog = FakeEventCatalog(books = listOf(syntheticBook(MEDIA_ITEM, ageRating = ADULT_AGE)))
    val event =
      event(
        "media-item.changed",
        XoboroNativeEventScope.MediaItem(LIBRARY_ONE, MEDIA_ITEM, removed = false),
      )

    assertTrue(event.isVisibleTo(readerOf(LIBRARY_ONE), catalog))
    assertFalse(event.isVisibleTo(restrictedReaderOf(LIBRARY_ONE), catalog))
  }

  @Test
  fun `skips the item check for removals so clients can drop what they hold`() {
    // The row is gone by the time a removal is published, so an item check would resolve to
    // nothing for everyone and no client would ever learn to drop the entry.
    val catalog = FakeEventCatalog(books = emptyList())
    val removal =
      event(
        "media-item.removed",
        XoboroNativeEventScope.MediaItem(LIBRARY_ONE, MEDIA_ITEM, removed = true),
      )

    assertTrue(removal.isVisibleTo(restrictedReaderOf(LIBRARY_ONE), catalog))
    // The exemption is bounded by the library grant, which is the point: it is not a broadcast.
    assertFalse(removal.isVisibleTo(restrictedReaderOf(LIBRARY_TWO), catalog))
  }

  @Test
  fun `scopes owner events to the subscriber they belong to`() {
    val catalog = FakeEventCatalog()
    val event = event("read-progress.changed", XoboroNativeEventScope.Owner(READER))

    assertTrue(event.isVisibleTo(readerOf(LIBRARY_ONE), catalog))
    assertFalse(event.isVisibleTo(readerOf(LIBRARY_ONE).copy(id = UserId("other")), catalog))
    assertEquals(0, catalog.lookups, "owner scope must be decided without any query")
  }

  @Test
  fun `shows a grouping when at least one member is visible`() {
    val visible = SeriesId("series-visible")
    val hidden = SeriesId("series-hidden")
    val catalog =
      FakeEventCatalog(
        series =
          listOf(
            syntheticSeries(visible, ageRating = CHILD_AGE),
            syntheticSeries(hidden, ageRating = ADULT_AGE),
          ),
      )
    val restricted = restrictedReaderOf(LIBRARY_ONE)

    assertTrue(
      event("collection.changed", XoboroNativeEventScope.SeriesMembers(listOf(hidden, visible)))
        .isVisibleTo(restricted, catalog),
    )
    assertFalse(
      event("collection.changed", XoboroNativeEventScope.SeriesMembers(listOf(hidden)))
        .isVisibleTo(restricted, catalog),
    )
  }

  @Test
  fun `hides a grouping with no members`() {
    // Matches the collection and read list read paths, which return nothing for a grouping with
    // no visible members — so announcing one would advertise something unfetchable.
    val catalog = FakeEventCatalog()

    assertFalse(
      event("collection.changed", XoboroNativeEventScope.SeriesMembers(emptyList()))
        .isVisibleTo(readerOf(LIBRARY_ONE), catalog),
    )
  }

  @Test
  fun `decides groupings without a query for unrestricted subscribers`() {
    // A scan publishing grouping events must not cost one query per member per subscriber for
    // the ordinary case, which is a subscriber with full access and no restrictions.
    val catalog = FakeEventCatalog(series = listOf(syntheticSeries(SeriesId("series-1"))))
    val event =
      event(
        "collection.changed",
        XoboroNativeEventScope.SeriesMembers(List(50) { SeriesId("series-$it") }),
      )

    assertTrue(event.isVisibleTo(unrestrictedReader(), catalog))
    assertEquals(0, catalog.lookups, "an unrestricted all-library subscriber needs no lookup")
  }

  private fun event(
    name: String,
    scope: XoboroNativeEventScope,
  ): XoboroNativeEvent = XoboroNativeEvent(name = name, scope = scope, payload = """{"seq":1}""")

  private fun readerOf(libraryId: LibraryId): User =
    User(
      id = READER,
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

  private fun unrestrictedReader(): User = readerOf(LIBRARY_ONE).copy(sharesAllLibraries = true)

  private fun syntheticSeries(
    id: SeriesId,
    ageRating: Int? = null,
  ): CatalogSeries =
    CatalogSeries(
      series =
        Series(
          id = id,
          libraryId = LIBRARY_ONE,
          name = "Fixture ${id.value}",
          relativePath = "Fixture/${id.value}",
          sourceItemId = "file:///synthetic/${id.value}",
          fileModifiedAtMillis = 1,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      metadata =
        SeriesMetadata(
          seriesId = id,
          title = "Fixture ${id.value}",
          ageRating = ageRating,
          createdAtMillis = 1,
        ),
      booksMetadata = BookMetadataAggregation(createdAtMillis = 1, updatedAtMillis = 1),
      readProgress = null,
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
   * Honours both `access.libraryIds` and `access.restrictions`, and counts lookups.
   *
   * The restriction half is load-bearing: a fake that ignored `access` would let every assertion
   * in this file pass with the filter deleted. The counter is what turns "no query was needed"
   * into an assertion rather than a claim.
   */
  private class FakeEventCatalog(
    private val books: List<CatalogBook> = emptyList(),
    private val series: List<CatalogSeries> = emptyList(),
  ) : CatalogReadRepository {
    var lookups = 0
      private set

    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? {
      lookups += 1
      return books.firstOrNull { it.book.id == id && it.isVisible(access) }
    }

    override fun findSeriesByIdOrNull(
      id: SeriesId,
      access: CatalogAccess,
    ): CatalogSeries? {
      lookups += 1
      return series.firstOrNull { it.series.id == id && it.isVisible(access) }
    }

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
    val READER: UserId = UserId("reader")
    val LIBRARY_ONE: LibraryId = LibraryId("library-1")
    val LIBRARY_TWO: LibraryId = LibraryId("library-2")
    val MEDIA_ITEM: BookId = BookId("media-1")
    const val ALLOWED_AGE: Int = 12
    const val CHILD_AGE: Int = 10
    const val ADULT_AGE: Int = 18
  }
}
