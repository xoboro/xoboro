package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XoboroMediaProgressRequest
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookMetadataRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/**
 * Asks a real database for every named discovery feed.
 *
 * Every one of the eight answered `500 Unsupported catalog sort property` in production while three
 * tests covering them passed. The feed enum held wire field names - `createdAt`, `updatedAt`,
 * `lastReadAt` - and handed them to the repository, which knows `created`, `lastModified`,
 * `readProgress.readDate`. Nothing noticed, because the route tests run against a fake catalog that
 * records whatever property it is given and the enum's own test asserted the constants against
 * themselves. Only the SQL layer rejects an unknown property, so only a test that reaches it can say
 * a feed works.
 *
 * Ordering is asserted for `new`, `updated` and `on-deck` rather than status alone: a property the
 * repository merely *accepts* would answer `200` while sorting by the wrong column, which is the
 * same failure one layer further in.
 *
 * `recently-read` and `keep-reading` are asserted to answer, not to order. Both resolve to a
 * repository sort property whose ordering is already asserted directly against the database in
 * `JooqCatalogReadRepositoryTest` (`readProgress.readDate` and the `keepReading` special case), so
 * pinning the same column again through an HTTP round trip would duplicate that coverage rather
 * than add to it, and a status check is what would have caught the defect this test exists for.
 */
class XoboroNativeDiscoveryFeedApplicationTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `answers every discovery feed against a real catalog`() {
    val databasePath = temporaryDirectory.resolve("discovery-feeds.sqlite")
    createSyntheticCatalog(databasePath)

    testApplication {
      application { xoboroModule(openRuntime(databasePath)) }
      val client =
        createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
      val token = adminToken(client)

      for (path in FEED_PATHS) {
        val response = client.get("$XOBORO_API_PREFIX$path") { bearerAuth(token) }
        assertEquals(
          HttpStatusCode.OK,
          response.status,
          "$path answered ${response.status}: ${response.bodyAsText()}",
        )
        val body = LENIENT.parseToJsonElement(response.bodyAsText())
        assertTrue(
          body is JsonObject && "items" in body,
          "$path did not answer the page envelope: ${response.bodyAsText()}",
        )
      }
    }
  }

  @Test
  fun `orders the new and updated feeds by their respective timestamps, newest first`() {
    val databasePath = temporaryDirectory.resolve("discovery-feed-order.sqlite")
    createSyntheticCatalog(databasePath)

    testApplication {
      application { xoboroModule(openRuntime(databasePath)) }
      val client =
        createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
      val token = adminToken(client)

      // Three rows, not two, and the newest is the *middle* one alphabetically. With two rows an
      // assertion here proves almost nothing: sorting by title descending picks the same row as
      // sorting by creation time descending, and a mutation replacing the feed's field with `title`
      // survived. Now the newest-created row is the answer to no other ordering the fixture below
      // discriminates against - by title (and so by id) it is neither first nor last, its source
      // timestamp is neither, and neither is its own last-updated time.
      assertEquals(NEWEST_SERIES_TITLE, firstTitle(client, token, "/series/feeds/new"))
      assertEquals(NEWEST_BOOK_TITLE, firstTitle(client, token, "/media-items/feeds/new"))

      // Same fixture, different column: `updated` resolves to each row's own last-updated time,
      // which disagrees with every other ordering above, including `created` - the two feeds
      // pick different rows. A mutation collapsing `updated` onto `createdAt` (they were
      // indistinguishable before this fixture set `updatedAtMillis` explicitly) would make this
      // feed answer the `new` feed's row instead, and fails here.
      assertEquals(
        MOST_RECENTLY_UPDATED_SERIES_TITLE,
        firstTitle(client, token, "/series/feeds/updated"),
      )
      assertEquals(
        MOST_RECENTLY_UPDATED_BOOK_TITLE,
        firstTitle(client, token, "/media-items/feeds/updated"),
      )
    }
  }

  @Test
  fun `orders the on deck feed by when the reader last read anything in that series`() {
    val databasePath = temporaryDirectory.resolve("discovery-feed-on-deck.sqlite")
    createOnDeckCatalog(databasePath)

    testApplication {
      application { xoboroModule(openRuntime(databasePath)) }
      val client =
        createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
      val token = adminToken(client)

      // Marks one book per series completed, through the real progress endpoint rather than a
      // direct write - that endpoint is what production actually calls, and it is what recomputes
      // read_progress_series.last_read_at_ms. The timestamp passed here is what on-deck must
      // order by.
      ON_DECK_FIXTURES.forEach { fixture ->
        val response =
          client.put(
            "$XOBORO_API_PREFIX/media-items/${fixture.seriesIdentifier}-read/progress",
          ) {
            bearerAuth(token)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
              XoboroMediaProgressRequest(
                page = 1,
                locator = JsonObject(mapOf("page" to JsonPrimitive(1))),
                deviceId = "synthetic-device",
                deviceName = "Synthetic device",
                modifiedAtMillis = fixture.lastReadAtMillis,
              ),
            )
          }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
      }

      val response =
        client.get("$XOBORO_API_PREFIX/media-items/feeds/on-deck") { bearerAuth(token) }
      assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
      val items = LENIENT.parseToJsonElement(response.bodyAsText()).jsonObject["items"]!!.jsonArray
      assertEquals(3, items.size, "on-deck returned ${items.size} rows, so order proves nothing")

      // Same discrimination as the new/updated fixture above: the candidate answered first
      // (beta's) is read most recently, but is neither first nor last by id or by title (in
      // either direction), and its `number` is the middle value too. Before the fix, every one of
      // these rows had a null `readProgress.readDate` - they are unread by definition - so
      // ordering by it left the whole feed on the SQL tie-breaker, `b.id ASC`, which would answer
      // `on-deck-alpha-candidate` first (alpha's "banana" title) - not beta's.
      assertEquals(
        listOf(
          MOST_RECENTLY_ON_DECK_TITLE,
          "Synthetic on-deck apple candidate",
          "Synthetic on-deck banana candidate",
        ),
        items.map { it.jsonObject["title"]!!.jsonPrimitive.content },
      )
    }
  }

  private suspend fun firstTitle(
    client: HttpClient,
    token: String,
    path: String,
  ): String {
    val response = client.get("$XOBORO_API_PREFIX$path") { bearerAuth(token) }
    assertEquals(HttpStatusCode.OK, response.status, "$path answered ${response.bodyAsText()}")
    val items = LENIENT.parseToJsonElement(response.bodyAsText()).jsonObject["items"]!!.jsonArray
    assertEquals(3, items.size, "$path returned ${items.size} rows, so order proves nothing")
    return items
      .first()
      .jsonObject["title"]!!
      .jsonPrimitive.content
  }

  /**
   * Three series and three media items, arranged so that creation order agrees with nothing else.
   *
   * The newest row is second alphabetically, its source timestamp is the middle one, and its own
   * last-updated time is neither the newest nor the oldest either - so it is the first row for
   * `created` descending and for no other column the repository will sort by.
   */
  private fun createSyntheticCatalog(databasePath: Path) {
    val libraryId = LibraryId("library-1")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = libraryId,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      val series = JooqSeriesRepository(database)
      val seriesMetadata = JooqSeriesMetadataRepository(database)
      val books = JooqBookRepository(database)
      val bookMetadata = JooqBookMetadataRepository(database)

      SERIES_FIXTURES.forEach { fixture ->
        val seriesId = SeriesId(fixture.identifier)
        series.insert(
          Series(
            id = seriesId,
            libraryId = libraryId,
            name = fixture.title,
            relativePath = fixture.title,
            sourceItemId = "file:///synthetic/${fixture.identifier}",
            fileModifiedAtMillis = fixture.sourceModifiedAtMillis,
            bookCount = 1,
            createdAtMillis = fixture.createdAtMillis,
            updatedAtMillis = fixture.updatedAtMillis,
          ),
        )
        seriesMetadata.upsert(
          SeriesMetadata(
            seriesId = seriesId,
            title = fixture.title,
            createdAtMillis = fixture.createdAtMillis,
          ),
        )
      }

      BOOK_FIXTURES.forEachIndexed { index, fixture ->
        val bookId = BookId(fixture.identifier)
        books.insert(
          Book(
            id = bookId,
            libraryId = libraryId,
            seriesId = SeriesId(SERIES_FIXTURES.first().identifier),
            name = "${fixture.title}.cbz",
            relativePath = "${SERIES_FIXTURES.first().title}/${fixture.title}.cbz",
            sourceItemId = "file:///synthetic/${fixture.identifier}.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = fixture.sourceModifiedAtMillis,
            fileSize = 100,
            number = index + 1,
            createdAtMillis = fixture.createdAtMillis,
            updatedAtMillis = fixture.updatedAtMillis,
          ),
        )
        bookMetadata.upsert(
          BookMetadata(
            bookId = bookId,
            title = fixture.title,
            number = "${index + 1}",
            numberSort = (index + 1).toFloat(),
            createdAtMillis = fixture.createdAtMillis,
          ),
        )
      }
    }
  }

  /**
   * Three series, each with one already-read book and one unread candidate - the one on-deck can
   * return. The already-read books are left with no progress row here; the test itself marks them
   * complete through the real HTTP progress endpoint, because that endpoint - not a direct write -
   * is what recomputes `read_progress_series.last_read_at_ms`, the column on-deck must sort by.
   *
   * The candidate ids, titles, and numbers are arranged like the new/updated fixture above: none
   * of them, ascending or descending, picks the same row that recency does.
   */
  private fun createOnDeckCatalog(databasePath: Path) {
    val libraryId = LibraryId("library-1")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = libraryId,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      val series = JooqSeriesRepository(database)
      val books = JooqBookRepository(database)
      val media = JooqBookMediaRepository(database)

      ON_DECK_FIXTURES.forEach { fixture ->
        val seriesId = SeriesId(fixture.seriesIdentifier)
        series.insert(
          Series(
            id = seriesId,
            libraryId = libraryId,
            name = "Synthetic ${fixture.seriesIdentifier}",
            relativePath = fixture.seriesIdentifier,
            sourceItemId = "file:///synthetic/${fixture.seriesIdentifier}",
            fileModifiedAtMillis = 1,
            bookCount = 2,
            createdAtMillis = 1,
          ),
        )
        val readBookId = BookId("${fixture.seriesIdentifier}-read")
        books.insert(
          Book(
            id = readBookId,
            libraryId = libraryId,
            seriesId = seriesId,
            name = "Synthetic ${fixture.seriesIdentifier} already read",
            relativePath = "${fixture.seriesIdentifier}/read.cbz",
            sourceItemId = "file:///synthetic/${fixture.seriesIdentifier}/read.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 1,
            number = 1,
            createdAtMillis = 1,
          ),
        )
        // The progress endpoint requires the book to have analyzed media before it will accept
        // progress against it.
        media.upsert(
          BookMedia(
            bookId = readBookId,
            status = MediaStatus.READY,
            pageCount = 1,
            createdAtMillis = 1,
          ),
        )
        books.insert(
          Book(
            id = BookId(fixture.candidateIdentifier),
            libraryId = libraryId,
            seriesId = seriesId,
            name = fixture.candidateTitle,
            relativePath = "${fixture.seriesIdentifier}/candidate.cbz",
            sourceItemId = "file:///synthetic/${fixture.seriesIdentifier}/candidate.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 1,
            number = fixture.candidateNumber,
            createdAtMillis = 1,
          ),
        )
      }
    }
  }

  private suspend fun adminToken(client: HttpClient): String {
    val setup =
      client.post("$XOBORO_API_PREFIX/setup") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
          SetupRequest(
            email = "admin@example.invalid",
            password = "synthetic-password",
            transport = SessionTransport.BEARER,
          ),
        )
      }
    assertEquals(HttpStatusCode.Created, setup.status)
    return requireNotNull(setup.body<SessionResponse>().accessToken)
  }

  private fun openRuntime(databasePath: Path): XoboroRuntime =
    XoboroRuntime.open(
      ServerConfig(
        port = 25_703,
        databasePath = databasePath,
        workerCount = 1,
        taskPollMillis = 50,
        taskFailurePollMillis = 50,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
        backupsDirectory = temporaryDirectory.resolve("backups"),
        webDirectory = temporaryDirectory.resolve("no-web-directory"),
      ),
    )

  private companion object {
    val LENIENT = Json { ignoreUnknownKeys = true }

    const val NEWEST_SERIES_TITLE = "Synthetic series beta"
    const val NEWEST_BOOK_TITLE = "Synthetic item beta"
    const val MOST_RECENTLY_UPDATED_SERIES_TITLE = "Synthetic series alpha"
    const val MOST_RECENTLY_UPDATED_BOOK_TITLE = "Synthetic item alpha"
    const val MOST_RECENTLY_ON_DECK_TITLE = "Synthetic on-deck beta candidate"

    /**
     * One row of the ordering fixture.
     *
     * `createdAtMillis`, `sourceModifiedAtMillis`, and `updatedAtMillis` are three separate values
     * on purpose: [Series] and [Book] both default `updatedAtMillis` to `createdAtMillis`, so
     * leaving it unset would make `created` and `updatedAt` (`lastModified`) indistinguishable -
     * exactly the two fields [NEW] and [UPDATED] resolve to - and a mutation swapping one feed's
     * field for the other would survive here undetected.
     */
    data class OrderingFixture(
      val identifier: String,
      val title: String,
      val createdAtMillis: Long,
      val sourceModifiedAtMillis: Long,
      val updatedAtMillis: Long,
    )

    /**
     * Newest is `beta`, which is second by title, second by source timestamp, and neither the
     * newest nor the oldest by its own last-updated time.
     *
     * So `created DESC` answers `beta`, while `title ASC` answers `alpha`, `title DESC` answers
     * `gamma`, `fileLastModified DESC` answers `alpha`, and `lastModified DESC` (what `updatedAt`
     * resolves to) answers `alpha` too. One assertion rules out all four.
     */
    val SERIES_FIXTURES =
      listOf(
        OrderingFixture("series-alpha", "Synthetic series alpha", 10, 30, 50),
        OrderingFixture("series-beta", NEWEST_SERIES_TITLE, 30, 20, 30),
        OrderingFixture("series-gamma", "Synthetic series gamma", 20, 10, 25),
      )

    /**
     * The same arrangement for media items, and `number` is assigned in list order so that
     * `numberSort` disagrees too: ascending answers `alpha`, descending `gamma`.
     */
    val BOOK_FIXTURES =
      listOf(
        OrderingFixture("media-alpha", "Synthetic item alpha", 10, 30, 50),
        OrderingFixture("media-beta", NEWEST_BOOK_TITLE, 30, 20, 30),
        OrderingFixture("media-gamma", "Synthetic item gamma", 20, 10, 25),
      )

    /**
     * Every mounted feed path, listed rather than derived from the enum.
     *
     * Derived from `XoboroNativeDiscoveryFeed.entries` this test would follow the production
     * definition wherever it went, including into a feed silently disappearing. It also cannot use
     * the enum: it is `internal` to `server:api`.
     */
    val FEED_PATHS =
      listOf(
        "/series/feeds/new",
        "/series/feeds/updated",
        "/series/feeds/recently-read",
        "/media-items/feeds/new",
        "/media-items/feeds/updated",
        "/media-items/feeds/recently-read",
        "/media-items/feeds/on-deck",
        "/media-items/feeds/keep-reading",
      )

    /**
     * One series of the on-deck ordering fixture: an already-read book (identified by
     * `$seriesIdentifier-read`) and the one unread candidate on-deck should return for that
     * series.
     */
    data class OnDeckFixture(
      val seriesIdentifier: String,
      val candidateIdentifier: String,
      val candidateTitle: String,
      val candidateNumber: Int,
      val lastReadAtMillis: Long,
    )

    /**
     * Beta is read most recently (300), gamma next (200), alpha least recently (100) - and that
     * is the only ranking that agrees with this order. By id or by title, ascending or
     * descending, the answer is alpha, gamma, or beta first, never this beta-gamma-alpha
     * sequence: id and title both run alpha, beta, gamma one way and gamma, beta, alpha the
     * other; `candidateNumber` runs gamma, alpha, beta one way and beta, alpha, gamma the other.
     */
    val ON_DECK_FIXTURES =
      listOf(
        OnDeckFixture(
          seriesIdentifier = "on-deck-alpha",
          candidateIdentifier = "on-deck-alpha-candidate",
          candidateTitle = "Synthetic on-deck banana candidate",
          candidateNumber = 20,
          lastReadAtMillis = 100,
        ),
        OnDeckFixture(
          seriesIdentifier = "on-deck-beta",
          candidateIdentifier = "on-deck-beta-candidate",
          candidateTitle = MOST_RECENTLY_ON_DECK_TITLE,
          candidateNumber = 30,
          lastReadAtMillis = 300,
        ),
        OnDeckFixture(
          seriesIdentifier = "on-deck-gamma",
          candidateIdentifier = "on-deck-gamma-candidate",
          candidateTitle = "Synthetic on-deck apple candidate",
          candidateNumber = 10,
          lastReadAtMillis = 200,
        ),
      )
  }
}
