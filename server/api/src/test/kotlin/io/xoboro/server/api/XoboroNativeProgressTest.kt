package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.ReadProgressRepository
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SeriesReadProgress
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class XoboroNativeProgressTest {
  @Test
  fun `accepts trusted cookie mutation and stores progress`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          cookie(XOBORO_SESSION_COOKIE, fixture.token)
          trustedBrowserMutation()
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(1, fixture.progresses.writeAttempts)
      assertEquals(4, fixture.storedProgress()?.page)
    }

  @Test
  fun `accepts cookie mutation with same-origin fetch metadata only`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          cookie(XOBORO_SESSION_COOKIE, fixture.token)
          header("Sec-Fetch-Site", "same-origin")
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(1, fixture.progresses.writeAttempts)
    }

  @Test
  fun `rejects cross-site cookie mutation before storage`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          cookie(XOBORO_SESSION_COOKIE, fixture.token)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals(
        CrossSiteRequestRejectedException.CODE,
        response.body<XoboroApiError>().code,
      )
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  @Test
  fun `rejects cookie mutation without browser provenance before storage`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          cookie(XOBORO_SESSION_COOKIE, fixture.token)
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals(
        CrossSiteRequestRejectedException.CODE,
        response.body<XoboroApiError>().code,
      )
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  @Test
  fun `rejects cookie mutation from a different origin port before storage`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          cookie(XOBORO_SESSION_COOKIE, fixture.token)
          header(HttpHeaders.Origin, "http://localhost:81")
          header("Sec-Fetch-Site", "same-origin")
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals(
        CrossSiteRequestRejectedException.CODE,
        response.body<XoboroApiError>().code,
      )
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  @Test
  fun `accepts bearer mutation without CSRF headers`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(1, fixture.progresses.writeAttempts)
    }

  @Test
  fun `accepts bearer mutation with cross-site origin`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(1, fixture.progresses.writeAttempts)
    }

  @Test
  fun `hides media item outside restricted user library grants`() =
    testApplication {
      val fixture = Fixture.restricted()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  @Test
  fun `returns not found for nonexistent media item`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put("$XOBORO_API_PREFIX/media-items/missing-media/progress") {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  @Test
  fun `requires authentication`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  @Test
  fun `authorizes media item before validating request body`() =
    testApplication {
      val fixture = Fixture.restricted()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody("""{"page":"invalid"}""")
        }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  @Test
  fun `applies newer progress and returns stored device locator and timestamps`() =
    testApplication {
      val initial = syntheticProgress(page = 2, readAtMillis = 100)
      val fixture = Fixture.administrator(initial)
      installProgress(fixture)
      val request = validRequest(page = 7, modifiedAtMillis = 200)

      val response =
        client.put(PROGRESS_PATH) {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody(request)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<XoboroMediaProgressResponse>()
      assertEquals(7, body.page)
      assertEquals(false, body.completed)
      assertEquals(200, body.readAtMillis)
      assertEquals(300, body.updatedAtMillis)
      assertEquals("device-1", body.deviceId)
      assertEquals("Synthetic reader", body.deviceName)
      assertEquals(request.locator, body.locator)
      assertEquals(body, fixture.storedProgress()?.toNativeProgressResponse())
    }

  @Test
  fun `applies a page-only write from a reader that has no locator`() =
    testApplication {
      // A comic has no Readium locator - there is no spine and no `href` to point at - so the
      // comic reader sends a page and nothing else. `locator` used to be a required field, which
      // made every progress write from that reader answer `400`, and the whole existing suite
      // missed it because every case here is built from one `validRequest()` that always supplies
      // one. `ReadProgress.locatorJson` has always been `String?` with the invariant "null or
      // non-blank", so an absent locator is what the domain already models; it was only this
      // route's request type that refused to express it.
      //
      // Written as raw JSON rather than through the DTO on purpose: constructing
      // `XoboroMediaProgressRequest(locator = null)` would prove that Kotlin accepts a null, not
      // that a client omitting the field is accepted. The wire is what the reader speaks.
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody(
            """
            {"page":6,"deviceId":"device-1","deviceName":"Synthetic reader","modifiedAtMillis":200}
            """.trimIndent(),
          )
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<XoboroMediaProgressResponse>()
      assertEquals(6, body.page)
      // Absent, not an empty object: storing `{}` would say a locator was recorded and had no
      // fields, which is a different claim from "this reader has no locator".
      assertNull(body.locator)
      assertNull(fixture.storedProgress()?.locatorJson)
      // The conflict contract still applies to this reader. Routing a page-only write to the
      // page-based `updateBook` instead would have been the smaller change and would have silently
      // dropped `modifiedAtMillis` ordering, so a second device could rewind a comic reader's place
      // while an EPUB reader stayed protected.
      val stale =
        client.put(PROGRESS_PATH) {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody(
            """
            {"page":9,"deviceId":"device-2","deviceName":"Other reader","modifiedAtMillis":200}
            """.trimIndent(),
          )
        }
      assertEquals(HttpStatusCode.Conflict, stale.status)
      assertEquals("stale_progress", stale.body<XoboroApiError>().code)
      assertEquals(6, fixture.storedProgress()?.page)
    }

  @Test
  fun `rejects equal timestamp as stale without changing stored progress`() =
    testApplication {
      val initial = syntheticProgress(page = 3, readAtMillis = 200)
      val fixture = Fixture.administrator(initial)
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody(validRequest(page = 8, modifiedAtMillis = 200))
        }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("stale_progress", response.body<XoboroApiError>().code)
      assertEquals(initial, fixture.storedProgress())
    }

  @Test
  fun `rejects older timestamp as stale`() =
    testApplication {
      val initial = syntheticProgress(page = 3, readAtMillis = 200)
      val fixture = Fixture.administrator(initial)
      installProgress(fixture)

      val response =
        client.put(PROGRESS_PATH) {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody(validRequest(page = 8, modifiedAtMillis = 199))
        }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("stale_progress", response.body<XoboroApiError>().code)
      assertEquals(initial, fixture.storedProgress())
    }

  @Test
  fun `rejects pages outside media range`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      for (page in listOf(0, PAGE_COUNT + 1)) {
        val response =
          client.put(PROGRESS_PATH) {
            bearerAuth(fixture.token)
            contentType(ContentType.Application.Json)
            setBody(validRequest(page = page))
          }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", response.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  @Test
  fun `maps malformed and incomplete JSON to invalid request`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      for (body in listOf("{not-json", """{"page":4}""")) {
        val response =
          client.put(PROGRESS_PATH) {
            bearerAuth(fixture.token)
            contentType(ContentType.Application.Json)
            setBody(body)
          }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", response.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  @Test
  fun `maps blank media item path parameter to invalid query`() =
    testApplication {
      val fixture = Fixture.administrator()
      installProgress(fixture)

      val response =
        client.put("$XOBORO_API_PREFIX/media-items/%20/progress") {
          bearerAuth(fixture.token)
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("invalid_query", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.progresses.writeAttempts)
    }

  private fun ApplicationTestBuilder.installProgress(fixture: Fixture) {
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(StatusPages) {
        exception<BadRequestException> { call, _ ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_request", "Malformed request"),
          )
        }
        exception<ContentTransformationException> { call, _ ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_request", "Malformed JSON request"),
          )
        }
        exception<CrossSiteRequestRejectedException> { call, cause ->
          call.respond(
            HttpStatusCode.Forbidden,
            XoboroApiError(
              CrossSiteRequestRejectedException.CODE,
              requireNotNull(cause.message),
            ),
          )
        }
        exception<XoboroInvalidQueryException> { call, cause ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_query", requireNotNull(cause.message)),
          )
        }
      }
      routing {
        xoboroNativeProgressRoutes(fixture.catalog, fixture.lifecycle)
      }
    }
    client =
      createClient {
        install(ClientContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      }
  }

  private fun HttpRequestBuilder.trustedBrowserMutation() {
    header(HttpHeaders.Origin, "http://localhost")
    header("Sec-Fetch-Site", "same-origin")
  }

  private class Fixture private constructor(
    user: User,
    initialProgress: ReadProgress?,
  ) {
    private val users = InMemoryUserRepository(user)
    private val book = syntheticBook()
    val progresses = InMemoryReadProgressRepository(initialProgress)
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory = { "progress-token" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val token = requireNotNull(sessions.create(user)).plainToken
    val catalog = RecordingCatalog(book)
    val lifecycle =
      ReadProgressLifecycle(
        books = InMemoryBookRepository(book.book),
        series = InMemorySeriesRepository(),
        media = InMemoryBookMediaRepository(requireNotNull(book.media)),
        progresses = progresses,
        currentTimeMillis = { 300 },
      )

    fun storedProgress(): ReadProgress? =
      progresses.findByBookIdAndUserIdOrNull(MEDIA_ID, USER_ID)

    companion object {
      fun administrator(initialProgress: ReadProgress? = null): Fixture =
        Fixture(
          user =
            syntheticUser(
              roles = setOf(UserRole.ADMIN),
              sharesAllLibraries = true,
            ),
          initialProgress = initialProgress,
        )

      fun restricted(): Fixture =
        Fixture(
          user =
            syntheticUser(
              roles = setOf(UserRole.PAGE_STREAMING),
              sharesAllLibraries = false,
              sharedLibraryIds = setOf(VISIBLE_LIBRARY_ID),
            ),
          initialProgress = null,
        )
    }
  }

  private class RecordingCatalog(
    private val book: CatalogBook,
  ) : CatalogReadRepository {
    override fun findBooks(
      query: BookCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogBook> = error("Not used")

    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? {
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

  private class InMemoryBookRepository(
    book: Book,
  ) : BookRepository {
    private val values = linkedMapOf(book.id to book)

    override fun findByIdOrNull(id: BookId): Book? = values[id]

    override fun findAllByLibraryId(libraryId: LibraryId): List<Book> =
      values.values.filter { it.libraryId == libraryId }

    override fun findAllBySeriesId(seriesId: SeriesId): List<Book> =
      values.values.filter { it.seriesId == seriesId }

    override fun findByLibraryIdAndRelativePath(
      libraryId: LibraryId,
      relativePath: String,
    ): Book? = values.values.firstOrNull {
      it.libraryId == libraryId && it.relativePath == relativePath
    }

    override fun insert(book: Book) {
      values[book.id] = book
    }

    override fun insertAll(books: Collection<Book>) = books.forEach(::insert)

    override fun update(book: Book) = insert(book)

    override fun updateAll(books: Collection<Book>) = books.forEach(::update)

    override fun delete(id: BookId) {
      values.remove(id)
    }

    override fun count(): Long = values.size.toLong()
  }

  private class InMemorySeriesRepository : SeriesRepository {
    override fun findByIdOrNull(id: SeriesId): Series? = null

    override fun findAllByLibraryId(libraryId: LibraryId): List<Series> = emptyList()

    override fun findByLibraryIdAndRelativePath(
      libraryId: LibraryId,
      relativePath: String,
    ): Series? = null

    override fun insert(series: Series) = error("Not used")

    override fun insertAll(series: Collection<Series>) = error("Not used")

    override fun update(series: Series) = error("Not used")

    override fun updateAll(series: Collection<Series>) = error("Not used")

    override fun delete(id: SeriesId) = error("Not used")

    override fun count(): Long = 0
  }

  private class InMemoryBookMediaRepository(
    media: BookMedia,
  ) : BookMediaRepository {
    private val values = linkedMapOf(media.bookId to media)

    override fun findByBookIdOrNull(bookId: BookId): BookMedia? = values[bookId]

    override fun upsert(media: BookMedia) {
      values[media.bookId] = media
    }

    override fun deleteByBookId(bookId: BookId) {
      values.remove(bookId)
    }
  }

  private class InMemoryReadProgressRepository(
    initial: ReadProgress?,
  ) : ReadProgressRepository {
    private val values =
      initial
        ?.let { linkedMapOf((it.bookId to it.userId) to it) }
        ?: linkedMapOf()
    var writeAttempts = 0
      private set

    override fun findByBookIdAndUserIdOrNull(
      bookId: BookId,
      userId: UserId,
    ): ReadProgress? = values[bookId to userId]

    override fun findAllByBookIdsAndUserId(
      bookIds: Collection<BookId>,
      userId: UserId,
    ): List<ReadProgress> = bookIds.mapNotNull { values[it to userId] }

    override fun findSeriesByIdAndUserIdOrNull(
      seriesId: SeriesId,
      userId: UserId,
    ): SeriesReadProgress? = null

    override fun upsert(progress: ReadProgress) {
      values[progress.bookId to progress.userId] = progress
    }

    override fun upsertIfNewer(progress: ReadProgress): Boolean {
      writeAttempts += 1
      val existing = values[progress.bookId to progress.userId]
      if (existing != null && progress.readAtMillis <= existing.readAtMillis) return false
      upsert(progress)
      return true
    }

    override fun upsertAll(progresses: Collection<ReadProgress>) = progresses.forEach(::upsert)

    override fun delete(
      bookId: BookId,
      userId: UserId,
    ) {
      values.remove(bookId to userId)
    }

    override fun deleteBySeriesIdAndUserId(
      seriesId: SeriesId,
      userId: UserId,
    ) = error("Not used")
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
    private const val PAGE_COUNT = 10
    private val MEDIA_ID = BookId("media-progress")
    private val HIDDEN_LIBRARY_ID = LibraryId("library-hidden")
    private val VISIBLE_LIBRARY_ID = LibraryId("library-visible")
    private val SERIES_ID = SeriesId("series-progress")
    private val USER_ID = UserId("user-progress")
    private const val PROGRESS_PATH = "$XOBORO_API_PREFIX/media-items/media-progress/progress"

    private fun validRequest(
      page: Int = 4,
      modifiedAtMillis: Long = 200,
    ): XoboroMediaProgressRequest =
      XoboroMediaProgressRequest(
        page = page,
        locator =
          buildJsonObject {
            put("href", "chapter-2.xhtml")
            put(
              "locations",
              buildJsonObject {
                put("progression", 0.25)
              },
            )
          },
        deviceId = "device-1",
        deviceName = "Synthetic reader",
        modifiedAtMillis = modifiedAtMillis,
      )

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

    private fun syntheticBook(): CatalogBook {
      val book =
        Book(
          id = MEDIA_ID,
          libraryId = HIDDEN_LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic progress issue.cbz",
          relativePath = "Synthetic progress/Synthetic progress issue.cbz",
          sourceItemId = "file:///synthetic/progress/issue.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 2,
          fileSize = 100,
          number = 1,
          createdAtMillis = 1,
        )
      val seriesMetadata =
        SeriesMetadata(
          seriesId = SERIES_ID,
          title = "Synthetic progress series",
          createdAtMillis = 1,
        )
      return CatalogBook(
        book = book,
        seriesTitle = seriesMetadata.title,
        seriesMetadata = seriesMetadata,
        metadata =
          BookMetadata(
            bookId = MEDIA_ID,
            title = "Synthetic progress issue",
            number = "1",
            numberSort = 1F,
            createdAtMillis = 1,
          ),
        media =
          BookMedia(
            bookId = MEDIA_ID,
            status = MediaStatus.READY,
            mediaType = "application/vnd.comicbook+zip",
            pageCount = PAGE_COUNT,
            createdAtMillis = 1,
          ),
        readProgress = null,
      )
    }

    private fun syntheticProgress(
      page: Int,
      readAtMillis: Long,
    ): ReadProgress =
      ReadProgress(
        bookId = MEDIA_ID,
        userId = USER_ID,
        page = page,
        completed = page == PAGE_COUNT,
        readAtMillis = readAtMillis,
        deviceId = "stored-device",
        deviceName = "Stored reader",
        locatorJson = """{"href":"stored.xhtml"}""",
        createdAtMillis = 10,
        updatedAtMillis = 20,
      )
  }
}
