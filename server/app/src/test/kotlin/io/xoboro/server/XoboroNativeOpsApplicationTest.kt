package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XoboroApiError
import io.xoboro.server.api.XoboroBackupResponse
import io.xoboro.server.api.XoboroOperationalMetricsResponse
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMetadataRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Exercises the native backup, metrics, and maintenance surface through the same production
 * wiring path as [XoboroNativeCatalogApplicationTest] — a real SQLite database and a real
 * [XoboroRuntime] rather than the in-memory fakes used by the route-level tests.
 */
class XoboroNativeOpsApplicationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `creates, lists, and deletes a real backup through the production boundary`() {
    val databasePath = tempDirectory.resolve("ops-native.sqlite")
    createSyntheticCatalog(databasePath)
    val backupsDirectory = tempDirectory.resolve("backups")
    val runtime =
      XoboroRuntime.open(
        ServerConfig(
          port = 25_600,
          databasePath = databasePath,
          workerCount = 1,
          taskPollMillis = 10,
          taskFailurePollMillis = 10,
          taskLeaseMillis = 1_000,
          shutdownTimeoutMillis = 2_000,
          backupsDirectory = backupsDirectory,
        ),
      )

    testApplication {
      application { xoboroModule(runtime) }
      val client = jsonClient()
      val token = adminToken(client)

      val created =
        client.post("$XOBORO_API_PREFIX/backups") { bearerAuth(token) }
      assertEquals(HttpStatusCode.Created, created.status)
      val backup = created.body<XoboroBackupResponse>()
      assertTrue(backup.sizeBytes > 0)

      // The client never learns the real filesystem location of the backup file.
      assertTrue(Files.exists(backupsDirectory.resolve("${backup.id}.sqlite")))

      val listed =
        client.get("$XOBORO_API_PREFIX/backups") { bearerAuth(token) }.body<List<XoboroBackupResponse>>()
      assertEquals(listOf(backup), listed)

      val deleted =
        client.delete("$XOBORO_API_PREFIX/backups/${backup.id}") { bearerAuth(token) }
      assertEquals(HttpStatusCode.NoContent, deleted.status)
      assertTrue(!Files.exists(backupsDirectory.resolve("${backup.id}.sqlite")))

      val deletedAgain =
        client.delete("$XOBORO_API_PREFIX/backups/${backup.id}") { bearerAuth(token) }
      assertEquals(HttpStatusCode.NotFound, deletedAgain.status)
      assertEquals("backup_not_found", deletedAgain.body<XoboroApiError>().code)
    }
  }

  @Test
  fun `reports native operational metrics without configuring a Prometheus token`() {
    val databasePath = tempDirectory.resolve("ops-metrics.sqlite")
    createSyntheticCatalog(databasePath)
    val runtime =
      XoboroRuntime.open(
        ServerConfig(
          port = 25_600,
          databasePath = databasePath,
          workerCount = 1,
          taskPollMillis = 10,
          taskFailurePollMillis = 10,
          taskLeaseMillis = 1_000,
          shutdownTimeoutMillis = 2_000,
          backupsDirectory = tempDirectory.resolve("backups"),
        ),
      )

    testApplication {
      // metricsToken is intentionally left null: the native endpoint must not depend on it.
      application { xoboroModule(runtime) }
      val client = jsonClient()
      val token = adminToken(client)

      val response =
        client.get("$XOBORO_API_PREFIX/metrics") { bearerAuth(token) }
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<XoboroOperationalMetricsResponse>()
      assertEquals(true, body.ready)
      assertTrue(body.uptimeSeconds >= 0)
      assertTrue(body.totalRequests >= 1)
      assertEquals(0L, body.taskQueue.pending)
    }
  }

  @Test
  fun `queues native media item maintenance through the production boundary`() {
    // The media-item and series cases are kept in separate runtimes below for a reason that no
    // longer holds: the series route used to rebuild the aggregation as part of reading it, which
    // could race the analyze task this route starts and fail with SQLITE_BUSY_SNAPSHOT. That race
    // is gone twice over - the sweep claims its work write-first (see
    // JooqBookMetadataAggregationRepositoryConcurrencyTest) and a series read no longer sweeps at
    // all. The split is left as-is rather than chained back together to keep each change scoped.
    val databasePath = tempDirectory.resolve("ops-maintenance.sqlite")
    createSyntheticCatalog(databasePath)
    val runtime =
      XoboroRuntime.open(
        ServerConfig(
          port = 25_600,
          databasePath = databasePath,
          workerCount = 1,
          taskPollMillis = 10,
          taskFailurePollMillis = 10,
          taskLeaseMillis = 1_000,
          shutdownTimeoutMillis = 2_000,
          backupsDirectory = tempDirectory.resolve("backups"),
        ),
      )

    testApplication {
      application { xoboroModule(runtime) }
      val client = jsonClient()
      val token = adminToken(client)

      val analyzed =
        client.post("$XOBORO_API_PREFIX/media-items/media-1/analyze") { bearerAuth(token) }
      assertEquals(HttpStatusCode.Accepted, analyzed.status)

      val missing =
        client.post("$XOBORO_API_PREFIX/media-items/missing-media-item/analyze") {
          bearerAuth(token)
        }
      assertEquals(HttpStatusCode.NotFound, missing.status)
      assertEquals("media_item_not_found", missing.body<XoboroApiError>().code)
    }
  }

  @Test
  fun `queues native series maintenance through the production boundary`() {
    val databasePath = tempDirectory.resolve("ops-series-maintenance.sqlite")
    createSyntheticCatalog(databasePath)
    val runtime =
      XoboroRuntime.open(
        ServerConfig(
          port = 25_600,
          databasePath = databasePath,
          workerCount = 1,
          taskPollMillis = 10,
          taskFailurePollMillis = 10,
          taskLeaseMillis = 1_000,
          shutdownTimeoutMillis = 2_000,
          backupsDirectory = tempDirectory.resolve("backups"),
        ),
      )

    testApplication {
      application { xoboroModule(runtime) }
      val client = jsonClient()
      val token = adminToken(client)

      val seriesAnalyzed =
        client.post("$XOBORO_API_PREFIX/series/series-1/metadata-refresh") { bearerAuth(token) }
      assertEquals(HttpStatusCode.Accepted, seriesAnalyzed.status)

      val seriesMissing =
        client.post("$XOBORO_API_PREFIX/series/missing-series/analyze") { bearerAuth(token) }
      assertEquals(HttpStatusCode.NotFound, seriesMissing.status)
      assertEquals("series_not_found", seriesMissing.body<XoboroApiError>().code)
    }
  }

  private fun ApplicationTestBuilder.jsonClient(): HttpClient =
    createClient {
      install(ContentNegotiation) {
        json()
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

  private fun createSyntheticCatalog(databasePath: Path) {
    val libraryId = LibraryId("library-1")
    val seriesId = SeriesId("series-1")
    val bookId = BookId("media-1")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = libraryId,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = seriesId,
          libraryId = libraryId,
          name = "Synthetic catalog series",
          relativePath = "Synthetic catalog series",
          sourceItemId = "file:///synthetic/series",
          fileModifiedAtMillis = 2,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      )
      JooqSeriesMetadataRepository(database).upsert(
        SeriesMetadata(
          seriesId = seriesId,
          title = "Synthetic catalog series",
          createdAtMillis = 1,
        ),
      )
      JooqBookRepository(database).insert(
        Book(
          id = bookId,
          libraryId = libraryId,
          seriesId = seriesId,
          name = "Synthetic issue.cbz",
          relativePath = "Synthetic catalog series/Synthetic issue.cbz",
          sourceItemId = "file:///synthetic/series/issue.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 2,
          fileSize = 100,
          number = 1,
          createdAtMillis = 1,
        ),
      )
      JooqBookMetadataRepository(database).upsert(
        BookMetadata(
          bookId = bookId,
          title = "Synthetic issue",
          number = "1",
          numberSort = 1F,
          createdAtMillis = 1,
        ),
      )
    }
  }
}
