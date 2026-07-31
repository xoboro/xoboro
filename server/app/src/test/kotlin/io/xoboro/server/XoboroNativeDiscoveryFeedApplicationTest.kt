package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.persistence.DatabaseConfig
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
 * Ordering is asserted for `new` on both collections rather than status alone: a property the
 * repository merely *accepts* would answer `200` while sorting by the wrong column, which is the
 * same failure one layer further in.
 *
 * `updated`, `recently-read`, `on-deck` and `keep-reading` are asserted to answer, not to order.
 * Their columns need a second write or a read-progress row to become distinguishable, and a status
 * check is what would have caught the defect this test exists for.
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
  fun `orders the new feed by when the row was created, newest first`() {
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
      // survived. Now the newest row is the answer to no other ordering the repository offers - by
      // title it is neither first nor last, and its source timestamp is neither.
      assertEquals(NEWEST_SERIES_TITLE, firstTitle(client, token, "/series/feeds/new"))
      assertEquals(NEWEST_BOOK_TITLE, firstTitle(client, token, "/media-items/feeds/new"))
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
   * The newest row is second alphabetically and its source timestamp is the middle one, so it is the
   * first row for `created` descending and for no other column the repository will sort by.
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

    /**
     * One row of the ordering fixture.
     *
     * `createdAtMillis` and `sourceModifiedAtMillis` are separate values on purpose: setting them
     * from one number would make `created` and `fileLastModified` indistinguishable, and the
     * assertion could not tell which of the two a feed had been resolved to.
     */
    data class OrderingFixture(
      val identifier: String,
      val title: String,
      val createdAtMillis: Long,
      val sourceModifiedAtMillis: Long,
    )

    /**
     * Newest is `beta`, which is second by title and second by source timestamp.
     *
     * So `created DESC` answers `beta`, while `title ASC` answers `alpha`, `title DESC` answers
     * `gamma`, and `fileLastModified DESC` answers `alpha`. One assertion rules out all three.
     */
    val SERIES_FIXTURES =
      listOf(
        OrderingFixture("series-alpha", "Synthetic series alpha", 10, 30),
        OrderingFixture("series-beta", NEWEST_SERIES_TITLE, 30, 20),
        OrderingFixture("series-gamma", "Synthetic series gamma", 20, 10),
      )

    /**
     * The same arrangement for media items, and `number` is assigned in list order so that
     * `numberSort` disagrees too: ascending answers `alpha`, descending `gamma`.
     */
    val BOOK_FIXTURES =
      listOf(
        OrderingFixture("media-alpha", "Synthetic item alpha", 10, 30),
        OrderingFixture("media-beta", NEWEST_BOOK_TITLE, 30, 20),
        OrderingFixture("media-gamma", "Synthetic item gamma", 20, 10),
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
  }
}
