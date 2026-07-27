package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqArtworkRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReadRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqMetadataFacetRepository
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class OpdsRoutesTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `serves authenticated OPDS catalogs media and progression aliases`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("opds.sqlite"))).use { database ->
      seed(database)
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "user-admin" },
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
      val artwork =
        ArtworkLifecycle(
          artwork = JooqArtworkRepository(database),
          processor = {
            ProcessedArtwork(
              bytes = it,
              mediaType = "image/jpeg",
              width = 1,
              height = 1,
            )
          },
          idFactory = { "artwork-1" },
          currentTimeMillis = { 30 },
        )
      val content = SyntheticContent()

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(JSON)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaOpdsRoutes(
              catalog = catalog,
              libraries = JooqLibraryRepository(database),
              collections = JooqSeriesCollectionRepository(database),
              readLists = JooqReadListRepository(database),
              artwork = artwork,
              content = content,
              progress = progress,
              facets = JooqMetadataFacetRepository(database),
            )
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(JSON)
            }
          }

        val authentication = client.get("/opds/v2/auth")
        assertEquals(HttpStatusCode.OK, authentication.status)
        assertTrue(
          authentication.headers[HttpHeaders.ContentType]
            .orEmpty()
            .startsWith("application/opds-authentication+json"),
        )
        assertContains(authentication.bodyAsText(), "\"title\":\"Komga\"")
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/opds/v2/catalog").status,
        )
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )

        val atom = client.authenticatedGet("/opds/v1.2/catalog")
        assertEquals(HttpStatusCode.OK, atom.status)
        assertTrue(
          atom.headers[HttpHeaders.ContentType]
            .orEmpty()
            .startsWith("application/atom+xml"),
        )
        assertContains(atom.bodyAsText(), "<feed")

        val v2 = client.authenticatedGet("/opds/v2/catalog")
        assertEquals(HttpStatusCode.OK, v2.status)
        assertTrue(
          v2.headers[HttpHeaders.ContentType]
            .orEmpty()
            .startsWith("application/opds+json"),
        )
        val catalogFeed = JSON.decodeFromString<OpdsFeedDto>(v2.bodyAsText())
        assertEquals("All libraries - Recommended", catalogFeed.metadata.title)
        assertEquals("self", catalogFeed.links.first().rel)
        assertEquals(null, catalogFeed.links.first().type)
        assertEquals("Home", catalogFeed.links.first { it.rel == "start" }.title)
        assertEquals(
          listOf("Libraries", "Latest Books", "Latest Series"),
          catalogFeed.groups.map { it.metadata.title },
        )
        catalogFeed.groups.filter { it.metadata.title.startsWith("Latest") }.forEach { group ->
          assertEquals(5, group.metadata.itemsPerPage)
          assertEquals(1, group.metadata.currentPage)
          assertEquals(1, group.metadata.numberOfItems)
          assertEquals("self", group.links.single().rel)
        }
        val catalogPublication =
          catalogFeed.groups
            .single { it.metadata.title == "Latest Books" }
            .publications
            .single()
        assertEquals(null, catalogPublication.metadata.conformsTo)
        assertTrue(catalogPublication.readingOrder.isEmpty())
        assertTrue(catalogPublication.resources.isEmpty())
        assertEquals(
          "application/opds+json",
          catalogPublication.metadata.belongsTo?.series?.single()?.links?.single()?.type,
        )
        assertEquals(null, catalogPublication.images.single().rel)
        assertEquals(
          "application/opds-authentication+json",
          catalogPublication.images.single().properties["authenticate"]?.get("type"),
        )
        assertContains(
          catalogPublication.links.single { it.rel == "http://opds-spec.org/acquisition" }.href.orEmpty(),
          "/opds/v2/books/book-1/file",
        )

        val browseFeed =
          JSON.decodeFromString<OpdsFeedDto>(
            client.authenticatedGet("/opds/v2/libraries/browse?page=0&size=20").bodyAsText(),
          )
        assertEquals("All libraries", browseFeed.metadata.title)
        assertEquals(20, browseFeed.metadata.itemsPerPage)
        assertEquals(1, browseFeed.metadata.currentPage)
        assertEquals(1, browseFeed.metadata.numberOfItems)
        assertEquals(
          listOf("Recommended", "Browse", "Collections", "Read lists"),
          browseFeed.navigation.mapNotNull(WPLinkDto::title),
        )
        assertEquals(
          listOf("Series", "Publisher"),
          browseFeed.groups.map { it.metadata.title },
        )
        assertEquals(
          "Synthetic publisher",
          browseFeed.groups.single { it.metadata.title == "Publisher" }.navigation.single().title,
        )

        val searchFeed =
          JSON.decodeFromString<OpdsFeedDto>(
            client.authenticatedGet("/opds/v2/search?query=series&page=9&size=1").bodyAsText(),
          )
        assertEquals("Search results", searchFeed.metadata.title)
        assertTrue(searchFeed.metadata.modified != null)
        assertTrue(searchFeed.links.none { it.rel == "self" })
        assertEquals(listOf("Series"), searchFeed.groups.map { it.metadata.title })
        assertEquals("Synthetic series", searchFeed.groups.single().navigation.single().title)

        catalogPaths.forEach { path ->
          val response = client.authenticatedGet(path)
          assertTrue(
            response.status.value in 200..299,
            "$path returned ${response.status}: ${response.bodyAsText()}",
          )
        }

        incompatibleManifestPaths.forEach { path ->
          assertEquals(
            HttpStatusCode.BadRequest,
            client.authenticatedGet(path).status,
            path,
          )
        }

        val v1Page = client.authenticatedGet("/opds/v1.2/books/book-1/pages/0")
        assertEquals(HttpStatusCode.OK, v1Page.status)
        assertEquals(SyntheticContent.FIRST_BYTES.toList(), v1Page.body<ByteArray>().toList())
        assertEquals(1, content.lastOpenedPage)

        val openedBeforeV2 = content.openedPages
        val page = client.authenticatedGet("/opds/v2/books/book-1/pages/1")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("image/jpeg", page.headers[HttpHeaders.ContentType])
        assertEquals(SyntheticContent.FIRST_BYTES.size.toLong(), page.headers[HttpHeaders.ContentLength]?.toLong())
        assertTrue(page.headers[HttpHeaders.ContentDisposition].orEmpty().contains("Synthetic_chapter-1.jpg"))
        val pageEntityTag = requireNotNull(page.headers[HttpHeaders.ETag])
        val pageLastModified = requireNotNull(page.headers[HttpHeaders.LastModified])
        assertEquals(openedBeforeV2 + 1, content.openedPages)
        assertEquals(
          HttpStatusCode.NotModified,
          client.get("/opds/v2/books/book-1/pages/1") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.IfNoneMatch, pageEntityTag)
          }.status,
        )
        assertEquals(openedBeforeV2 + 2, content.openedPages)
        assertEquals(
          HttpStatusCode.NotModified,
          client.get("/opds/v2/books/book-1/pages/1") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.IfModifiedSince, pageLastModified)
          }.status,
        )
        assertEquals(openedBeforeV2 + 2, content.openedPages)

        val thumbnail = client.authenticatedGet("/opds/v1.2/books/book-1/thumbnail")
        assertEquals(HttpStatusCode.OK, thumbnail.status)
        val thumbnailEntityTag = requireNotNull(thumbnail.headers[HttpHeaders.ETag])
        assertEquals(
          HttpStatusCode.NotModified,
          client.get("/opds/v1.2/books/book-1/thumbnail") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.IfNoneMatch, thumbnailEntityTag)
          }.status,
        )

        val update =
          client.put("/opds/v2/books/book-1/progression") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, "application/json")
            setBody(
              R2ProgressionDto(
                modified = "2026-01-01T00:00:00Z",
                device = R2DeviceDto(id = "device-1", name = "Synthetic reader"),
                locator =
                  R2LocatorDto(
                    href = "/opds/v2/books/book-1/pages/1",
                    type = "image/jpeg",
                    locations = R2LocationDto(position = 1, progression = 0.5F),
                  ),
              ),
            )
          }
        assertEquals(HttpStatusCode.NoContent, update.status)
        val saved = client.authenticatedGet("/opds/v2/books/book-1/progression")
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals(1, JSON.decodeFromString<R2ProgressionDto>(saved.bodyAsText()).locator.locations?.position)

        val keepReading =
          JSON.decodeFromString<OpdsFeedDto>(
            client.authenticatedGet("/opds/v2/libraries/keep-reading?size=1&page=0").bodyAsText(),
          )
        assertEquals(1, keepReading.metadata.numberOfItems)
        assertEquals(1, keepReading.metadata.itemsPerPage)
        assertEquals("Synthetic chapter", keepReading.publications.single().metadata.title)

        val acquisition = client.authenticatedGet("/opds/v2/books/book-1/file")
        assertEquals(HttpStatusCode.OK, acquisition.status)
        assertEquals("application/zip", acquisition.headers[HttpHeaders.ContentType])
        assertEquals(SyntheticContent.ARCHIVE_BYTES.toList(), acquisition.body<ByteArray>().toList())
      }
    }
  }

  private suspend fun io.ktor.client.HttpClient.authenticatedGet(path: String) =
    get(path) {
      basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
    }

  private fun seed(database: XoboroDatabase) {
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
    JooqSeriesMetadataRepository(database).upsert(
      SeriesMetadata(
        seriesId = SERIES_ID,
        title = "Synthetic series",
        publisher = "Synthetic publisher",
        createdAtMillis = 1,
      ),
    )
    JooqBookRepository(database).insert(
      Book(
        id = BOOK_ID,
        libraryId = LIBRARY_ID,
        seriesId = SERIES_ID,
        name = "Synthetic chapter",
        relativePath = "series/chapter.cbz",
        sourceItemId = "file:///synthetic/series/chapter.cbz",
        mediaKind = MediaKind.COMIC_ARCHIVE,
        fileModifiedAtMillis = 1,
        number = 1,
        createdAtMillis = 1,
      ),
    )
    JooqBookMediaRepository(database).upsert(
      BookMedia(
        bookId = BOOK_ID,
        status = MediaStatus.READY,
        mediaType = "application/zip",
        profile = MediaProfile.DIVINA,
        pages =
          listOf(
            BookPage(
              number = 1,
              fileName = "001.jpg",
              mediaType = "image/jpeg",
              fileSize = SyntheticContent.FIRST_BYTES.size.toLong(),
            ),
            BookPage(
              number = 2,
              fileName = "002.jpg",
              mediaType = "image/jpeg",
              fileSize = SyntheticContent.SECOND_BYTES.size.toLong(),
            ),
          ),
        pageCount = 2,
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

  private class SyntheticContent : BookContentAccess {
    var openedPages: Int = 0
    var lastOpenedPage: Int? = null

    override fun pages(bookId: BookId): List<BookPage> =
      if (bookId == BOOK_ID) {
        listOf(
          BookPage(1, "001.jpg", "image/jpeg", fileSize = FIRST_BYTES.size.toLong()),
          BookPage(2, "002.jpg", "image/jpeg", fileSize = SECOND_BYTES.size.toLong()),
        )
      } else {
        emptyList()
      }

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream? {
      if (bookId != BOOK_ID || pageNumber !in 1..2) return null
      openedPages += 1
      lastOpenedPage = pageNumber
      return if (pageNumber == 1) {
        ByteStream(FIRST_BYTES, "001.jpg")
      } else {
        ByteStream(SECOND_BYTES, "002.jpg")
      }
    }

    override fun openBook(bookId: BookId): MediaContentStream? =
      if (bookId == BOOK_ID) {
        ByteStream(ARCHIVE_BYTES, "chapter.cbz", "application/zip")
      } else {
        null
      }

    companion object {
      val FIRST_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xD9.toByte())
      val SECOND_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 2, 0xD9.toByte())
      val ARCHIVE_BYTES = byteArrayOf(0x50, 0x4B, 3, 4)
    }
  }

  private class ByteStream(
    private val bytes: ByteArray,
    override val fileName: String,
    override val mediaType: String = "image/jpeg",
  ) : MediaContentStream {
    private var cursor = 0
    override val contentLength: Long = bytes.size.toLong()

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (cursor >= bytes.size) return -1
      val count = minOf(length, bytes.size - cursor)
      bytes.copyInto(buffer, offset, cursor, cursor + count)
      cursor += count
      return count
    }

    override fun close() = Unit
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "SyntheticPassword1!"
    val JSON = Json { ignoreUnknownKeys = true }
    val catalogPaths =
      listOf(
        "/opds/v1.2/search",
        "/opds/v1.2/ondeck",
        "/opds/v1.2/keep-reading",
        "/opds/v1.2/series",
        "/opds/v1.2/series/latest",
        "/opds/v1.2/books/latest",
        "/opds/v1.2/libraries",
        "/opds/v1.2/collections",
        "/opds/v1.2/readlists",
        "/opds/v1.2/publishers",
        "/opds/v1.2/series/series-1",
        "/opds/v1.2/libraries/library-1",
        "/opds/v1.2/collections/collection-1",
        "/opds/v1.2/readlists/read-list-1",
        "/opds/v1.2/books/book-1/thumbnail/small",
        "/opds/v1.2/books/book-1/pages/1",
        "/opds/v2/libraries",
        "/opds/v2/libraries/library-1",
        "/opds/v2/libraries/keep-reading",
        "/opds/v2/libraries/library-1/keep-reading",
        "/opds/v2/libraries/on-deck",
        "/opds/v2/libraries/library-1/on-deck",
        "/opds/v2/libraries/books/latest",
        "/opds/v2/libraries/library-1/books/latest",
        "/opds/v2/libraries/series/latest",
        "/opds/v2/libraries/library-1/series/latest",
        "/opds/v2/libraries/browse",
        "/opds/v2/libraries/library-1/browse",
        "/opds/v2/libraries/collections",
        "/opds/v2/libraries/library-1/collections",
        "/opds/v2/collections/collection-1",
        "/opds/v2/libraries/readlists",
        "/opds/v2/libraries/library-1/readlists",
        "/opds/v2/readlists/read-list-1",
        "/opds/v2/series/series-1",
        "/opds/v2/search?query=Synthetic",
        "/opds/v2/books/book-1/thumbnail",
        "/opds/v2/books/book-1/manifest",
        "/opds/v2/books/book-1/manifest/divina",
      )
    val incompatibleManifestPaths =
      listOf(
        "/opds/v2/books/book-1/manifest/epub",
        "/opds/v2/books/book-1/manifest/pdf",
      )
  }
}
