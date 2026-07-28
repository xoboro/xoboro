package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Dimension
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class XoboroNativeDeliveryTest {
  @Test
  fun `page manifest projects indexed page metadata without internal fields or content access`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get(PAGES_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      val json = response.bodyAsText()
      val pages = Json.decodeFromString<List<XoboroMediaPageResponse>>(json)
      assertEquals(
        listOf(
          XoboroMediaPageResponse(1, "image/jpeg", 800, 1_200, 12_345),
          XoboroMediaPageResponse(2, "image/png", 1_000, 1_500, 23_456),
        ),
        pages,
      )
      assertFalse("\"fileName\"" in json)
      assertFalse("KiB" in json)
      assertEquals(0, fixture.content.openPageCallCount)
      assertEquals(0, fixture.content.pagesCallCount)
    }

  @Test
  fun `resource manifest preserves stored paths and excludes general files`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get(RESOURCES_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(
        listOf(
          XoboroResourceResponse(
            path = "OEBPS/text/chapter-1.xhtml",
            mediaType = "application/xhtml+xml",
            sizeBytes = 3_456,
            kind = "EPUB_PAGE",
          ),
        ),
        response.body<List<XoboroResourceResponse>>(),
      )
      assertEquals(0, fixture.content.openPageCallCount)
      assertEquals(0, fixture.content.pagesCallCount)
    }

  @Test
  fun `page bytes include media type validators and close the stream`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get(PAGE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(PAGE_BYTES.decodeToString(), response.bodyAsText())
      assertEquals("image/png", response.headers[HttpHeaders.ContentType])
      val entityTag = assertNotNull(response.headers[HttpHeaders.ETag])
      assertTrue(entityTag.matches(Regex("\"[0-9a-f]{32}\"")))
      assertEquals(
        "max-age=0, must-revalidate, private",
        response.headers[HttpHeaders.CacheControl],
      )
      assertNotNull(response.headers[HttpHeaders.LastModified])
      assertEquals(1, fixture.content.openPageCallCount)
      assertTrue(assertNotNull(fixture.content.lastStream).closed)
    }

  @Test
  fun `matching comma separated weak entity tag returns not modified after opening content`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)
      val initial = client.get(PAGE_PATH) { bearerAuth(fixture.token) }
      val entityTag = assertNotNull(initial.headers[HttpHeaders.ETag])

      val response =
        client.get(PAGE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.IfNoneMatch, "\"stale\", W/$entityTag")
        }

      assertEquals(HttpStatusCode.NotModified, response.status)
      assertEquals("", response.bodyAsText())
      assertEquals(2, fixture.content.openPageCallCount)
      assertTrue(assertNotNull(fixture.content.lastStream).closed)
    }

  @Test
  fun `wildcard entity tag returns not modified`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response =
        client.get(PAGE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.IfNoneMatch, "*")
        }

      assertEquals(HttpStatusCode.NotModified, response.status)
      assertEquals("", response.bodyAsText())
      assertEquals(1, fixture.content.openPageCallCount)
      assertTrue(assertNotNull(fixture.content.lastStream).closed)
    }

  @Test
  fun `stale entity tag returns full page body`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response =
        client.get(PAGE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.IfNoneMatch, "\"00000000000000000000000000000000\"")
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(PAGE_BYTES.decodeToString(), response.bodyAsText())
      assertEquals(1, fixture.content.openPageCallCount)
    }

  @Test
  fun `if modified since at media timestamp returns not modified`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)
      val initial = client.get(PAGE_PATH) { bearerAuth(fixture.token) }
      val lastModified = assertNotNull(initial.headers[HttpHeaders.LastModified])

      val response =
        client.get(PAGE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.IfModifiedSince, lastModified)
        }

      assertEquals(HttpStatusCode.NotModified, response.status)
      assertEquals("", response.bodyAsText())
      assertEquals(2, fixture.content.openPageCallCount)
      assertTrue(assertNotNull(fixture.content.lastStream).closed)
    }

  @Test
  fun `jpeg conversion records requested format and maximum dimension`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response =
        client.get("$PAGE_PATH?format=jpeg&maxDimension=300") {
          bearerAuth(fixture.token)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(
        PageImageRequest(PageImageFormat.JPEG, maximumDimension = 300),
        fixture.content.lastRequest,
      )
    }

  @Test
  fun `source format records raw request for every media kind`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get("$PAGE_PATH?format=source") { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(PageImageRequest(raw = true), fixture.content.lastRequest)
    }

  @Test
  fun `maximum dimension alone is valid and capped at 4096`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get("$PAGE_PATH?maxDimension=5000") { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(
        PageImageRequest(maximumDimension = 4_096),
        fixture.content.lastRequest,
      )
    }

  @Test
  fun `source format with maximum dimension is invalid before content access`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response =
        client.get("$PAGE_PATH?format=source&maxDimension=300") {
          bearerAuth(fixture.token)
        }

      assertInvalidQuery(response.status, response.body<XoboroApiError>())
      assertEquals(0, fixture.content.openPageCallCount)
    }

  @Test
  fun `invalid format and dimensions are rejected before content access`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      for (query in listOf("format=webp", "maxDimension=0", "maxDimension=abc")) {
        val response = client.get("$PAGE_PATH?$query") { bearerAuth(fixture.token) }
        assertInvalidQuery(response.status, response.body())
      }
      assertEquals(0, fixture.content.openPageCallCount)
    }

  @Test
  fun `page numbers outside media count return page not found before content access`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      for (pageNumber in listOf(0, PAGE_COUNT + 1)) {
        val response =
          client.get("$XOBORO_API_PREFIX/media-items/${MEDIA_ID.value}/pages/$pageNumber") {
            bearerAuth(fixture.token)
          }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("page_not_found", response.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.content.openPageCallCount)
    }

  @Test
  fun `non numeric page number returns invalid query`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response =
        client.get("$XOBORO_API_PREFIX/media-items/${MEDIA_ID.value}/pages/not-a-number") {
          bearerAuth(fixture.token)
        }

      assertInvalidQuery(response.status, response.body())
      assertEquals(0, fixture.content.openPageCallCount)
    }

  @Test
  fun `media that is not ready returns conflict before content access`() =
    testApplication {
      val fixture = Fixture.visible(MediaStatus.ERROR)
      installDelivery(fixture)

      val response = client.get(PAGE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("media_not_ready", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.content.openPageCallCount)
    }

  @Test
  fun `page decode failure returns conflict`() =
    testApplication {
      val fixture = Fixture.visible()
      fixture.content.failure = IllegalArgumentException("Synthetic decode failure")
      installDelivery(fixture)

      val response = client.get(PAGE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("page_not_decodable", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.content.openPageCallCount)
    }

  @Test
  fun `indexed page unavailable from content provider returns page not found`() =
    testApplication {
      val fixture = Fixture.visible()
      fixture.content.streamFactory = { null }
      installDelivery(fixture)

      val response = client.get(PAGE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("page_not_found", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.content.openPageCallCount)
    }

  @Test
  fun `restricted user cannot discover media through any delivery endpoint`() =
    testApplication {
      val fixture = Fixture.restricted()
      installDelivery(fixture)

      for (path in listOf(PAGES_PATH, PAGE_PATH, RESOURCES_PATH)) {
        val response = client.get(path) { bearerAuth(fixture.token) }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      }
      assertEquals(3, fixture.catalog.findByIdCallCount)
      assertEquals(0, fixture.content.openPageCallCount)
    }

  @Test
  fun `nonexistent media item is indistinguishable from restricted item`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response =
        client.get("$XOBORO_API_PREFIX/media-items/missing-media/pages/1") {
          bearerAuth(fixture.token)
        }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.catalog.findByIdCallCount)
      assertEquals(0, fixture.content.openPageCallCount)
    }

  @Test
  fun `page streaming role is checked before catalog lookup on every delivery endpoint`() =
    testApplication {
      val fixture = Fixture.roleLess()
      installDelivery(fixture)

      for (path in listOf(PAGES_PATH, PAGE_PATH, RESOURCES_PATH)) {
        val response = client.get(path) { bearerAuth(fixture.token) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("page_streaming_forbidden", response.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.catalog.findByIdCallCount)
      assertEquals(0, fixture.content.openPageCallCount)
    }

  @Test
  fun `delivery requires authentication`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get(PAGE_PATH)

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals(0, fixture.catalog.findByIdCallCount)
      assertEquals(0, fixture.content.openPageCallCount)
    }

  private fun ApplicationTestBuilder.installDelivery(fixture: Fixture) {
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(StatusPages) {
        exception<XoboroInvalidQueryException> { call, cause ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_query", requireNotNull(cause.message)),
          )
        }
      }
      routing {
        xoboroNativeDeliveryRoutes(fixture.catalog, fixture.content)
      }
    }
    client =
      createClient {
        install(ClientContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      }
  }

  private fun assertInvalidQuery(
    status: HttpStatusCode,
    error: XoboroApiError,
  ) {
    assertEquals(HttpStatusCode.BadRequest, status)
    assertEquals("invalid_query", error.code)
  }

  private class Fixture private constructor(
    user: User,
    status: MediaStatus,
  ) {
    private val users = InMemoryUserRepository(user)
    private val book = syntheticBook(status)
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory = { "delivery-token" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val token = sessions.create(user).plainToken
    val catalog = RecordingCatalog(book)
    val content = FakeBookContentAccess()

    companion object {
      fun visible(status: MediaStatus = MediaStatus.READY): Fixture =
        Fixture(
          user =
            syntheticUser(
              roles = setOf(UserRole.PAGE_STREAMING),
              sharesAllLibraries = true,
            ),
          status = status,
        )

      fun restricted(): Fixture =
        Fixture(
          user =
            syntheticUser(
              roles = setOf(UserRole.PAGE_STREAMING),
              sharesAllLibraries = false,
              sharedLibraryIds = setOf(VISIBLE_LIBRARY_ID),
            ),
          status = MediaStatus.READY,
        )

      fun roleLess(): Fixture =
        Fixture(
          user =
            syntheticUser(
              roles = emptySet(),
              sharesAllLibraries = true,
            ),
          status = MediaStatus.READY,
        )
    }
  }

  private class RecordingCatalog(
    private val book: CatalogBook,
  ) : CatalogReadRepository {
    var findByIdCallCount = 0
      private set

    override fun findBooks(
      query: BookCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogBook> = error("Not used")

    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? {
      findByIdCallCount += 1
      val libraryIds = access.libraryIds
      return book.takeIf {
        it.book.id == id && (libraryIds == null || it.book.libraryId in libraryIds)
      }
    }

    override fun findPreviousBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = null

    override fun findNextBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = null

    override fun findSeries(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogSeries> = error("Not used")

    override fun findSeriesByIdOrNull(
      id: SeriesId,
      access: CatalogAccess,
    ): CatalogSeries? = null

    override fun countSeriesByFirstCharacter(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
    ) = emptyList<io.xoboro.core.application.CatalogGroupCount>()
  }

  private class FakeBookContentAccess : BookContentAccess {
    var openPageCallCount = 0
      private set
    var pagesCallCount = 0
      private set
    var lastRequest: PageImageRequest? = null
      private set
    var lastStream: FakeMediaContentStream? = null
      private set
    var failure: IllegalArgumentException? = null
    var streamFactory: () -> FakeMediaContentStream? = {
      FakeMediaContentStream(PAGE_BYTES)
    }

    override fun pages(bookId: BookId): List<BookPage>? {
      pagesCallCount += 1
      error("Delivery manifests must not call BookContentAccess.pages")
    }

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream? {
      openPageCallCount += 1
      lastRequest = request
      failure?.let { throw it }
      return streamFactory().also { lastStream = it }
    }

    override fun openBook(bookId: BookId): MediaContentStream? = null
  }

  private class FakeMediaContentStream(
    private val bytes: ByteArray,
  ) : MediaContentStream {
    private var position = 0
    var closed = false
      private set
    override val mediaType: String = "image/png"
    override val contentLength: Long = bytes.size.toLong()

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (position >= bytes.size) return -1
      val count = minOf(length, bytes.size - position)
      bytes.copyInto(buffer, offset, position, position + count)
      position += count
      return count
    }

    override fun close() {
      closed = true
    }
  }

  private class InMemoryUserRepository(
    user: User,
  ) : UserRepository {
    private var value: User? = user

    override fun count(): Long = if (value == null) 0 else 1

    override fun findByIdOrNull(id: UserId): User? = value?.takeIf { it.id == id }

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      value?.takeIf { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = listOfNotNull(value)

    override fun insert(user: User) {
      if (value != null) throw UserEmailAlreadyExistsException(user.email)
      value = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (value != null) return false
      value = user
      return true
    }

    override fun update(user: User) {
      value = user
    }

    override fun delete(id: UserId) {
      if (value?.id == id) value = null
    }
  }

  companion object {
    private const val PAGE_COUNT = 2
    private val MEDIA_ID = BookId("media-delivery")
    private val HIDDEN_LIBRARY_ID = LibraryId("library-hidden")
    private val VISIBLE_LIBRARY_ID = LibraryId("library-visible")
    private val SERIES_ID = SeriesId("series-delivery")
    private val USER_ID = UserId("user-delivery")
    private val PAGE_BYTES = "synthetic-page-bytes".encodeToByteArray()
    private const val PAGES_PATH = "$XOBORO_API_PREFIX/media-items/media-delivery/pages"
    private const val PAGE_PATH = "$PAGES_PATH/1"
    private const val RESOURCES_PATH =
      "$XOBORO_API_PREFIX/media-items/media-delivery/resources"

    private fun syntheticUser(
      roles: Set<UserRole>,
      sharesAllLibraries: Boolean,
      sharedLibraryIds: Set<LibraryId> = emptySet(),
    ): User =
      User(
        id = USER_ID,
        email = "reader@example.invalid",
        passwordHash = "synthetic-hash",
        roles = roles,
        sharedLibraryIds = sharedLibraryIds,
        sharesAllLibraries = sharesAllLibraries,
        createdAtMillis = 1,
      )

    private fun syntheticBook(status: MediaStatus): CatalogBook {
      val book =
        Book(
          id = MEDIA_ID,
          libraryId = HIDDEN_LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic delivery.epub",
          relativePath = "Synthetic delivery/Synthetic delivery.epub",
          sourceItemId = "file:///synthetic/delivery/book.epub",
          mediaKind = MediaKind.EPUB,
          fileModifiedAtMillis = 2,
          fileSize = 100,
          number = 1,
          createdAtMillis = 1,
        )
      val seriesMetadata =
        SeriesMetadata(
          seriesId = SERIES_ID,
          title = "Synthetic delivery series",
          createdAtMillis = 1,
        )
      return CatalogBook(
        book = book,
        seriesTitle = seriesMetadata.title,
        seriesMetadata = seriesMetadata,
        metadata =
          BookMetadata(
            bookId = MEDIA_ID,
            title = "Synthetic delivery item",
            number = "1",
            numberSort = 1F,
            createdAtMillis = 1,
          ),
        media =
          BookMedia(
            bookId = MEDIA_ID,
            status = status,
            mediaType = "application/epub+zip",
            pages =
              listOf(
                BookPage(
                  number = 1,
                  fileName = "OEBPS/images/page-1.jpg",
                  mediaType = "image/jpeg",
                  fileSize = 12_345,
                  dimension = Dimension(800, 1_200),
                ),
                BookPage(
                  number = 2,
                  fileName = "OEBPS/images/page-2.png",
                  mediaType = "image/png",
                  fileSize = 23_456,
                  dimension = Dimension(1_000, 1_500),
                ),
              ),
            pageCount = PAGE_COUNT,
            files =
              listOf(
                MediaFile(
                  fileName = "OEBPS/text/chapter-1.xhtml",
                  mediaType = "application/xhtml+xml",
                  fileSize = 3_456,
                  kind = MediaFileKind.EPUB_PAGE,
                ),
                MediaFile(
                  fileName = "META-INF/container.xml",
                  mediaType = "application/xml",
                  fileSize = 456,
                  kind = MediaFileKind.GENERAL,
                ),
              ),
            createdAtMillis = 1_735_689_600_000,
            updatedAtMillis = 1_735_689_600_123,
          ),
        readProgress = null,
      )
    }
  }
}
