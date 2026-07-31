package io.xoboro.compatibility.komga.api

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
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaNavigationEntry
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReadRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqReadProgressRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class WebPubRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `uses Komga export media types for comic archives`() {
    assertEquals(
      "application/vnd.comicbook+zip",
      "application/zip".toKomgaExportMediaType(),
    )
    assertEquals(
      "application/vnd.comicbook-rar",
      "application/x-rar-compressed; version=5".toKomgaExportMediaType(),
    )
    assertEquals("application/pdf", "application/pdf".toKomgaExportMediaType())
  }

  @Test
  fun `serves epub and pdf WebPub contracts from analyzed media`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("webpub.sqlite"))).use { database ->
      seed(database)
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = AdaptivePasswordHasher(),
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
      val content = SyntheticContent()
      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(JSON)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaWebPubRoutes(catalog, progress, content)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(JSON)
            }
          }
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )

        val epubResponse =
          client.get("/api/v1/books/book-epub/manifest") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, epubResponse.status)
        assertEquals("application/webpub+json", epubResponse.headers[HttpHeaders.ContentType])
        val manifestEntityTag = requireNotNull(epubResponse.headers[HttpHeaders.ETag])
        assertEquals(
          HttpStatusCode.NotModified,
          client.get("/api/v1/books/book-epub/manifest") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.IfNoneMatch, manifestEntityTag)
          }.status,
        )
        val epub = JSON.decodeFromString<WPPublicationDto>(epubResponse.bodyAsText())
        assertTrue(epubResponse.bodyAsText().contains("\"context\":"))
        assertFalse(epubResponse.bodyAsText().contains("\"@context\":"))
        assertFalse(epubResponse.bodyAsText().contains("\"author\":[]"))
        assertEquals(
          "https://readium.org/webpub-manifest/profiles/epub",
          epub.metadata.conformsTo,
        )
        assertEquals("fixed", epub.metadata.rendition["layout"])
        assertTrue(epub.readingOrder.single().href.orEmpty().endsWith("OEBPS/chapter.xhtml"))
        // Every non-spine manifest item is a resource, whichever kind analysis recorded for it.
        // A reader fetches the cover through this list, so classifying the declared cover as its
        // own kind must not drop it out of the manifest: recognising a file is not a reason to
        // stop publishing it. Asserted on the hrefs rather than the count because `base.resources`
        // contributes entries of its own, and a count would pass while naming the wrong files.
        val resourceHrefs = epub.resources.mapNotNull { it.href }
        assertTrue(
          resourceHrefs.any { it.endsWith("OEBPS/styles/main.css") },
          "manifest resources must list the stylesheet asset: $resourceHrefs",
        )
        assertTrue(
          resourceHrefs.any { it.endsWith("OEBPS/images/cover.png") },
          "manifest resources must list the declared cover: $resourceHrefs",
        )
        assertFalse(
          resourceHrefs.any { it.endsWith("OEBPS/chapter.xhtml") },
          "a spine page belongs to readingOrder, not resources: $resourceHrefs",
        )
        assertEquals("Synthetic contents", epub.toc.single().title)
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v1/books/book-epub/manifest/epub") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )

        val positionsResponse =
          client.get("/api/v1/books/book-epub/positions") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, positionsResponse.status)
        val positionsEntityTag = requireNotNull(positionsResponse.headers[HttpHeaders.ETag])
        assertEquals(
          HttpStatusCode.NotModified,
          client.get("/api/v1/books/book-epub/positions") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.IfNoneMatch, positionsEntityTag)
          }.status,
        )
        val positions =
          JSON.decodeFromString<R2PositionsDto>(positionsResponse.bodyAsText())
        assertEquals(1, positions.total)
        assertEquals(1, positions.positions.single().locations?.position)

        val resource =
          client.get("/api/v1/books/book-epub/resource/OEBPS/chapter.xhtml") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, resource.status)
        assertEquals("script-src 'none'; object-src 'none';", resource.headers["Content-Security-Policy"])
        assertEquals("<html>Synthetic resource</html>", resource.bodyAsText())
        val resourceEntityTag = requireNotNull(resource.headers[HttpHeaders.ETag])
        val resourceLastModified = requireNotNull(resource.headers[HttpHeaders.LastModified])
        assertEquals(1, content.openedResources)
        assertEquals(
          HttpStatusCode.NotModified,
          client.get("/api/v1/books/book-epub/resource/OEBPS/chapter.xhtml") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.IfNoneMatch, resourceEntityTag)
          }.status,
        )
        assertEquals(2, content.openedResources)
        assertEquals(
          HttpStatusCode.NotModified,
          client.get("/api/v1/books/book-epub/resource/OEBPS/chapter.xhtml") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.IfModifiedSince, resourceLastModified)
          }.status,
        )
        assertEquals(2, content.openedResources)

        val pdfResponse =
          client.get("/api/v1/books/book-pdf/manifest") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, pdfResponse.status)
        val pdf = JSON.decodeFromString<WPPublicationDto>(pdfResponse.bodyAsText())
        assertEquals(
          "https://readium.org/webpub-manifest/profiles/pdf",
          pdf.metadata.conformsTo,
        )
        assertEquals(2, pdf.readingOrder.size)
        assertTrue(pdf.readingOrder.first().href.orEmpty().endsWith("/pages/1/raw"))
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v1/books/book-pdf/manifest/divina") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.BadRequest,
          client.get("/api/v1/books/book-pdf/manifest/epub") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v1/books/book-epub/positions").status,
        )
      }
    }
  }

  @Test
  fun `accepts an identical WebPub progression resend`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("webpub-progression.sqlite"))).use { database ->
      seed(database)
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-admin" },
          currentTimeMillis = { 10 },
        )
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
            json(JSON)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaWebPubRoutes(
              JooqCatalogReadRepository(database),
              progress,
              SyntheticContent(),
            )
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(JSON)
            }
          }
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )
        val update =
          R2ProgressionDto(
            modified = "2026-01-01T00:00:00Z",
            device = R2DeviceDto(id = "device-1", name = "Synthetic reader"),
            locator =
              R2LocatorDto(
                href = "OEBPS/chapter.xhtml",
                type = "application/xhtml+xml",
                locations = R2LocationDto(position = 1, progression = 0.5F),
              ),
          )

        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/api/v1/books/book-epub/progression") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, "application/json")
            setBody(update)
          }.status,
        )
        // Identical timestamp, different device. A stale write must be rejected, so the stored
        // device below must still be the first one; an identical body could not prove that.
        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/api/v1/books/book-epub/progression") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, "application/json")
            setBody(update.copy(device = R2DeviceDto(id = "device-2", name = "Other reader")))
          }.status,
        )
        val saved =
          client.get("/api/v1/books/book-epub/progression") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, saved.status)
        val savedProgression = JSON.decodeFromString<R2ProgressionDto>(saved.bodyAsText())
        assertEquals(1, savedProgression.locator.locations?.position)
        assertEquals("device-1", savedProgression.device.id)
      }
    }
  }

  private fun seed(database: XoboroDatabase) {
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
        name = "Synthetic publications",
        relativePath = "synthetic",
        sourceItemId = "file:///synthetic",
        fileModifiedAtMillis = 1,
        bookCount = 2,
        createdAtMillis = 1,
      ),
    )
    val books = JooqBookRepository(database)
    books.insert(
      Book(
        id = BookId("book-epub"),
        libraryId = libraryId,
        seriesId = seriesId,
        name = "Synthetic EPUB",
        relativePath = "synthetic/book.epub",
        sourceItemId = "file:///synthetic/book.epub",
        mediaKind = MediaKind.EPUB,
        fileModifiedAtMillis = 1,
        createdAtMillis = 1,
      ),
    )
    books.insert(
      Book(
        id = BookId("book-pdf"),
        libraryId = libraryId,
        seriesId = seriesId,
        name = "Synthetic PDF",
        relativePath = "synthetic/book.pdf",
        sourceItemId = "file:///synthetic/book.pdf",
        mediaKind = MediaKind.PDF,
        fileModifiedAtMillis = 1,
        createdAtMillis = 1,
      ),
    )
    val media = JooqBookMediaRepository(database)
    media.upsert(
      BookMedia(
        bookId = BookId("book-epub"),
        status = MediaStatus.READY,
        mediaType = "application/epub+zip",
        profile = MediaProfile.EPUB,
        pageCount = 1,
        files =
          listOf(
            MediaFile(
              fileName = "OEBPS/chapter.xhtml",
              mediaType = "application/xhtml+xml",
              fileSize = 32,
              kind = MediaFileKind.EPUB_PAGE,
            ),
            MediaFile(
              fileName = "OEBPS/styles/main.css",
              mediaType = "text/css",
              fileSize = 16,
              kind = MediaFileKind.EPUB_ASSET,
            ),
            // The manifest item the OPF declares as the cover image. It is a resource of the
            // publication like any other non-spine item - the kind only records what analysis
            // recognised it as, so cover generation can find it without re-parsing the archive.
            MediaFile(
              fileName = "OEBPS/images/cover.png",
              mediaType = "image/png",
              fileSize = 64,
              kind = MediaFileKind.EPUB_COVER,
            ),
          ),
        epubIsFixedLayout = true,
        toc =
          listOf(
            MediaNavigationEntry(
              title = "Synthetic contents",
              href = "OEBPS/chapter.xhtml",
            ),
          ),
        positions =
          listOf(
            MediaPosition(
              href = "OEBPS/chapter.xhtml",
              mediaType = "application/xhtml+xml",
              progression = 0F,
              position = 1,
              totalProgression = 1F,
            ),
          ),
        createdAtMillis = 1,
      ),
    )
    media.upsert(
      BookMedia(
        bookId = BookId("book-pdf"),
        status = MediaStatus.READY,
        mediaType = "application/pdf",
        profile = MediaProfile.PDF,
        pages =
          listOf(
            BookPage(number = 1, fileName = "1", mediaType = "application/pdf"),
            BookPage(number = 2, fileName = "2", mediaType = "application/pdf"),
          ),
        createdAtMillis = 1,
      ),
    )
  }

  private class SyntheticContent : BookContentAccess {
    var openedResources: Int = 0

    override fun pages(bookId: BookId): List<BookPage>? = null

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream? = null

    override fun openBook(bookId: BookId): MediaContentStream? = null

    override fun openResource(
      bookId: BookId,
      resource: String,
    ): MediaContentStream? {
      if (bookId != BookId("book-epub") || resource != "OEBPS/chapter.xhtml") return null
      openedResources += 1
      val bytes = "<html>Synthetic resource</html>".encodeToByteArray()
      return object : MediaContentStream {
        private var cursor = 0
        override val fileName: String = "chapter.xhtml"
        override val mediaType: String = "application/xhtml+xml"
        override val contentLength: Long = bytes.size.toLong()

        override fun read(
          buffer: ByteArray,
          offset: Int,
          length: Int,
        ): Int {
          if (cursor == bytes.size) return -1
          val count = minOf(length, bytes.size - cursor)
          bytes.copyInto(buffer, offset, cursor, cursor + count)
          cursor += count
          return count
        }

        override fun close() = Unit
      }
    }
  }

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "SyntheticPassword1!"
    val JSON = Json { explicitNulls = false }
  }
}
