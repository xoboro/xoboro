package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.BookMetadataAggregation
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogGroupCount
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Covers `GET /series/{seriesId}/file` and `GET /read-lists/{readListId}/file`.
 *
 * The fixture is deliberately larger than the happy path needs, because the defects an archive route
 * has are all invisible in a small one. Its series holds eight items: three readable in a `numberSort`
 * order that disagrees with insertion order, one in a library only some callers are granted, one whose
 * file name collides with another's, one whose content reports no file name, one whose content cannot
 * be opened, and one deleted. Its read list holds two members no restricted caller may read *before*
 * the two it may, so filtering, ordering and member numbering can each be told apart from doing
 * nothing.
 *
 * [FakeArchiveCatalog] applies the caller's [CatalogAccess] the way `JooqCatalogReadRepository` does -
 * a library grant per media item and an excluded sharing label through the item's series - and refuses
 * a sort property it does not know, the way the SQL layer does. A fake that answered every access and
 * every sort identically would let an assertion about ordering or filtering pass with the rule gone.
 */
class XoboroNativeArchiveTest {
  @Test
  fun `series archive streams visible items in number order under unique entry names`() =
    testApplication {
      val fixture = Fixture.unrestrictedDownloader()
      installArchives(fixture)

      val response = client.get(SERIES_ARCHIVE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("application/zip", response.headers[HttpHeaders.ContentType])
      val entries = response.body<ByteArray>().zipEntries()
      // Ordered by `numberSort`, which the fixture stores out of order: insertion order would begin
      // two, three, one and reverse order would be the mirror of this list. The colliding name near
      // the end also rules out a route that sorted by entry name.
      assertEquals(
        listOf(
          "synthetic-one.cbz",
          "synthetic-two.cbz",
          "synthetic-three.cbz",
          "synthetic-elsewhere.cbz",
          "synthetic-one (2).cbz",
          "item-nameless.bin",
        ),
        entries.map { it.first },
      )
      // Bytes, not just names: an archive of correctly named empty entries is what a half-connected
      // writer produces.
      assertEquals(
        listOf(
          "one-content",
          "two-content",
          "three-content",
          "elsewhere-content",
          "duplicate-content",
          "nameless-content",
        ),
        entries.map { it.second },
      )
    }

  @Test
  fun `series archive omits a deleted item and one whose content is unavailable`() =
    testApplication {
      // Both are in the series and visible to this caller, so the entry list above already excludes
      // them - this names why. A deleted item's file is gone, and an unopenable one would otherwise
      // truncate the archive mid-stream, long after the 200 was sent.
      val fixture = Fixture.unrestrictedDownloader()
      installArchives(fixture)

      val response = client.get(SERIES_ARCHIVE_PATH) { bearerAuth(fixture.token) }
      val entryNames = response.body<ByteArray>().zipEntries().map { it.first }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(6, entryNames.size, entryNames.toString())
      assertTrue(entryNames.none { "deleted" in it }, entryNames.toString())
      assertTrue(entryNames.none { "unavailable" in it }, entryNames.toString())
      // The unopenable item was still asked for, so its absence is the writer skipping an empty
      // answer rather than the catalog never offering it. The deleted one was never asked for.
      assertTrue(UNAVAILABLE_ID in fixture.content.requestedBookIds)
      assertTrue(DELETED_ID !in fixture.content.requestedBookIds)
    }

  @Test
  fun `series archive excludes an item the caller may not read and never opens it`() =
    testApplication {
      // The property that turns this route into an access-control hole if it is missing: the caller
      // can see the series, so the archive is streamed, and the filter on its *members* is the only
      // thing keeping a file from a library this caller was never granted out of the download.
      val fixture = Fixture.restrictedDownloader()
      installArchives(fixture)

      val response = client.get(SERIES_ARCHIVE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      val entryNames = response.body<ByteArray>().zipEntries().map { it.first }
      assertEquals(
        listOf(
          "synthetic-one.cbz",
          "synthetic-two.cbz",
          "synthetic-three.cbz",
          "synthetic-one (2).cbz",
          "item-nameless.bin",
        ),
        entryNames,
      )
      // Not merely absent from the container: never read from storage. An implementation that opened
      // every item and dropped the forbidden bytes afterwards would still have read them.
      assertTrue(
        ELSEWHERE_ID !in fixture.content.requestedBookIds,
        fixture.content.requestedBookIds.toString(),
      )
    }

  @Test
  fun `series archive names the download after the series with unsafe characters replaced`() =
    testApplication {
      val fixture = Fixture.unrestrictedDownloader()
      installArchives(fixture)

      val response = client.get(SERIES_ARCHIVE_PATH) { bearerAuth(fixture.token) }

      val disposition = assertNotNull(response.headers[HttpHeaders.ContentDisposition])
      // The series title carries `:` and `/`, which an extractor would read as structure. Both forms
      // are present because the native surface offers an ASCII fallback beside the extended
      // parameter, exactly as a single media item's download does.
      assertTrue(
        "filename=\"Synthetic archive_ volume_set.zip\"" in disposition,
        disposition,
      )
      assertTrue(
        "filename*=UTF-8''Synthetic%20archive_%20volume_set.zip" in disposition,
        disposition,
      )
    }

  @Test
  fun `series archive is streamed without a length or a range validator`() =
    testApplication {
      val fixture = Fixture.unrestrictedDownloader()
      installArchives(fixture)

      val response = client.get(SERIES_ARCHIVE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      // The container is assembled as it is written, so its length is unknown when the headers go
      // out. Advertising a validator or byte ranges would invite a client to resume against a
      // container this server never stored.
      assertNull(response.headers[HttpHeaders.ContentLength])
      assertNull(response.headers[HttpHeaders.ETag])
      assertNull(response.headers[HttpHeaders.AcceptRanges])
    }

  @Test
  fun `series archive closes every content stream it opened`() =
    testApplication {
      val fixture = Fixture.unrestrictedDownloader()
      installArchives(fixture)

      client.get(SERIES_ARCHIVE_PATH) { bearerAuth(fixture.token) }.body<ByteArray>()

      assertEquals(6, fixture.content.streams.size)
      assertTrue(fixture.content.streams.all { it.closed }, "every opened member must be closed")
    }

  @Test
  fun `series archive requires the file download permission before any catalog read`() =
    testApplication {
      val fixture = Fixture.pageReader()
      installArchives(fixture)

      val response = client.get(SERIES_ARCHIVE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("file_download_forbidden", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.catalog.seriesLookupCount)
      assertTrue(fixture.content.requestedBookIds.isEmpty())
    }

  @Test
  fun `series archive hides an unauthorized series behind a not found`() =
    testApplication {
      val fixture = Fixture.outsiderDownloader()
      installArchives(fixture)

      val response = client.get(SERIES_ARCHIVE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("series_not_found", response.body<XoboroApiError>().code)
      assertTrue(fixture.content.requestedBookIds.isEmpty())
    }

  @Test
  fun `read list archive keeps list order and numbers members by their list position`() =
    testApplication {
      val fixture = Fixture.restrictedDownloader()
      installArchives(fixture)

      val response = client.get(READ_LIST_ARCHIVE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      val entries = response.body<ByteArray>().zipEntries()
      // The list holds four ids: one in a series this caller's excluded sharing label hides, one in
      // an ungranted library, then the two it may read. So this single expectation carries three
      // rules at once:
      //  - both restriction dimensions filter members, not just the library grant;
      //  - the order is the read list's, which is the reverse of the `numberSort` order the series
      //    archive uses, so a route that reused the series ordering would answer these swapped;
      //  - the numbers are positions in the list, not in the filtered result, so dropping the first
      //    two members leaves 3 and 4 rather than renumbering to 1 and 2.
      assertEquals(
        listOf("3 - synthetic-two.cbz", "4 - synthetic-one.cbz"),
        entries.map { it.first },
      )
      assertEquals(listOf("two-content", "one-content"), entries.map { it.second })
      assertTrue(
        fixture.content.requestedBookIds.none { it == ELSEWHERE_ID || it == LABELLED_ID },
        fixture.content.requestedBookIds.toString(),
      )
      assertEquals(
        "attachment; filename=\"Synthetic list_ archive.zip\"; " +
          "filename*=UTF-8''Synthetic%20list_%20archive.zip",
        response.headers[HttpHeaders.ContentDisposition],
      )
    }

  @Test
  fun `read list archive requires the file download permission before any lookup`() =
    testApplication {
      val fixture = Fixture.pageReader()
      installArchives(fixture)

      val response = client.get(READ_LIST_ARCHIVE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("file_download_forbidden", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.readLists.lookupCount)
      assertTrue(fixture.content.requestedBookIds.isEmpty())
    }

  @Test
  fun `read list archive answers a missing list and an entirely invisible one identically`() =
    testApplication {
      // A read list is visible through its members, so a caller who may read none of them must not
      // be able to tell it apart from an identifier that does not exist.
      val fixture = Fixture.outsiderDownloader()
      installArchives(fixture)

      val invisible = client.get(READ_LIST_ARCHIVE_PATH) { bearerAuth(fixture.token) }
      val missing = client.get(MISSING_READ_LIST_ARCHIVE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, invisible.status)
      assertEquals("read_list_not_found", invisible.body<XoboroApiError>().code)
      assertEquals(HttpStatusCode.NotFound, missing.status)
      assertEquals("read_list_not_found", missing.body<XoboroApiError>().code)
      assertTrue(fixture.content.requestedBookIds.isEmpty())
    }

  @Test
  fun `archives require authentication`() =
    testApplication {
      val fixture = Fixture.unrestrictedDownloader()
      installArchives(fixture)

      for (path in listOf(SERIES_ARCHIVE_PATH, READ_LIST_ARCHIVE_PATH)) {
        assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, path)
      }
      assertTrue(fixture.content.requestedBookIds.isEmpty())
    }

  private fun ApplicationTestBuilder.installArchives(fixture: Fixture) {
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      routing {
        xoboroNativeArchiveRoutes(fixture.catalog, fixture.readLists, fixture.content)
      }
    }
    client =
      createClient {
        install(ClientContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      }
  }

  private fun ByteArray.zipEntries(): List<Pair<String, String>> =
    ZipInputStream(ByteArrayInputStream(this)).use { archive ->
      buildList {
        while (true) {
          val entry = archive.nextEntry ?: break
          add(entry.name to archive.readBytes().decodeToString())
          archive.closeEntry()
        }
      }
    }

  private class Fixture private constructor(
    user: User,
  ) {
    val sessions =
      UserSessionLifecycle(
        users = InMemoryUserRepository(user),
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory = { "archive-token" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val token = requireNotNull(sessions.create(user)).plainToken
    val catalog = FakeArchiveCatalog(SERIES_ITEMS, listOf(SERIES, LABELLED_SERIES))
    val readLists = FakeReadListRepository(READ_LIST)
    val content = FakeArchiveContent(MEMBER_CONTENT)

    companion object {
      /** Sees every library and is restricted from nothing. */
      fun unrestrictedDownloader(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.FILE_DOWNLOAD),
            sharesAllLibraries = true,
          ),
        )

      /** Sees the series' library only, and is excluded from one sharing label. */
      fun restrictedDownloader(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.FILE_DOWNLOAD),
            sharesAllLibraries = false,
            sharedLibraryIds = setOf(SERIES_LIBRARY_ID),
            restrictions = ContentRestrictions(labelsExclude = setOf(EXCLUDED_LABEL)),
          ),
        )

      /** May download, but is granted a library that holds none of the fixture. */
      fun outsiderDownloader(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.FILE_DOWNLOAD),
            sharesAllLibraries = false,
            sharedLibraryIds = setOf(LibraryId("library-unrelated")),
          ),
        )

      /** May stream pages but not download files. */
      fun pageReader(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.PAGE_STREAMING),
            sharesAllLibraries = true,
          ),
        )
    }
  }

  /**
   * A catalog that answers according to the [CatalogAccess] it is handed.
   *
   * Visibility mirrors `JooqCatalogReadRepository`: a media item is filtered by its own `library_id`
   * and by an excluded sharing label on the series it belongs to. Those two columns are independent
   * there, which is why this fixture can hold an item whose library differs from its series'.
   */
  private class FakeArchiveCatalog(
    private val items: List<CatalogBook>,
    private val seriesEntries: List<CatalogSeries>,
  ) : CatalogReadRepository {
    var seriesLookupCount = 0
      private set

    override fun findBooks(
      query: BookCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogBook> {
      val sortProperties = page.sorts.map(CatalogSort::property)
      // The SQL layer rejects an unknown sort property instead of ignoring it, so an assertion about
      // the order this route asked for is not an assertion against something that accepts anything.
      val unsupported = sortProperties - SUPPORTED_SORT_PROPERTIES
      if (unsupported.isNotEmpty()) {
        error("Unsupported catalog sort property: ${unsupported.first()}")
      }
      val matching =
        items.filter { item ->
          item.book.seriesId == query.seriesId &&
            item.isVisibleTo(access) &&
            (query.deleted != false || item.book.deletedAtMillis == null)
        }
      val ordered =
        if (NUMBER_SORT_PROPERTY in sortProperties) {
          matching.sortedBy { item -> item.metadata.numberSort }
        } else {
          matching
        }
      return CatalogPage(
        content = ordered,
        page = 0,
        size = ordered.size.coerceAtLeast(1),
        totalElements = ordered.size.toLong(),
        unpaged = page.unpaged,
        sorts = page.sorts,
      )
    }

    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = items.firstOrNull { it.book.id == id && it.isVisibleTo(access) }

    override fun findPreviousBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = error("Archive downloads must not navigate between media items")

    override fun findNextBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = error("Archive downloads must not navigate between media items")

    override fun findSeries(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogSeries> = error("Archive downloads must not list series")

    override fun findSeriesByIdOrNull(
      id: SeriesId,
      access: CatalogAccess,
    ): CatalogSeries? {
      seriesLookupCount += 1
      return seriesEntries.firstOrNull { entry ->
        entry.series.id == id && entry.isVisibleTo(access)
      }
    }

    override fun countSeriesByFirstCharacter(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
    ): List<CatalogGroupCount> = error("Archive downloads must not group series")

    private companion object {
      val SUPPORTED_SORT_PROPERTIES = listOf(NUMBER_SORT_PROPERTY, "createdAt", "name")
    }
  }

  private class FakeReadListRepository(
    private val stored: ReadList,
  ) : ReadListRepository {
    var lookupCount = 0
      private set

    override fun findByIdOrNull(id: ReadListId): ReadList? {
      lookupCount += 1
      return stored.takeIf { it.id == id }
    }

    override fun findAll(): List<ReadList> = error("Archive downloads must not list read lists")

    override fun findAllByBookId(bookId: BookId): List<ReadList> =
      error("Archive downloads must not search read lists by member")

    override fun findByNameIgnoreCaseOrNull(name: String): ReadList? =
      error("Archive downloads must not search read lists by name")

    override fun insert(readList: ReadList) = error("Archive downloads must not write read lists")

    override fun update(readList: ReadList) = error("Archive downloads must not write read lists")

    override fun delete(id: ReadListId) = error("Archive downloads must not write read lists")
  }

  private class FakeArchiveContent(
    private val contents: Map<BookId, MemberContent>,
  ) : BookContentAccess {
    val requestedBookIds = mutableListOf<BookId>()
    val streams = mutableListOf<FakeArchiveStream>()

    override fun pages(bookId: BookId): List<BookPage>? =
      error("Archive downloads must not read page manifests")

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream? = error("Archive downloads must not decode pages")

    override fun openBook(bookId: BookId): MediaContentStream? {
      requestedBookIds += bookId
      val content = contents[bookId] ?: return null
      return FakeArchiveStream(content.fileName, content.bytes).also { streams += it }
    }
  }

  private class MemberContent(
    val fileName: String?,
    val bytes: ByteArray,
  )

  private class FakeArchiveStream(
    override val fileName: String?,
    private val bytes: ByteArray,
  ) : MediaContentStream {
    private var position = 0
    var closed = false
      private set
    override val mediaType: String = "application/zip"
    override val contentLength: Long = bytes.size.toLong()

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (position >= bytes.size) return -1
      val count = minOf(length, bytes.size - position)
      bytes.copyInto(buffer, offset, position, position + count)
      position += count
      return count
    }

    override fun close() {
      closed = true
    }
  }

  private class InMemoryUserRepository(
    user: User,
  ) : UserRepository {
    private val values = linkedMapOf(user.id to user)

    override fun count(): Long = values.size.toLong()

    override fun findByIdOrNull(id: UserId): User? = values[id]

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      values.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = values.values.toList()

    override fun insert(user: User) {
      if (findByEmailIgnoreCaseOrNull(user.email) != null) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      values[user.id] = user
    }

    override fun claimIfEmpty(user: User): Boolean = false

    override fun update(user: User) {
      values[user.id] = user
    }

    override fun delete(id: UserId) {
      values.remove(id)
    }
  }

  private companion object {
    const val NUMBER_SORT_PROPERTY = "numberSort"
    const val EXCLUDED_LABEL = "synthetic-excluded"

    val SERIES_LIBRARY_ID = LibraryId("library-series")
    val OTHER_LIBRARY_ID = LibraryId("library-other")
    val SERIES_ID = SeriesId("series-archive")
    val LABELLED_SERIES_ID = SeriesId("series-labelled")
    val READ_LIST_ID = ReadListId("read-list-archive")
    val ONE_ID = BookId("item-one")
    val TWO_ID = BookId("item-two")
    val THREE_ID = BookId("item-three")
    val ELSEWHERE_ID = BookId("item-elsewhere")
    val DUPLICATE_ID = BookId("item-duplicate")
    val NAMELESS_ID = BookId("item-nameless")
    val UNAVAILABLE_ID = BookId("item-unavailable")
    val DELETED_ID = BookId("item-deleted")
    val LABELLED_ID = BookId("item-labelled")

    val SERIES_ARCHIVE_PATH = "$XOBORO_API_PREFIX/series/${SERIES_ID.value}/file"
    val READ_LIST_ARCHIVE_PATH = "$XOBORO_API_PREFIX/read-lists/${READ_LIST_ID.value}/file"
    val MISSING_READ_LIST_ARCHIVE_PATH = "$XOBORO_API_PREFIX/read-lists/read-list-absent/file"

    val SERIES: CatalogSeries =
      syntheticSeries(
        seriesId = SERIES_ID,
        libraryId = SERIES_LIBRARY_ID,
        // `:` and `/` have to be neutralised before this reaches a file name.
        title = "Synthetic archive: volume/set",
      )

    val LABELLED_SERIES: CatalogSeries =
      syntheticSeries(
        seriesId = LABELLED_SERIES_ID,
        libraryId = SERIES_LIBRARY_ID,
        title = "Synthetic labelled series",
        sharingLabels = setOf(EXCLUDED_LABEL),
      )

    /** Stored out of `numberSort` order on purpose, so insertion order and sorted order differ. */
    val SERIES_ITEMS: List<CatalogBook> =
      listOf(
        syntheticItem(TWO_ID, numberSort = 2F),
        syntheticItem(THREE_ID, numberSort = 3F),
        syntheticItem(ONE_ID, numberSort = 1F),
        // Same series, different library: the catalog filters an item's library independently of its
        // series', so a caller who may see the series can still be barred from this item.
        syntheticItem(ELSEWHERE_ID, numberSort = 4F, libraryId = OTHER_LIBRARY_ID),
        syntheticItem(DUPLICATE_ID, numberSort = 5F),
        syntheticItem(NAMELESS_ID, numberSort = 6F),
        syntheticItem(UNAVAILABLE_ID, numberSort = 7F),
        syntheticItem(DELETED_ID, numberSort = 8F, deletedAtMillis = 9),
        syntheticItem(LABELLED_ID, numberSort = 1F, seriesEntry = LABELLED_SERIES),
      )

    /**
     * Two members no restricted caller may read come first, so numbering by list position is
     * distinguishable from numbering the filtered result, and the list's order is the reverse of the
     * series' `numberSort` order.
     */
    val READ_LIST =
      ReadList(
        id = READ_LIST_ID,
        name = "Synthetic list: archive",
        bookIds = listOf(LABELLED_ID, ELSEWHERE_ID, TWO_ID, ONE_ID),
        createdAtMillis = 1,
      )

    /**
     * `DUPLICATE_ID` deliberately reports the leaf name `ONE_ID` already used, `NAMELESS_ID` reports
     * no file name at all, and `UNAVAILABLE_ID` is absent so its content cannot be opened.
     */
    val MEMBER_CONTENT: Map<BookId, MemberContent> =
      mapOf(
        ONE_ID to memberContent("/synthetic/set/synthetic-one.cbz", "one-content"),
        TWO_ID to memberContent("/synthetic/set/synthetic-two.cbz", "two-content"),
        THREE_ID to memberContent("/synthetic/set/synthetic-three.cbz", "three-content"),
        ELSEWHERE_ID to memberContent("/synthetic/other/synthetic-elsewhere.cbz", "elsewhere-content"),
        DUPLICATE_ID to memberContent("/synthetic/set/extra/synthetic-one.cbz", "duplicate-content"),
        NAMELESS_ID to memberContent(null, "nameless-content"),
        LABELLED_ID to memberContent("/synthetic/set/synthetic-labelled.cbz", "labelled-content"),
        DELETED_ID to memberContent("/synthetic/set/synthetic-deleted.cbz", "deleted-content"),
      )

    private fun memberContent(
      fileName: String?,
      content: String,
    ): MemberContent = MemberContent(fileName, content.encodeToByteArray())

    private fun CatalogBook.isVisibleTo(access: CatalogAccess): Boolean {
      val libraryIds = access.libraryIds
      if (libraryIds != null && book.libraryId !in libraryIds) return false
      return seriesMetadata.sharingLabels.hasNoExcludedLabel(access)
    }

    private fun CatalogSeries.isVisibleTo(access: CatalogAccess): Boolean {
      val libraryIds = access.libraryIds
      if (libraryIds != null && series.libraryId !in libraryIds) return false
      return metadata.sharingLabels.hasNoExcludedLabel(access)
    }

    private fun Set<String>.hasNoExcludedLabel(access: CatalogAccess): Boolean =
      none { label -> label.lowercase() in access.restrictions.labelsExclude }

    private fun syntheticUser(
      roles: Set<UserRole>,
      sharesAllLibraries: Boolean,
      sharedLibraryIds: Set<LibraryId> = emptySet(),
      restrictions: ContentRestrictions = ContentRestrictions(),
    ): User =
      User(
        id = UserId("user-archive"),
        email = "reader@example.invalid",
        passwordHash = "synthetic-hash",
        roles = roles,
        sharedLibraryIds = sharedLibraryIds,
        sharesAllLibraries = sharesAllLibraries,
        restrictions = restrictions,
        createdAtMillis = 1,
      )

    private fun syntheticSeries(
      seriesId: SeriesId,
      libraryId: LibraryId,
      title: String,
      sharingLabels: Set<String> = emptySet(),
    ): CatalogSeries =
      CatalogSeries(
        series =
          Series(
            id = seriesId,
            libraryId = libraryId,
            name = "Synthetic series directory",
            relativePath = "synthetic/${seriesId.value}",
            sourceItemId = "file:///synthetic/${seriesId.value}",
            fileModifiedAtMillis = 2,
            createdAtMillis = 1,
          ),
        metadata =
          SeriesMetadata(
            seriesId = seriesId,
            title = title,
            sharingLabels = sharingLabels,
            createdAtMillis = 1,
          ),
        booksMetadata =
          BookMetadataAggregation(
            createdAtMillis = 1,
            updatedAtMillis = 1,
          ),
        readProgress = null,
      )

    private fun syntheticItem(
      id: BookId,
      numberSort: Float,
      libraryId: LibraryId = SERIES_LIBRARY_ID,
      deletedAtMillis: Long? = null,
      seriesEntry: CatalogSeries = SERIES,
    ): CatalogBook =
      CatalogBook(
        book =
          Book(
            id = id,
            libraryId = libraryId,
            seriesId = seriesEntry.series.id,
            name = "${id.value}.cbz",
            relativePath = "synthetic/set/${id.value}.cbz",
            sourceItemId = "file:///synthetic/set/${id.value}.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 2,
            fileSize = 16,
            number = numberSort.toInt(),
            deletedAtMillis = deletedAtMillis,
            createdAtMillis = 1,
          ),
        seriesTitle = seriesEntry.metadata.title,
        seriesMetadata = seriesEntry.metadata,
        metadata =
          BookMetadata(
            bookId = id,
            title = id.value,
            number = numberSort.toInt().toString(),
            numberSort = numberSort,
            createdAtMillis = 1,
          ),
        media = null,
        readProgress = null,
      )
  }
}
