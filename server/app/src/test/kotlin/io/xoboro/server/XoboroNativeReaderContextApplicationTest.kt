package io.xoboro.server

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
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
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
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

class XoboroNativeReaderContextApplicationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `boundary reader context omits unavailable adjacent ids in production serialization`() {
    val databasePath = tempDirectory.resolve("reader-context.sqlite")
    createSingleItemCatalog(databasePath)
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
      application { xoboroModule(runtime) }
      val client = createClient { install(ContentNegotiation) { json() } }
      val setup =
        client.post("$XOBORO_API_PREFIX/setup") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            SetupRequest(
              email = "reader-context@example.invalid",
              password = "synthetic-password",
              transport = SessionTransport.BEARER,
            ),
          )
        }
      val token = requireNotNull(Json.decodeFromString<SessionResponse>(setup.bodyAsText()).accessToken)

      val response =
        client.get("$XOBORO_API_PREFIX/media-items/media-1/reader-context") {
          bearerAuth(token)
        }

      assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
      val context = Json.parseToJsonElement(response.bodyAsText()).jsonObject
      assertEquals(setOf("item", "pages", "positions"), context.keys)
      assertEquals(1, context.getValue("pages").jsonArray.size)
      assertEquals(0, context.getValue("positions").jsonArray.size)
    }

    assertFalse(runtime.isReady())
  }

  private fun createSingleItemCatalog(databasePath: Path) {
    val libraryId = LibraryId("library-1")
    val seriesId = SeriesId("series-1")
    val bookId = BookId("media-1")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = libraryId,
          name = "Synthetic reader-context library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = seriesId,
          libraryId = libraryId,
          name = "Synthetic reader-context series",
          relativePath = "Synthetic reader-context series",
          sourceItemId = "file:///synthetic/series",
          fileModifiedAtMillis = 2,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      )
      JooqSeriesMetadataRepository(database).upsert(
        SeriesMetadata(
          seriesId = seriesId,
          title = "Synthetic reader-context series",
          createdAtMillis = 1,
        ),
      )
      JooqBookRepository(database).insert(
        Book(
          id = bookId,
          libraryId = libraryId,
          seriesId = seriesId,
          name = "Synthetic reader-context issue.cbz",
          relativePath = "Synthetic reader-context series/issue.cbz",
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
          title = "Synthetic reader-context issue",
          number = "1",
          numberSort = 1F,
          createdAtMillis = 1,
        ),
      )
      JooqBookMediaRepository(database).upsert(
        BookMedia(
          bookId = bookId,
          status = MediaStatus.READY,
          mediaType = "application/zip",
          profile = MediaProfile.DIVINA,
          pages = listOf(BookPage(1, "001.jpg", "image/jpeg")),
          pageCount = 1,
          createdAtMillis = 1,
        ),
      )
    }
  }
}
