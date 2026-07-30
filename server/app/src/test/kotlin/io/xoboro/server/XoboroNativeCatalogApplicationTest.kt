package io.xoboro.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import io.xoboro.server.api.XoboroLibraryResponse
import io.xoboro.server.api.XoboroMediaItemResponse
import io.xoboro.server.api.XoboroPageResponse
import io.xoboro.server.api.XoboroSeriesResponse
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class XoboroNativeCatalogApplicationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `serves a persisted catalog through the production native boundary`() {
    val databasePath = tempDirectory.resolve("native-catalog.sqlite")
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
        ),
      )

    testApplication {
      application {
        xoboroModule(runtime)
      }
      val client =
        createClient {
          install(ContentNegotiation) {
            json()
          }
        }
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
      val token = requireNotNull(setup.body<SessionResponse>().accessToken)

      val libraries =
        client
          .get("$XOBORO_API_PREFIX/libraries") {
            bearerAuth(token)
          }.body<List<XoboroLibraryResponse>>()
      assertEquals(listOf("Synthetic library"), libraries.map(XoboroLibraryResponse::name))
      assertEquals("local", libraries.single().source?.provider)

      val series =
        client
          .get("$XOBORO_API_PREFIX/series?query=synthetic&genre=adventure") {
            bearerAuth(token)
          }.body<XoboroPageResponse<XoboroSeriesResponse>>()
      assertEquals(1, series.totalItems)
      assertEquals("Synthetic catalog series", series.items.single().title)

      val media =
        client
          .get("$XOBORO_API_PREFIX/series/series-1/media-items") {
            bearerAuth(token)
          }.body<XoboroPageResponse<XoboroMediaItemResponse>>()
      assertEquals(1, media.totalItems)
      assertEquals("Synthetic issue", media.items.single().title)
      assertEquals("COMIC", media.items.single().type)

      // Reconciliation soft-deletes what disappeared from storage, and empty-trash then destroys
      // it. Both listings default to live entries, and `trashed=true` is the only way to see what
      // a scan removed before agreeing to lose it.
      val liveSeries =
        client
          .get("$XOBORO_API_PREFIX/series") {
            bearerAuth(token)
          }.body<XoboroPageResponse<XoboroSeriesResponse>>()
      assertEquals(listOf("Synthetic catalog series"), liveSeries.items.map { it.title })
      assertTrue(liveSeries.items.none(XoboroSeriesResponse::deleted))

      val trashedSeries =
        client
          .get("$XOBORO_API_PREFIX/series?trashed=true") {
            bearerAuth(token)
          }.body<XoboroPageResponse<XoboroSeriesResponse>>()
      assertEquals(listOf("Trashed catalog series"), trashedSeries.items.map { it.title })
      assertTrue(trashedSeries.items.all(XoboroSeriesResponse::deleted))

      val trashedMedia =
        client
          .get("$XOBORO_API_PREFIX/media-items?trashed=true") {
            bearerAuth(token)
          }.body<XoboroPageResponse<XoboroMediaItemResponse>>()
      assertEquals(listOf("Trashed issue"), trashedMedia.items.map { it.title })
      assertTrue(trashedMedia.items.all(XoboroMediaItemResponse::deleted))

      val invalid =
        client.get("$XOBORO_API_PREFIX/series?trashed=perhaps") {
          bearerAuth(token)
        }
      assertEquals(HttpStatusCode.BadRequest, invalid.status)
    }

    assertFalse(runtime.isReady())
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
          genres = setOf("adventure"),
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
      val trashedSeriesId = SeriesId("series-trashed")
      val trashedBookId = BookId("media-trashed")
      JooqSeriesRepository(database).insert(
        Series(
          id = trashedSeriesId,
          libraryId = libraryId,
          name = "Trashed catalog series",
          relativePath = "Trashed catalog series",
          sourceItemId = "file:///synthetic/trashed",
          fileModifiedAtMillis = 2,
          bookCount = 1,
          createdAtMillis = 1,
          deletedAtMillis = 3,
        ),
      )
      JooqSeriesMetadataRepository(database).upsert(
        SeriesMetadata(
          seriesId = trashedSeriesId,
          title = "Trashed catalog series",
          createdAtMillis = 1,
        ),
      )
      JooqBookRepository(database).insert(
        Book(
          id = trashedBookId,
          libraryId = libraryId,
          seriesId = trashedSeriesId,
          name = "Trashed issue.cbz",
          relativePath = "Trashed catalog series/Trashed issue.cbz",
          sourceItemId = "file:///synthetic/trashed/issue.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 2,
          fileSize = 100,
          number = 1,
          createdAtMillis = 1,
          deletedAtMillis = 3,
        ),
      )
      JooqBookMetadataRepository(database).upsert(
        BookMetadata(
          bookId = trashedBookId,
          title = "Trashed issue",
          number = "1",
          numberSort = 1F,
          createdAtMillis = 1,
        ),
      )
    }
  }
}
