package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import io.xoboro.core.application.BookMetadataAggregation
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogGroupCount
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.MetadataEditingLifecycle
import io.xoboro.core.application.MetadataFacet
import io.xoboro.core.application.MetadataFacetQuery
import io.xoboro.core.application.MetadataFacetRepository
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SeriesMetadataRepository
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class XoboroNativeMetadataTest {
  @Test
  fun `hides unauthorized and nonexistent books before parsing or writing`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      val unauthorized =
        client.patch("$MEDIA_ITEMS_PATH/${HIDDEN_BOOK_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody("{not-json")
        }
      val missing =
        client.patch("$MEDIA_ITEMS_PATH/missing-book/metadata") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody("""{"title":"Ignored"}""")
        }

      for (response in listOf(unauthorized, missing)) {
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.bookMetadata.upsertCalls)
    }

  @Test
  fun `requires authentication on every metadata and facet route`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      for ((method, path) in AUTHENTICATED_ROUTES) {
        val response =
          client.request(path) {
            this.method = method
            if (method == HttpMethod.Patch) {
              contentType(ContentType.Application.Json)
              setBody("{}")
            }
          }
        assertEquals(HttpStatusCode.Unauthorized, response.status, "$method $path")
      }
      assertEquals(0, fixture.bookMetadata.upsertCalls)
      assertEquals(0, fixture.seriesMetadata.upsertCalls)
    }

  @Test
  fun `rejects cross-site cookie patches before any metadata write`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)
      val requests =
        listOf(
          "$MEDIA_ITEMS_PATH/${VISIBLE_BOOK_ID.value}/metadata" to "{}",
          "$MEDIA_ITEMS_PATH/metadata" to
            """{"${VISIBLE_BOOK_ID.value}":{"title":"Blocked"}}""",
          "$SERIES_PATH/${VISIBLE_SERIES_ID.value}/metadata" to "{}",
        )

      for ((path, body) in requests) {
        val response =
          client.patch(path) {
            cookie(XOBORO_SESSION_COOKIE, fixture.adminToken)
            header(HttpHeaders.Origin, "https://cross-site.example.invalid")
            header("Sec-Fetch-Site", "cross-site")
            contentType(ContentType.Application.Json)
            setBody(body)
          }
        assertEquals(HttpStatusCode.Forbidden, response.status, path)
        assertEquals(
          CrossSiteRequestRejectedException.CODE,
          response.body<XoboroApiError>().code,
          path,
        )
      }
      assertEquals(0, fixture.bookMetadata.upsertCalls)
      assertEquals(0, fixture.seriesMetadata.upsertCalls)
    }

  @Test
  fun `accepts bearer patch with a cross-site origin`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      val response =
        client.patch("$MEDIA_ITEMS_PATH/${VISIBLE_BOOK_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
          contentType(ContentType.Application.Json)
          setBody("""{"title":"Cross-site bearer title"}""")
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(1, fixture.bookMetadata.upsertCalls)
      assertEquals(
        "Cross-site bearer title",
        fixture.bookMetadata.findByBookIdOrNull(VISIBLE_BOOK_ID)?.title,
      )
    }

  @Test
  fun `patches one book and returns the editable metadata surface`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      val response =
        client.patch("$MEDIA_ITEMS_PATH/${VISIBLE_BOOK_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody(
            """
            {
              "title": "Updated Synthetic Issue",
              "tags": ["synthetic-tag-updated"],
              "authors": [{"name": "Synthetic Writer", "role": "writer"}],
              "links": [{"label": "Reference", "url": "https://example.invalid/reference"}]
            }
            """.trimIndent(),
          )
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<XoboroBookMetadataResponse>()
      assertEquals("Updated Synthetic Issue", body.title)
      assertEquals(setOf("synthetic-tag-updated"), body.tags)
      assertEquals("Synthetic Writer", body.authors.single().name)
      assertEquals("Reference", body.links.single().label)
      val stored = assertNotNull(fixture.bookMetadata.findByBookIdOrNull(VISIBLE_BOOK_ID))
      assertEquals(body.title, stored.title)
      assertEquals(body.tags, stored.tags)
      assertEquals(1, fixture.bookMetadata.upsertCalls)
    }

  @Test
  fun `distinguishes an absent book patch field from explicit null`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      val absent =
        client.patch("$MEDIA_ITEMS_PATH/${VISIBLE_BOOK_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody("""{"title":"Title changed without summary"}""")
        }
      assertEquals(HttpStatusCode.OK, absent.status)
      assertEquals(
        INITIAL_BOOK_SUMMARY,
        fixture.bookMetadata.findByBookIdOrNull(VISIBLE_BOOK_ID)?.summary,
      )

      val explicitNull =
        client.patch("$MEDIA_ITEMS_PATH/${VISIBLE_BOOK_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody("""{"summary":null}""")
        }
      assertEquals(HttpStatusCode.OK, explicitNull.status)
      assertEquals("", fixture.bookMetadata.findByBookIdOrNull(VISIBLE_BOOK_ID)?.summary)
      assertEquals(2, fixture.bookMetadata.upsertCalls)
    }

  @Test
  fun `manual book patches can change a field whose lock is set`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      val lock =
        client.patch("$MEDIA_ITEMS_PATH/${VISIBLE_BOOK_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody("""{"summaryLock":true}""")
        }
      val edit =
        client.patch("$MEDIA_ITEMS_PATH/${VISIBLE_BOOK_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody("""{"summary":"Manual edit despite lock"}""")
        }

      assertEquals(HttpStatusCode.OK, lock.status)
      assertEquals(HttpStatusCode.OK, edit.status)
      val stored = assertNotNull(fixture.bookMetadata.findByBookIdOrNull(VISIBLE_BOOK_ID))
      assertTrue(stored.summaryLock)
      assertEquals("Manual edit despite lock", stored.summary)
      assertEquals(2, fixture.bookMetadata.upsertCalls)
    }

  @Test
  fun `rejects an unauthorized or missing bulk id before writing any entry`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)
      val bodies =
        listOf(
          """
          {
            "${VISIBLE_BOOK_ID.value}":{"title":"Would otherwise succeed"},
            "${HIDDEN_BOOK_ID.value}":{"title":"Unauthorized"}
          }
          """.trimIndent(),
          """
          {
            "${VISIBLE_BOOK_ID.value}":{"title":"Would otherwise succeed"},
            "missing-book":{"title":"Missing"}
          }
          """.trimIndent(),
        )

      for (body in bodies) {
        val response =
          client.patch("$MEDIA_ITEMS_PATH/metadata") {
            bearerAuth(fixture.readerToken)
            contentType(ContentType.Application.Json)
            setBody(body)
          }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
        assertEquals(0, fixture.bookMetadata.upsertCalls)
      }
      assertEquals(INITIAL_BOOK_TITLE, fixture.bookMetadata.findByBookIdOrNull(VISIBLE_BOOK_ID)?.title)
    }

  @Test
  fun `rejects a bulk patch above the limit before lookup or writing`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)
      val body =
        buildJsonObject {
          repeat(XOBORO_METADATA_BULK_PATCH_LIMIT + 1) { index ->
            put("synthetic-book-$index", buildJsonObject { put("title", "Title $index") })
          }
        }

      val response =
        client.patch("$MEDIA_ITEMS_PATH/metadata") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(body)
        }

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("invalid_request", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.catalog.bookLookupCalls)
      assertEquals(0, fixture.bookMetadata.upsertCalls)
    }

  @Test
  fun `bulk patch updates every authorized book once and preserves input order`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)
      val request =
        buildJsonObject {
          put(
            VISIBLE_BOOK_ID.value,
            buildJsonObject { put("title", "First bulk response") },
          )
          put(
            SECOND_VISIBLE_BOOK_ID.value,
            buildJsonObject { put("title", "Second bulk response") },
          )
        }

      val response =
        client.patch("$MEDIA_ITEMS_PATH/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody(request)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<List<XoboroBookMetadataResponse>>()
      assertEquals(listOf("First bulk response", "Second bulk response"), body.map { it.title })
      assertEquals(2, fixture.bookMetadata.upsertCalls)
      assertEquals(1, fixture.bookMetadata.upsertsById[VISIBLE_BOOK_ID])
      assertEquals(1, fixture.bookMetadata.upsertsById[SECOND_VISIBLE_BOOK_ID])
    }

  @Test
  fun `reports a partially applied bulk patch instead of a short success`() =
    testApplication {
      val fixture = Fixture()
      // patchBooks drops entries whose patch fails inside the lifecycle, so without an explicit
      // size check the route would answer 200 with a shorter array and report success for an
      // edit that never happened.
      fixture.bookMetadata.failingUpserts += SECOND_VISIBLE_BOOK_ID
      installMetadata(fixture)
      val request =
        buildJsonObject {
          put(
            VISIBLE_BOOK_ID.value,
            buildJsonObject { put("title", "Applied entry") },
          )
          put(
            SECOND_VISIBLE_BOOK_ID.value,
            buildJsonObject { put("title", "Dropped entry") },
          )
        }

      val response =
        client.patch("$MEDIA_ITEMS_PATH/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody(request)
        }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("bulk_patch_incomplete", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.bookMetadata.upsertsById[VISIBLE_BOOK_ID])
      assertNull(fixture.bookMetadata.upsertsById[SECOND_VISIBLE_BOOK_ID])
    }

  @Test
  fun `hides unauthorized and nonexistent series without writing`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)
      val requests =
        listOf(
          fixture.readerToken to HIDDEN_SERIES_ID.value,
          fixture.adminToken to "missing-series",
        )

      for ((token, id) in requests) {
        val response =
          client.patch("$SERIES_PATH/$id/metadata") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Ignored"}""")
          }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("series_not_found", response.body<XoboroApiError>().code)
        assertEquals(0, fixture.seriesMetadata.upsertCalls)
      }
    }

  @Test
  fun `patches one series and stores exact submitted fields`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      val response =
        client.patch("$SERIES_PATH/${VISIBLE_SERIES_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody(
            """
            {
              "status":"ENDED",
              "publisher":"Synthetic Updated Publisher",
              "genres":["synthetic-genre-updated"],
              "alternateTitles":[{"label":"Short","title":"Synthetic Short Title"}]
            }
            """.trimIndent(),
          )
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<XoboroSeriesMetadataResponse>()
      assertEquals("ENDED", body.status)
      assertEquals("Synthetic Updated Publisher", body.publisher)
      assertEquals(setOf("synthetic-genre-updated"), body.genres)
      assertEquals("Synthetic Short Title", body.alternateTitles.single().title)
      val stored = assertNotNull(fixture.seriesMetadata.findBySeriesIdOrNull(VISIBLE_SERIES_ID))
      assertEquals(SeriesStatus.ENDED, stored.status)
      assertEquals(body.publisher, stored.publisher)
      assertEquals(1, fixture.seriesMetadata.upsertCalls)
    }

  @Test
  fun `distinguishes an absent series patch field from explicit null`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      val absent =
        client.patch("$SERIES_PATH/${VISIBLE_SERIES_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody("""{"title":"Series title without age change"}""")
        }
      assertEquals(HttpStatusCode.OK, absent.status)
      assertEquals(
        INITIAL_AGE_RATING,
        fixture.seriesMetadata.findBySeriesIdOrNull(VISIBLE_SERIES_ID)?.ageRating,
      )

      val explicitNull =
        client.patch("$SERIES_PATH/${VISIBLE_SERIES_ID.value}/metadata") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody("""{"ageRating":null}""")
        }
      assertEquals(HttpStatusCode.OK, explicitNull.status)
      assertNull(fixture.seriesMetadata.findBySeriesIdOrNull(VISIBLE_SERIES_ID)?.ageRating)
      assertEquals(2, fixture.seriesMetadata.upsertCalls)
    }

  @Test
  fun `scopes facet values to caller access and requested libraries`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      val restricted =
        client.get("$FACETS_PATH?facet=bookTag") {
          bearerAuth(fixture.readerToken)
        }
      val requestedHidden =
        client.get(
          "$FACETS_PATH?facet=bookTag&libraryId=${HIDDEN_LIBRARY_ID.value}",
        ) {
          bearerAuth(fixture.readerToken)
        }
      val administrator =
        client.get("$FACETS_PATH?facet=bookTag") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.OK, restricted.status)
      assertEquals(listOf(VISIBLE_FACET_VALUE), restricted.body<List<String>>())
      assertEquals(emptyList(), requestedHidden.body<List<String>>())
      assertEquals(
        listOf(HIDDEN_FACET_VALUE, VISIBLE_FACET_VALUE),
        administrator.body<List<String>>(),
      )
      assertEquals(
        listOf(setOf(VISIBLE_LIBRARY_ID), setOf(VISIBLE_LIBRARY_ID), null),
        fixture.facets.valuesAccesses.map(CatalogAccess::libraryIds),
      )
    }

  @Test
  fun `scopes paginated authors to caller access`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      val restricted =
        client.get("$FACETS_PATH/authors?page=0&size=20") {
          bearerAuth(fixture.readerToken)
        }
      assertEquals(HttpStatusCode.OK, restricted.status)
      val restrictedBody = restricted.body<XoboroPageResponse<XoboroAuthorResponse>>()
      assertEquals(listOf(VISIBLE_AUTHOR), restrictedBody.items.map { it.name })
      assertEquals(setOf(VISIBLE_LIBRARY_ID), fixture.facets.lastAuthorsAccess?.libraryIds)

      val administrator =
        client.get("$FACETS_PATH/authors?page=0&size=20") {
          bearerAuth(fixture.adminToken)
        }
      val administratorBody = administrator.body<XoboroPageResponse<XoboroAuthorResponse>>()
      assertEquals(listOf(HIDDEN_AUTHOR, VISIBLE_AUTHOR), administratorBody.items.map { it.name })
      assertEquals(2, administratorBody.totalItems)
      assertEquals(null, fixture.facets.lastAuthorsAccess?.libraryIds)
    }

  @Test
  fun `rejects unknown and missing facet values as invalid query`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      for (path in listOf("$FACETS_PATH?facet=nonsense", FACETS_PATH)) {
        val response =
          client.get(path) {
            bearerAuth(fixture.readerToken)
          }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_query", response.body<XoboroApiError>().code)
      }
    }

  @Test
  fun `maps malformed and wrong-typed book patches to invalid request`() =
    testApplication {
      val fixture = Fixture()
      installMetadata(fixture)

      for (body in listOf("{not-json", """{"titleLock":"yes"}""")) {
        val response =
          client.patch("$MEDIA_ITEMS_PATH/${VISIBLE_BOOK_ID.value}/metadata") {
            bearerAuth(fixture.readerToken)
            contentType(ContentType.Application.Json)
            setBody(body)
          }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", response.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.bookMetadata.upsertCalls)
    }

  private fun ApplicationTestBuilder.installMetadata(fixture: Fixture) {
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
        xoboroNativeMetadataRoutes(fixture.catalog, fixture.editing, fixture.facets)
      }
    }
    client =
      createClient {
        install(ClientContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      }
  }

  private class Fixture {
    private val users =
      InMemoryUserRepository(
        listOf(
          syntheticUser(
            ADMIN_USER_ID,
            "admin@example.invalid",
            setOf(UserRole.ADMIN),
            sharesAllLibraries = true,
          ),
          syntheticUser(
            READER_USER_ID,
            "reader@example.invalid",
            setOf(UserRole.PAGE_STREAMING),
            sharesAllLibraries = false,
            sharedLibraryIds = setOf(VISIBLE_LIBRARY_ID),
          ),
        ),
      )
    private val books =
      InMemoryBookRepository(
        listOf(
          syntheticBook(VISIBLE_BOOK_ID, VISIBLE_LIBRARY_ID, VISIBLE_SERIES_ID, 1),
          syntheticBook(SECOND_VISIBLE_BOOK_ID, VISIBLE_LIBRARY_ID, VISIBLE_SERIES_ID, 2),
          syntheticBook(HIDDEN_BOOK_ID, HIDDEN_LIBRARY_ID, HIDDEN_SERIES_ID, 1),
        ),
      )
    private val series =
      InMemorySeriesRepository(
        listOf(
          syntheticSeries(VISIBLE_SERIES_ID, VISIBLE_LIBRARY_ID, 2),
          syntheticSeries(HIDDEN_SERIES_ID, HIDDEN_LIBRARY_ID, 1),
        ),
      )
    val bookMetadata =
      InMemoryBookMetadataRepository(
        listOf(
          syntheticBookMetadata(
            VISIBLE_BOOK_ID,
            INITIAL_BOOK_TITLE,
            VISIBLE_AUTHOR,
            VISIBLE_FACET_VALUE,
          ),
          syntheticBookMetadata(
            SECOND_VISIBLE_BOOK_ID,
            "Synthetic Second Issue",
            VISIBLE_AUTHOR,
            VISIBLE_FACET_VALUE,
          ),
          syntheticBookMetadata(
            HIDDEN_BOOK_ID,
            "Synthetic Hidden Issue",
            HIDDEN_AUTHOR,
            HIDDEN_FACET_VALUE,
          ),
        ),
      )
    val seriesMetadata =
      InMemorySeriesMetadataRepository(
        listOf(
          syntheticSeriesMetadata(
            VISIBLE_SERIES_ID,
            "Synthetic Metadata Series",
            "Synthetic Visible Publisher",
          ),
          syntheticSeriesMetadata(
            HIDDEN_SERIES_ID,
            "Synthetic Hidden Series",
            "Synthetic Hidden Publisher",
          ),
        ),
      )
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory = tokenFactory(),
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val adminToken = sessions.create(requireNotNull(users.findByIdOrNull(ADMIN_USER_ID))).plainToken
    val readerToken = sessions.create(requireNotNull(users.findByIdOrNull(READER_USER_ID))).plainToken
    val catalog = RecordingCatalog(books, series, bookMetadata, seriesMetadata)
    val editing =
      MetadataEditingLifecycle(
        books = books,
        series = series,
        bookMetadata = bookMetadata,
        seriesMetadata = seriesMetadata,
        currentTimeMillis = { 100 },
      )
    val facets = RecordingFacets()
  }

  private class RecordingCatalog(
    private val books: BookRepository,
    private val series: SeriesRepository,
    private val bookMetadata: BookMetadataRepository,
    private val seriesMetadata: SeriesMetadataRepository,
  ) : CatalogReadRepository {
    var bookLookupCalls = 0
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
      bookLookupCalls += 1
      val book = books.findByIdOrNull(id) ?: return null
      if (!access.includes(book.libraryId)) return null
      val metadata = bookMetadata.findByBookIdOrNull(id) ?: return null
      val parentMetadata = seriesMetadata.findBySeriesIdOrNull(book.seriesId) ?: return null
      return CatalogBook(
        book = book,
        seriesTitle = parentMetadata.title,
        seriesMetadata = parentMetadata,
        metadata = metadata,
        media = null,
        readProgress = null,
      )
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
    ): CatalogSeries? {
      val item = series.findByIdOrNull(id) ?: return null
      if (!access.includes(item.libraryId)) return null
      val metadata = seriesMetadata.findBySeriesIdOrNull(id) ?: return null
      return CatalogSeries(
        series = item,
        metadata = metadata,
        booksMetadata =
          BookMetadataAggregation(
            createdAtMillis = metadata.createdAtMillis,
            updatedAtMillis = metadata.updatedAtMillis,
          ),
        readProgress = null,
      )
    }

    override fun countSeriesByFirstCharacter(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
    ): List<CatalogGroupCount> = emptyList()
  }

  private class RecordingFacets : MetadataFacetRepository {
    private val values =
      mapOf(
        VISIBLE_LIBRARY_ID to listOf(VISIBLE_FACET_VALUE),
        HIDDEN_LIBRARY_ID to listOf(HIDDEN_FACET_VALUE),
      )
    private val authors =
      mapOf(
        VISIBLE_LIBRARY_ID to listOf(Author(VISIBLE_AUTHOR, "writer")),
        HIDDEN_LIBRARY_ID to listOf(Author(HIDDEN_AUTHOR, "writer")),
      )
    val valuesAccesses = mutableListOf<CatalogAccess>()
    var lastAuthorsAccess: CatalogAccess? = null
      private set

    override fun findValues(
      facet: MetadataFacet,
      query: MetadataFacetQuery,
      access: CatalogAccess,
    ): List<String> {
      valuesAccesses += access
      if (facet != MetadataFacet.BOOK_TAG) return emptyList()
      return values
        .filterKeys { access.includes(it) && query.includes(it) }
        .values
        .flatten()
        .distinct()
        .sorted()
    }

    override fun findAuthors(
      query: MetadataFacetQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<Author> {
      lastAuthorsAccess = access
      val visible =
        authors
          .filterKeys { access.includes(it) && query.includes(it) }
          .values
          .flatten()
          .distinct()
          .sortedWith(compareBy({ it.name.lowercase() }, { it.role.lowercase() }))
      val start = (page.page * page.size).coerceAtMost(visible.size)
      val end = (start + page.size).coerceAtMost(visible.size)
      return CatalogPage(
        content = visible.subList(start, end),
        page = page.page,
        size = page.size,
        totalElements = visible.size.toLong(),
        sorts = emptyList(),
      )
    }
  }

  private class InMemoryBookRepository(
    books: Collection<Book>,
  ) : BookRepository {
    private val values = books.associateByTo(linkedMapOf(), Book::id)

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

  private class InMemorySeriesRepository(
    series: Collection<Series>,
  ) : SeriesRepository {
    private val values = series.associateByTo(linkedMapOf(), Series::id)

    override fun findByIdOrNull(id: SeriesId): Series? = values[id]

    override fun findAllByLibraryId(libraryId: LibraryId): List<Series> =
      values.values.filter { it.libraryId == libraryId }

    override fun findByLibraryIdAndRelativePath(
      libraryId: LibraryId,
      relativePath: String,
    ): Series? = values.values.firstOrNull {
      it.libraryId == libraryId && it.relativePath == relativePath
    }

    override fun insert(series: Series) {
      values[series.id] = series
    }

    override fun insertAll(series: Collection<Series>) = series.forEach(::insert)

    override fun update(series: Series) = insert(series)

    override fun updateAll(series: Collection<Series>) = series.forEach(::update)

    override fun delete(id: SeriesId) {
      values.remove(id)
    }

    override fun count(): Long = values.size.toLong()
  }

  private class InMemoryBookMetadataRepository(
    metadata: Collection<BookMetadata>,
  ) : BookMetadataRepository {
    private val values = metadata.associateByTo(linkedMapOf(), BookMetadata::bookId)
    var upsertCalls = 0
      private set
    val upsertsById = mutableMapOf<BookId, Int>()

    /** Ids whose upsert throws, standing in for a patch that fails inside the lifecycle. */
    val failingUpserts = mutableSetOf<BookId>()

    override fun findByBookIdOrNull(bookId: BookId): BookMetadata? = values[bookId]

    override fun upsert(metadata: BookMetadata) {
      if (metadata.bookId in failingUpserts) {
        throw IllegalStateException("Synthetic upsert failure for ${metadata.bookId.value}")
      }
      upsertCalls += 1
      upsertsById[metadata.bookId] = upsertsById.getOrDefault(metadata.bookId, 0) + 1
      values[metadata.bookId] = metadata
    }
  }

  private class InMemorySeriesMetadataRepository(
    metadata: Collection<SeriesMetadata>,
  ) : SeriesMetadataRepository {
    private val values = metadata.associateByTo(linkedMapOf(), SeriesMetadata::seriesId)
    var upsertCalls = 0
      private set

    override fun findBySeriesIdOrNull(seriesId: SeriesId): SeriesMetadata? = values[seriesId]

    override fun upsert(metadata: SeriesMetadata) {
      upsertCalls += 1
      values[metadata.seriesId] = metadata
    }
  }

  private class InMemoryUserRepository(
    users: Collection<User>,
  ) : UserRepository {
    private val values = users.associateByTo(linkedMapOf(), User::id)

    override fun count(): Long = values.size.toLong()

    override fun findByIdOrNull(id: UserId): User? = values[id]

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      values.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = values.values.toList()

    override fun insert(user: User) {
      if (findByEmailIgnoreCaseOrNull(user.email) != null) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      values[user.id] = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (values.isNotEmpty()) return false
      insert(user)
      return true
    }

    override fun update(user: User) {
      values[user.id] = user
    }

    override fun delete(id: UserId) {
      values.remove(id)
    }
  }

  companion object {
    private const val MEDIA_ITEMS_PATH = "$XOBORO_API_PREFIX/media-items"
    private const val SERIES_PATH = "$XOBORO_API_PREFIX/series"
    private const val FACETS_PATH = "$XOBORO_API_PREFIX/facets"
    private const val INITIAL_BOOK_TITLE = "Synthetic First Issue"
    private const val INITIAL_BOOK_SUMMARY = "Synthetic existing summary"
    private const val INITIAL_AGE_RATING = 13
    private const val VISIBLE_FACET_VALUE = "synthetic-tag-visible"
    private const val HIDDEN_FACET_VALUE = "synthetic-tag-hidden"
    private const val VISIBLE_AUTHOR = "Synthetic Visible Author"
    private const val HIDDEN_AUTHOR = "Synthetic Hidden Author"
    private val VISIBLE_LIBRARY_ID = LibraryId("library-visible")
    private val HIDDEN_LIBRARY_ID = LibraryId("library-hidden")
    private val VISIBLE_SERIES_ID = SeriesId("series-visible")
    private val HIDDEN_SERIES_ID = SeriesId("series-hidden")
    private val VISIBLE_BOOK_ID = BookId("book-visible-1")
    private val SECOND_VISIBLE_BOOK_ID = BookId("book-visible-2")
    private val HIDDEN_BOOK_ID = BookId("book-hidden")
    private val ADMIN_USER_ID = UserId("user-admin")
    private val READER_USER_ID = UserId("user-reader")
    private val AUTHENTICATED_ROUTES =
      listOf(
        HttpMethod.Patch to "$MEDIA_ITEMS_PATH/${VISIBLE_BOOK_ID.value}/metadata",
        HttpMethod.Patch to "$MEDIA_ITEMS_PATH/metadata",
        HttpMethod.Patch to "$SERIES_PATH/${VISIBLE_SERIES_ID.value}/metadata",
        HttpMethod.Get to "$FACETS_PATH?facet=bookTag",
        HttpMethod.Get to "$FACETS_PATH/authors",
      )

    private fun CatalogAccess.includes(libraryId: LibraryId): Boolean {
      val ids = libraryIds
      return ids == null || libraryId in ids
    }

    private fun MetadataFacetQuery.includes(libraryId: LibraryId): Boolean =
      libraryIds.isEmpty() || libraryId in libraryIds

    private fun tokenFactory(): () -> String {
      var next = 0
      return { "metadata-token-${next++}" }
    }

    private fun syntheticUser(
      id: UserId,
      email: String,
      roles: Set<UserRole>,
      sharesAllLibraries: Boolean,
      sharedLibraryIds: Set<LibraryId> = emptySet(),
    ): User =
      User(
        id = id,
        email = email,
        passwordHash = "synthetic-hash",
        roles = roles,
        sharedLibraryIds = sharedLibraryIds,
        sharesAllLibraries = sharesAllLibraries,
        createdAtMillis = 1,
      )

    private fun syntheticBook(
      id: BookId,
      libraryId: LibraryId,
      seriesId: SeriesId,
      number: Int,
    ): Book =
      Book(
        id = id,
        libraryId = libraryId,
        seriesId = seriesId,
        name = "Synthetic issue $number.cbz",
        relativePath = "Synthetic series/Synthetic issue $number.cbz",
        sourceItemId = "file:///synthetic/issue-$number.cbz",
        mediaKind = MediaKind.COMIC_ARCHIVE,
        fileModifiedAtMillis = 2,
        fileSize = 100,
        number = number,
        createdAtMillis = 1,
      )

    private fun syntheticSeries(
      id: SeriesId,
      libraryId: LibraryId,
      bookCount: Int,
    ): Series =
      Series(
        id = id,
        libraryId = libraryId,
        name = "Synthetic series",
        relativePath = "Synthetic series",
        sourceItemId = "file:///synthetic/${id.value}",
        fileModifiedAtMillis = 2,
        bookCount = bookCount,
        createdAtMillis = 1,
      )

    private fun syntheticBookMetadata(
      id: BookId,
      title: String,
      author: String,
      tag: String,
    ): BookMetadata =
      BookMetadata(
        bookId = id,
        title = title,
        summary = INITIAL_BOOK_SUMMARY,
        number = "1",
        numberSort = 1F,
        releaseDate = "2025-01-01",
        authors = listOf(Author(author, "writer")),
        tags = setOf(tag),
        createdAtMillis = 1,
      )

    private fun syntheticSeriesMetadata(
      id: SeriesId,
      title: String,
      publisher: String,
    ): SeriesMetadata =
      SeriesMetadata(
        seriesId = id,
        title = title,
        summary = "Synthetic series summary",
        publisher = publisher,
        ageRating = INITIAL_AGE_RATING,
        genres = setOf("synthetic-genre-original"),
        createdAtMillis = 1,
      )
  }
}
