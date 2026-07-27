package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.ProcessedArtwork
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqArtworkRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookMetadataRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReadRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqReadListRepository
import io.xoboro.server.persistence.JooqReadProgressRepository
import io.xoboro.server.persistence.JooqSeriesCollectionRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class ArtworkRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `uploads selects serves and scopes artwork with a page fallback`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("artwork-api.sqlite"))).use {
        database ->
      seedCatalog(database)
      var userSequence = 0
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "user-${++userSequence}" },
          currentTimeMillis = { 10 },
        )
      var artworkSequence = 0
      val artwork =
        ArtworkLifecycle(
          artwork = JooqArtworkRepository(database),
          processor = {
            ProcessedArtwork(
              bytes = it,
              mediaType = "image/jpeg",
              width = 1,
              height = it.size,
            )
          },
          idFactory = { "artwork-${++artworkSequence}" },
          currentTimeMillis = { 20 + artworkSequence.toLong() },
        )
      val catalog =
        JooqCatalogReadRepository(
          database,
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          bookMetadata = JooqBookMetadataRepository(database),
          seriesMetadata = JooqSeriesMetadataRepository(database),
          media = JooqBookMediaRepository(database),
          readProgress = JooqReadProgressRepository(database),
        )
      val collections = JooqSeriesCollectionRepository(database)
      val readLists = JooqReadListRepository(database)

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(TEST_JSON)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaArtworkRoutes(
              artwork,
              catalog,
              SyntheticContentAccess(),
              collections,
              readLists,
            )
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
          client.upload("/api/v1/books/book-1/thumbnails", USER_EMAIL, USER_PASSWORD, byteArrayOf(1))
            .status,
        )
        val uploaded =
          client.upload(
            "/api/v1/books/book-1/thumbnails",
            ADMIN_EMAIL,
            ADMIN_PASSWORD,
            byteArrayOf(1, 2, 3),
          )
        assertEquals(HttpStatusCode.OK, uploaded.status)
        assertEquals("artwork-1", uploaded.body<KomgaArtworkDto>().id)
        assertContentEquals(
          byteArrayOf(1, 2, 3),
          client
            .get("/api/v1/books/book-1/thumbnail") {
              basicAuth(USER_EMAIL, USER_PASSWORD)
            }.body(),
        )
        assertEquals(
          listOf("artwork-1"),
          client
            .get("/api/v1/books/book-1/thumbnails") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<List<KomgaArtworkDto>>()
            .map(KomgaArtworkDto::id),
        )
        assertEquals(
          HttpStatusCode.NotFound,
          client.get("/api/v1/series/series-1/thumbnails/artwork-1") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertContentEquals(
          byteArrayOf(9),
          client
            .get("/api/v1/series/series-1/thumbnail") {
              basicAuth(USER_EMAIL, USER_PASSWORD)
            }.body(),
        )
        assertEquals(
          HttpStatusCode.NotFound,
          client.put("/api/v1/books/book-1/thumbnails/missing/selected") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
      }
    }
  }

  private suspend fun io.ktor.client.HttpClient.upload(
    path: String,
    email: String,
    password: String,
    bytes: ByteArray,
  ): io.ktor.client.statement.HttpResponse {
    val boundary = "synthetic-boundary"
    val prefix =
      "--$boundary\r\n" +
        "Content-Disposition: form-data; name=\"file\"; filename=\"cover.jpg\"\r\n" +
        "Content-Type: image/jpeg\r\n\r\n"
    val suffix = "\r\n--$boundary--\r\n"
    return post(path) {
      basicAuth(email, password)
      header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
      setBody(prefix.encodeToByteArray() + bytes + suffix.encodeToByteArray())
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
    JooqSeriesCollectionRepository(database).insert(
      SeriesCollection(
        id = CollectionId("collection-1"),
        name = "Synthetic collection",
        ordered = true,
        seriesIds = listOf(SERIES_ID),
        createdAtMillis = 1,
      ),
    )
    JooqReadListRepository(database).insert(
      ReadList(
        id = ReadListId("read-list-1"),
        name = "Synthetic read list",
        bookIds = listOf(BOOK_ID),
        createdAtMillis = 1,
      ),
    )
  }

  private class SyntheticContentAccess : BookContentAccess {
    override fun pages(bookId: BookId): List<BookPage> = emptyList()

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream =
      object : MediaContentStream {
        private var consumed = false
        override val fileName: String = "fallback.jpg"
        override val mediaType: String = "image/jpeg"
        override val contentLength: Long = 1

        override fun read(
          buffer: ByteArray,
          offset: Int,
          length: Int,
        ): Int {
          if (consumed) return -1
          buffer[offset] = 9
          consumed = true
          return 1
        }

        override fun skip(byteCount: Long): Long = 0

        override fun close() = Unit
      }

    override fun openBook(bookId: BookId): MediaContentStream? = null
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
