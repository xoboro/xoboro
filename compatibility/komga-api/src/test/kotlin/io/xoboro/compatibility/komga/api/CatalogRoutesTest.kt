package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqCatalogReadRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqReadProgressRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class CatalogRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `serves paged catalog detail siblings and series groups`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("catalog-api.sqlite"))).use {
        database ->
      seedCatalog(database)
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "admin-1" },
          currentTimeMillis = { 10 },
        )
      val catalog = JooqCatalogReadRepository(database)
      val progress =
        ReadProgressLifecycle(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          media = JooqBookMediaRepository(database),
          progresses = JooqReadProgressRepository(database),
          currentTimeMillis = { 20 },
        )
      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(KOMGA_JSON)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaCatalogRoutes(catalog)
            komgaReadProgressRoutes(catalog, progress)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(KOMGA_JSON)
            }
          }
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/books").status)

        val books =
          client
            .get("/api/v1/books?size=1&sort=numberSort,desc") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<KomgaBookDto>>()
        assertEquals(2, books.totalElements)
        assertEquals(1, books.content.size)
        assertEquals("book-2", books.content.single().id)
        assertEquals("Synthetic catalog", books.content.single().seriesTitle)
        assertEquals("READY", books.content.single().media.status)

        val previous =
          client
            .get("/api/v1/books/book-2/previous") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaBookDto>()
        assertEquals("book-1", previous.id)
        assertEquals(
          HttpStatusCode.NotFound,
          client.get("/api/v1/books/book-2/next") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )

        val series =
          client
            .get("/api/v1/series/series-1") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaSeriesDto>()
        assertEquals("Synthetic catalog", series.metadata.title)
        assertEquals(2, series.booksCount)

        val seriesBooks =
          client
            .get("/api/v1/series/series-1/books") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<KomgaBookDto>>()
        assertEquals(listOf("book-1", "book-2"), seriesBooks.content.map(KomgaBookDto::id))

        val groups =
          client
            .get("/api/v1/series/alphabetical-groups") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<List<KomgaGroupCountDto>>()
        assertEquals(listOf(KomgaGroupCountDto("S", 1)), groups)

        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/books/book-1/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ReadProgressUpdateDto(completed = true))
          }.status,
        )
        val onDeck =
          client
            .get("/api/v1/books/ondeck") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<KomgaBookDto>>()
        assertEquals(listOf("book-2"), onDeck.content.map(KomgaBookDto::id))
        assertTrue(
          client
            .get("/api/v1/books/book-1") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaBookDto>().readProgress?.completed == true,
        )
        assertEquals(
          HttpStatusCode.BadRequest,
          client.patch("/api/v1/books/book-2/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ReadProgressUpdateDto(page = 99))
          }.status,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.post("/api/v1/series/series-1/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          2,
          client
            .get("/api/v1/series/series-1") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaSeriesDto>().booksReadCount,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.delete("/api/v1/series/series-1/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )

        assertEquals(
          HttpStatusCode.BadRequest,
          client.post("/api/v1/series/list") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(buildJsonObject { put("condition", buildJsonObject { put("type", "all") }) })
          }.status,
        )
      }
    }
  }

  private fun seedCatalog(database: XoboroDatabase) {
    val libraryId = LibraryId("library-1")
    val seriesId = SeriesId("series-1")
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
        name = "Synthetic catalog",
        relativePath = "synthetic-catalog",
        sourceItemId = "file:///synthetic/synthetic-catalog",
        fileModifiedAtMillis = 2,
        bookCount = 2,
        createdAtMillis = 1,
      ),
    )
    val books = JooqBookRepository(database)
    repeat(2) { index ->
      val number = index + 1
      books.insert(
        Book(
          id = BookId("book-$number"),
          libraryId = libraryId,
          seriesId = seriesId,
          name = "Synthetic chapter $number",
          relativePath = "synthetic-catalog/chapter-$number.cbz",
          sourceItemId = "file:///synthetic/synthetic-catalog/chapter-$number.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = number.toLong(),
          fileSize = number * 1_024L,
          number = number,
          createdAtMillis = number.toLong(),
        ),
      )
    }
    val seriesMetadata = JooqSeriesMetadataRepository(database)
    seriesMetadata.upsert(
      seriesMetadata.findBySeriesIdOrNull(seriesId)!!.copy(
        title = "Synthetic catalog",
        titleSort = "Synthetic catalog",
        updatedAtMillis = 2,
      ),
    )
    repeat(2) { index ->
      JooqBookMediaRepository(database).upsert(
        BookMedia(
          bookId = BookId("book-${index + 1}"),
          status = MediaStatus.READY,
          mediaType = "application/zip",
          profile = MediaProfile.DIVINA,
          pageCount = 2,
          createdAtMillis = 1,
        ),
      )
    }
    assertTrue(JooqCatalogReadRepository(database).findBookByIdOrNull(BookId("book-1"), io.xoboro.core.application.CatalogAccess()) != null)
  }

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "SyntheticPassword1!"
    val KOMGA_JSON = Json { explicitNulls = false }
  }
}
