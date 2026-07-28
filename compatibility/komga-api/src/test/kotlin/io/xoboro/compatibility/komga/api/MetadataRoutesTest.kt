package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.MetadataEditingLifecycle
import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.application.CatalogMutationKind
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMetadataRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqMetadataFacetRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class MetadataRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `edits metadata and exposes authorized referential values`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("metadata-api.sqlite"))).use {
        database ->
      seedCatalog(database)
      var userSequence = 0
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-${++userSequence}" },
          currentTimeMillis = { 10 },
        )
      val books = JooqBookRepository(database)
      val series = JooqSeriesRepository(database)
      val bookMetadata = JooqBookMetadataRepository(database)
      val seriesMetadata = JooqSeriesMetadataRepository(database)
      val events = mutableListOf<CatalogMutationEvent>()
      val editing =
        MetadataEditingLifecycle(
          books,
          series,
          bookMetadata,
          seriesMetadata,
          currentTimeMillis = { 20 },
          eventPublisher = events::add,
        )
      val facets = JooqMetadataFacetRepository(database)

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(TEST_JSON)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaMetadataRoutes(editing, facets)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(TEST_JSON)
            }
          }
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )
        users.createUser(
          email = USER_EMAIL,
          rawPassword = USER_PASSWORD,
          roles = emptySet<UserRole>(),
        )

        assertEquals(
          HttpStatusCode.Forbidden,
          client.patch("/api/v1/books/book-1/metadata") {
            basicAuth(USER_EMAIL, USER_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"title":"Denied"}""")
          }.status,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/books/book-1/metadata") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
              """
              {
                "title": "Edited synthetic title",
                "releaseDate": null,
                "authors": [{"name": "Synthetic Author", "role": "writer"}],
                "tags": ["sample"]
              }
              """.trimIndent(),
            )
          }.status,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/series/series-1/metadata") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
              """
              {
                "genres": ["adventure"],
                "publisher": "Synthetic publisher",
                "ageRating": 12
              }
              """.trimIndent(),
            )
          }.status,
        )

        assertEquals("Edited synthetic title", bookMetadata.findByBookIdOrNull(BOOK_ID)?.title)
        assertNull(bookMetadata.findByBookIdOrNull(BOOK_ID)?.releaseDate)
        assertEquals(
          listOf("adventure"),
          client
            .get("/api/v1/genres") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<JsonArray>()
            .map { it.jsonPrimitive.content },
        )
        val authors =
          client
            .get("/api/v2/authors?size=1") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<JsonObject>()
        assertEquals(1, authors["totalElements"]?.jsonPrimitive?.content?.toInt())
        assertEquals(
          "Synthetic Author",
          authors["content"]
            ?.let { it as JsonArray }
            ?.single()
            ?.let { it as JsonObject }
            ?.get("name")
            ?.jsonPrimitive
            ?.content,
        )
        assertEquals(
          listOf(CatalogMutationKind.UPDATED, CatalogMutationKind.UPDATED),
          events.map {
            when (it) {
              is CatalogMutationEvent.Book -> it.kind
              is CatalogMutationEvent.Series -> it.kind
            }
          },
        )
      }
    }
  }

  private fun seedCatalog(database: XoboroDatabase) {
    JooqLibraryRepository(database).insert(
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation("local", "file:///synthetic"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = SERIES_ID,
        libraryId = LIBRARY_ID,
        name = "Synthetic series",
        relativePath = "series",
        sourceItemId = "file:///synthetic/series",
        fileModifiedAtMillis = 1,
        bookCount = 1,
        createdAtMillis = 1,
      ),
    )
    JooqBookRepository(database).insert(
      Book(
        id = BOOK_ID,
        libraryId = LIBRARY_ID,
        seriesId = SERIES_ID,
        name = "Synthetic book",
        relativePath = "series/book.cbz",
        sourceItemId = "file:///synthetic/series/book.cbz",
        mediaKind = MediaKind.COMIC_ARCHIVE,
        fileModifiedAtMillis = 1,
        number = 1,
        createdAtMillis = 1,
      ),
    )
    JooqBookMetadataRepository(database).upsert(
      requireNotNull(JooqBookMetadataRepository(database).findByBookIdOrNull(BOOK_ID)).copy(
        releaseDate = "2026-01-02",
        authors = listOf(Author("Initial Author", "writer")),
      ),
    )
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-admin-password"
    const val USER_EMAIL = "reader@example.invalid"
    const val USER_PASSWORD = "synthetic-reader-password"
    val TEST_JSON = Json { ignoreUnknownKeys = true }
  }
}
