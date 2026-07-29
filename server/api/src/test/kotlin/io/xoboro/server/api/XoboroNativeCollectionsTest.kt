package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.OrganizationLifecycle
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class XoboroNativeCollectionsTest {
  @Test
  fun `forbids non-administrator mutations on both resources without writes`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      for (route in MUTATION_ROUTES) {
        val response =
          client.request(route.path) {
            method = route.method
            bearerAuth(fixture.readerToken)
          }

        assertEquals(HttpStatusCode.Forbidden, response.status, route.path)
        assertEquals(route.forbiddenCode, response.body<XoboroApiError>().code, route.path)
      }
      fixture.assertNoWrites()
    }

  @Test
  fun `non-administrator update and delete hide nonexistent targets`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      for (route in NONEXISTENT_MUTATION_ROUTES) {
        val response =
          client.request(route.path) {
            method = route.method
            bearerAuth(fixture.readerToken)
          }

        assertEquals(HttpStatusCode.Forbidden, response.status, route.path)
        assertEquals(route.forbiddenCode, response.body<XoboroApiError>().code, route.path)
      }
      assertEquals(0, fixture.collections.findByIdCalls)
      assertEquals(0, fixture.readLists.findByIdCalls)
      fixture.assertNoWrites()
    }

  @Test
  fun `requires authentication on every collection and read-list route`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      for (route in ALL_ROUTES) {
        val response = client.request(route.path) { method = route.method }
        assertEquals(HttpStatusCode.Unauthorized, response.status, route.path)
      }
      fixture.assertNoWrites()
    }

  @Test
  fun `rejects cross-site cookie mutations on both resources without writes`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      for (route in MUTATION_ROUTES) {
        val response =
          client.request(route.path) {
            method = route.method
            cookie(XOBORO_SESSION_COOKIE, fixture.adminToken)
            header(HttpHeaders.Origin, "https://cross-site.example.invalid")
            header("Sec-Fetch-Site", "cross-site")
          }

        assertEquals(HttpStatusCode.Forbidden, response.status, route.path)
        assertEquals(
          CrossSiteRequestRejectedException.CODE,
          response.body<XoboroApiError>().code,
          route.path,
        )
      }
      fixture.assertNoWrites()
    }

  @Test
  fun `accepts bearer creates with a cross-site origin`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val collection =
        client.post(COLLECTIONS_PATH) {
          bearerAuth(fixture.adminToken)
          crossSiteOrigin()
          contentType(ContentType.Application.Json)
          setBody(collectionCreation())
        }
      val readList =
        client.post(READ_LISTS_PATH) {
          bearerAuth(fixture.adminToken)
          crossSiteOrigin()
          contentType(ContentType.Application.Json)
          setBody(readListCreation())
        }

      assertEquals(HttpStatusCode.Created, collection.status)
      assertEquals(HttpStatusCode.Created, readList.status)
      assertEquals(1, fixture.collections.insertCalls)
      assertEquals(1, fixture.readLists.insertCalls)
    }

  @Test
  fun `creates both resources with submitted members and stored order`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)
      val collectionRequest =
        collectionCreation(
          name = "Created Collection",
          seriesIds = listOf(SERIES_THREE.value, SERIES_ONE.value),
        )
      val readListRequest =
        readListCreation(
          name = "Created Read List",
          mediaItemIds = listOf(BOOK_THREE.value, BOOK_ONE.value),
        )

      val collection =
        client.post(COLLECTIONS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(collectionRequest)
        }
      val readList =
        client.post(READ_LISTS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(readListRequest)
        }

      assertEquals(HttpStatusCode.Created, collection.status)
      assertEquals(2, collection.body<XoboroCollectionResponse>().memberCount)
      assertEquals(
        collectionRequest.seriesIds.map(::SeriesId),
        assertNotNull(fixture.collections.snapshot(CREATED_COLLECTION_ID)).seriesIds,
      )
      assertEquals(HttpStatusCode.Created, readList.status)
      assertEquals(2, readList.body<XoboroReadListResponse>().memberCount)
      assertEquals(
        readListRequest.mediaItemIds.map(::BookId),
        assertNotNull(fixture.readLists.snapshot(CREATED_READ_LIST_ID)).bookIds,
      )
      assertEquals(
        collectionRequest.seriesIds,
        client
          .get("$COLLECTIONS_PATH/${CREATED_COLLECTION_ID.value}/series") {
            bearerAuth(fixture.adminToken)
          }.body<XoboroPageResponse<XoboroSeriesResponse>>()
          .items
          .map(XoboroSeriesResponse::id),
      )
      assertEquals(
        readListRequest.mediaItemIds,
        client
          .get("$READ_LISTS_PATH/${CREATED_READ_LIST_ID.value}/media-items") {
            bearerAuth(fixture.adminToken)
          }.body<XoboroPageResponse<XoboroMediaItemResponse>>()
          .items
          .map(XoboroMediaItemResponse::id),
      )
    }

  @Test
  fun `updates fully replace members and order on both resources`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)
      val collectionRequest =
        XoboroCollectionUpdateRequest(
          name = "Replaced Collection",
          ordered = false,
          seriesIds = listOf(SERIES_THREE.value, SERIES_ONE.value),
        )
      val readListRequest =
        XoboroReadListUpdateRequest(
          name = "Replaced Read List",
          summary = "Replacement summary",
          ordered = false,
          mediaItemIds = listOf(BOOK_THREE.value, BOOK_ONE.value),
        )

      val collection =
        client.put("$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(collectionRequest)
        }
      val readList =
        client.put("$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(readListRequest)
        }

      assertEquals(HttpStatusCode.OK, collection.status)
      assertEquals(HttpStatusCode.OK, readList.status)
      val storedCollection = assertNotNull(fixture.collections.snapshot(VISIBLE_COLLECTION_ID))
      assertEquals(listOf(SERIES_THREE, SERIES_ONE), storedCollection.seriesIds)
      assertEquals(false, storedCollection.ordered)
      val storedReadList = assertNotNull(fixture.readLists.snapshot(VISIBLE_READ_LIST_ID))
      assertEquals(listOf(BOOK_THREE, BOOK_ONE), storedReadList.bookIds)
      assertEquals("Replacement summary", storedReadList.summary)
      assertEquals(false, storedReadList.ordered)
      assertEquals(
        collectionRequest.seriesIds,
        client
          .get("$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}/series") {
            bearerAuth(fixture.adminToken)
          }.body<XoboroPageResponse<XoboroSeriesResponse>>()
          .items
          .map(XoboroSeriesResponse::id),
      )
      assertEquals(
        readListRequest.mediaItemIds,
        client
          .get("$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}/media-items") {
            bearerAuth(fixture.adminToken)
          }.body<XoboroPageResponse<XoboroMediaItemResponse>>()
          .items
          .map(XoboroMediaItemResponse::id),
      )
    }

  @Test
  fun `unknown and invisible update members return invalid request without writes`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)
      val originalCollection = fixture.collections.snapshot(VISIBLE_COLLECTION_ID)
      val originalReadList = fixture.readLists.snapshot(VISIBLE_READ_LIST_ID)

      val unknown =
        client.put("$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(
            XoboroCollectionUpdateRequest(
              name = "Unknown Member Collection",
              ordered = true,
              seriesIds = listOf("series-unknown"),
            ),
          )
        }
      val invisible =
        client.put("$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(
            XoboroReadListUpdateRequest(
              name = "Invisible Member Read List",
              summary = "",
              ordered = true,
              mediaItemIds = listOf(BOOK_BLOCKED.value),
            ),
          )
        }

      assertEquals(HttpStatusCode.BadRequest, unknown.status)
      assertEquals("invalid_request", unknown.body<XoboroApiError>().code)
      assertEquals(HttpStatusCode.BadRequest, invisible.status)
      assertEquals("invalid_request", invisible.body<XoboroApiError>().code)
      assertEquals(originalCollection, fixture.collections.snapshot(VISIBLE_COLLECTION_ID))
      assertEquals(originalReadList, fixture.readLists.snapshot(VISIBLE_READ_LIST_ID))
      assertEquals(
        originalCollection?.seriesIds?.map(SeriesId::value),
        client
          .get("$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}/series") {
            bearerAuth(fixture.adminToken)
          }.body<XoboroPageResponse<XoboroSeriesResponse>>()
          .items
          .map(XoboroSeriesResponse::id),
      )
      assertEquals(
        originalReadList?.bookIds?.map(BookId::value),
        client
          .get("$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}/media-items") {
            bearerAuth(fixture.adminToken)
          }.body<XoboroPageResponse<XoboroMediaItemResponse>>()
          .items
          .map(XoboroMediaItemResponse::id),
      )
      assertEquals(0, fixture.collections.updateCalls)
      assertEquals(0, fixture.readLists.updateCalls)
    }

  @Test
  fun `unknown and invisible create members return invalid request without writes`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val unknown =
        client.post(COLLECTIONS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(collectionCreation(seriesIds = listOf("series-unknown")))
        }
      val invisible =
        client.post(READ_LISTS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(readListCreation(mediaItemIds = listOf(BOOK_BLOCKED.value)))
        }

      assertEquals(HttpStatusCode.BadRequest, unknown.status)
      assertEquals("invalid_request", unknown.body<XoboroApiError>().code)
      assertEquals(HttpStatusCode.BadRequest, invisible.status)
      assertEquals("invalid_request", invisible.body<XoboroApiError>().code)
      fixture.assertNoWrites()
    }

  @Test
  fun `deletes both resources once and then returns not found`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val collectionDeleted =
        client.delete("$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}") {
          bearerAuth(fixture.adminToken)
        }
      val collectionMissing =
        client.delete("$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}") {
          bearerAuth(fixture.adminToken)
        }
      val readListDeleted =
        client.delete("$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}") {
          bearerAuth(fixture.adminToken)
        }
      val readListMissing =
        client.delete("$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.NoContent, collectionDeleted.status)
      assertEquals(HttpStatusCode.NotFound, collectionMissing.status)
      assertEquals("collection_not_found", collectionMissing.body<XoboroApiError>().code)
      assertEquals(HttpStatusCode.NoContent, readListDeleted.status)
      assertEquals(HttpStatusCode.NotFound, readListMissing.status)
      assertEquals("read_list_not_found", readListMissing.body<XoboroApiError>().code)
      assertEquals(1, fixture.collections.deleteCalls)
      assertEquals(1, fixture.readLists.deleteCalls)
    }

  @Test
  fun `fully invisible resources return not found for detail and members`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val requests =
        listOf(
          "$COLLECTIONS_PATH/${HIDDEN_COLLECTION_ID.value}" to "collection_not_found",
          "$COLLECTIONS_PATH/${HIDDEN_COLLECTION_ID.value}/series" to "collection_not_found",
          "$READ_LISTS_PATH/${HIDDEN_READ_LIST_ID.value}" to "read_list_not_found",
          "$READ_LISTS_PATH/${HIDDEN_READ_LIST_ID.value}/media-items" to "read_list_not_found",
        )
      for ((path, code) in requests) {
        val response = client.get(path) { bearerAuth(fixture.readerToken) }
        assertEquals(HttpStatusCode.NotFound, response.status, path)
        assertEquals(code, response.body<XoboroApiError>().code, path)
      }
    }

  @Test
  fun `mixed collection filters members preserves order and exposes visible count`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val detail =
        client.get("$COLLECTIONS_PATH/${MIXED_COLLECTION_ID.value}") {
          bearerAuth(fixture.readerToken)
        }
      val members =
        client.get("$COLLECTIONS_PATH/${MIXED_COLLECTION_ID.value}/series") {
          bearerAuth(fixture.readerToken)
        }
      val listed =
        client.get(COLLECTIONS_PATH) {
          bearerAuth(fixture.readerToken)
        }.body<List<XoboroCollectionResponse>>()

      assertEquals(HttpStatusCode.OK, detail.status)
      assertEquals(2, detail.body<XoboroCollectionResponse>().memberCount)
      assertEquals(
        listOf(SERIES_THREE.value, SERIES_ONE.value),
        members
          .body<XoboroPageResponse<XoboroSeriesResponse>>()
          .items
          .map(XoboroSeriesResponse::id),
      )
      assertEquals(2, listed.single { it.id == MIXED_COLLECTION_ID.value }.memberCount)
      assertTrue(listed.none { it.id == HIDDEN_COLLECTION_ID.value })
    }

  @Test
  fun `read-list filtering removes an invisible middle member without reordering`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val detail =
        client.get("$READ_LISTS_PATH/${MIXED_READ_LIST_ID.value}") {
          bearerAuth(fixture.readerToken)
        }
      val members =
        client.get("$READ_LISTS_PATH/${MIXED_READ_LIST_ID.value}/media-items") {
          bearerAuth(fixture.readerToken)
        }
      val listed =
        client.get(READ_LISTS_PATH) {
          bearerAuth(fixture.readerToken)
        }.body<List<XoboroReadListResponse>>()

      assertEquals(2, detail.body<XoboroReadListResponse>().memberCount)
      assertEquals(
        listOf(BOOK_ONE.value, BOOK_THREE.value),
        members
          .body<XoboroPageResponse<XoboroMediaItemResponse>>()
          .items
          .map(XoboroMediaItemResponse::id),
      )
      assertEquals(2, listed.single { it.id == MIXED_READ_LIST_ID.value }.memberCount)
      assertTrue(listed.none { it.id == HIDDEN_READ_LIST_ID.value })
    }

  @Test
  fun `series reverse lookup returns only caller-visible collection projections`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val response =
        client.get("$XOBORO_API_PREFIX/series/${SERIES_ONE.value}/collections") {
          bearerAuth(fixture.readerToken)
        }
      val hiddenAnchor =
        client.get("$XOBORO_API_PREFIX/series/${SERIES_HIDDEN.value}/collections") {
          bearerAuth(fixture.readerToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val collections = response.body<List<XoboroCollectionResponse>>()
      assertEquals(
        listOf(VISIBLE_COLLECTION_ID.value, MIXED_COLLECTION_ID.value),
        collections.map(XoboroCollectionResponse::id),
      )
      assertEquals(listOf(2, 2), collections.map(XoboroCollectionResponse::memberCount))
      assertEquals(HttpStatusCode.NotFound, hiddenAnchor.status)
      assertEquals("series_not_found", hiddenAnchor.body<XoboroApiError>().code)
    }

  @Test
  fun `media-item reverse lookup returns only caller-visible read-list projections`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val response =
        client.get("$XOBORO_API_PREFIX/media-items/${BOOK_ONE.value}/read-lists") {
          bearerAuth(fixture.readerToken)
        }
      val hiddenAnchor =
        client.get("$XOBORO_API_PREFIX/media-items/${BOOK_HIDDEN.value}/read-lists") {
          bearerAuth(fixture.readerToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val readLists = response.body<List<XoboroReadListResponse>>()
      assertEquals(
        listOf(VISIBLE_READ_LIST_ID.value, MIXED_READ_LIST_ID.value),
        readLists.map(XoboroReadListResponse::id),
      )
      assertEquals(listOf(2, 2), readLists.map(XoboroReadListResponse::memberCount))
      assertEquals(HttpStatusCode.NotFound, hiddenAnchor.status)
      assertEquals("media_item_not_found", hiddenAnchor.body<XoboroApiError>().code)
    }

  @Test
  fun `member routes use native paging and reject sizes over the ceiling`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val collectionPage =
        client.get("$COLLECTIONS_PATH/${MIXED_COLLECTION_ID.value}/series?page=1&size=1") {
          bearerAuth(fixture.readerToken)
        }.body<XoboroPageResponse<XoboroSeriesResponse>>()
      val readListPage =
        client.get("$READ_LISTS_PATH/${MIXED_READ_LIST_ID.value}/media-items?page=1&size=1") {
          bearerAuth(fixture.readerToken)
        }.body<XoboroPageResponse<XoboroMediaItemResponse>>()
      val oversizedCollection =
        client.get("$COLLECTIONS_PATH/${MIXED_COLLECTION_ID.value}/series?size=201") {
          bearerAuth(fixture.readerToken)
        }
      val oversizedReadList =
        client.get("$READ_LISTS_PATH/${MIXED_READ_LIST_ID.value}/media-items?size=201") {
          bearerAuth(fixture.readerToken)
        }
      val unsupportedSort =
        client.get("$COLLECTIONS_PATH/${MIXED_COLLECTION_ID.value}/series?sort=title") {
          bearerAuth(fixture.readerToken)
        }

      assertEquals(listOf(SERIES_ONE.value), collectionPage.items.map(XoboroSeriesResponse::id))
      assertEquals(1, collectionPage.page)
      assertEquals(1, collectionPage.size)
      assertEquals(2L, collectionPage.totalItems)
      assertEquals(2, collectionPage.totalPages)
      assertEquals(true, collectionPage.hasPrevious)
      assertEquals(false, collectionPage.hasNext)
      assertEquals(listOf(BOOK_THREE.value), readListPage.items.map(XoboroMediaItemResponse::id))
      for (response in listOf(oversizedCollection, oversizedReadList, unsupportedSort)) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_query", response.body<XoboroApiError>().code)
      }
    }

  @Test
  fun `malformed create and update JSON returns invalid request without writes`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)
      val requests =
        listOf(
          RouteSpec(HttpMethod.Post, COLLECTIONS_PATH),
          RouteSpec(HttpMethod.Put, "$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}"),
          RouteSpec(HttpMethod.Post, READ_LISTS_PATH),
          RouteSpec(HttpMethod.Put, "$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}"),
        )

      for (route in requests) {
        val response =
          client.request(route.path) {
            method = route.method
            bearerAuth(fixture.adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"name":""")
          }
        assertEquals(HttpStatusCode.BadRequest, response.status, route.path)
        assertEquals("invalid_request", response.body<XoboroApiError>().code, route.path)
      }
      fixture.assertNoWrites()
    }

  @Test
  fun `domain validation and duplicate names use stable error codes`() =
    testApplication {
      val fixture = Fixture()
      installCollections(fixture)

      val collectionConflict =
        client.post(COLLECTIONS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(collectionCreation(name = "Fixture Collection"))
        }
      val readListConflict =
        client.post(READ_LISTS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(readListCreation(name = "Fixture Read List"))
        }
      val invalidCollection =
        client.post(COLLECTIONS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(collectionCreation(seriesIds = emptyList()))
        }
      val invalidReadList =
        client.post(READ_LISTS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(readListCreation(mediaItemIds = emptyList()))
        }

      assertEquals(HttpStatusCode.Conflict, collectionConflict.status)
      assertEquals("collection_name_conflict", collectionConflict.body<XoboroApiError>().code)
      assertEquals(HttpStatusCode.Conflict, readListConflict.status)
      assertEquals("read_list_name_conflict", readListConflict.body<XoboroApiError>().code)
      assertEquals(HttpStatusCode.BadRequest, invalidCollection.status)
      assertEquals("invalid_request", invalidCollection.body<XoboroApiError>().code)
      assertEquals(HttpStatusCode.BadRequest, invalidReadList.status)
      assertEquals("invalid_request", invalidReadList.body<XoboroApiError>().code)
      fixture.assertNoWrites()
    }

  private fun ApplicationTestBuilder.installCollections(fixture: Fixture) {
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(StatusPages) {
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
      }
      routing {
        xoboroNativeCollectionsRoutes(
          organization = fixture.organization,
          collections = fixture.collections,
          readLists = fixture.readLists,
          catalog = fixture.catalog,
        )
      }
    }
    client =
      createClient {
        install(ClientContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      }
  }

  private fun io.ktor.client.request.HttpRequestBuilder.crossSiteOrigin() {
    header(HttpHeaders.Origin, "https://cross-site.example.invalid")
    header("Sec-Fetch-Site", "cross-site")
  }

  private class Fixture {
    private val users =
      InMemoryUserRepository(
        listOf(
          syntheticUser(
            id = ADMIN_USER_ID,
            email = "admin@example.invalid",
            roles = setOf(UserRole.ADMIN),
            restrictions = ContentRestrictions(labelsExclude = setOf(BLOCKED_LABEL)),
          ),
          syntheticUser(
            id = READER_USER_ID,
            email = "reader@example.invalid",
            roles = setOf(UserRole.PAGE_STREAMING),
            sharesAllLibraries = false,
            sharedLibraryIds = setOf(VISIBLE_LIBRARY_ID),
          ),
        ),
      )
    private val catalogSeries =
      listOf(
        syntheticSeries(SERIES_ONE, VISIBLE_LIBRARY_ID),
        syntheticSeries(SERIES_THREE, VISIBLE_LIBRARY_ID),
        syntheticSeries(SERIES_HIDDEN, HIDDEN_LIBRARY_ID),
        syntheticSeries(SERIES_BLOCKED, VISIBLE_LIBRARY_ID, setOf(BLOCKED_LABEL)),
      )
    private val catalogBooks =
      listOf(
        syntheticBook(BOOK_ONE, SERIES_ONE, VISIBLE_LIBRARY_ID, "1", emptySet()),
        syntheticBook(BOOK_THREE, SERIES_THREE, VISIBLE_LIBRARY_ID, "3", emptySet()),
        syntheticBook(BOOK_HIDDEN, SERIES_HIDDEN, HIDDEN_LIBRARY_ID, "2", emptySet()),
        syntheticBook(
          BOOK_BLOCKED,
          SERIES_BLOCKED,
          VISIBLE_LIBRARY_ID,
          "4",
          setOf(BLOCKED_LABEL),
        ),
      )
    val collections =
      InMemorySeriesCollectionRepository(
        listOf(
          SeriesCollection(
            id = VISIBLE_COLLECTION_ID,
            name = "Fixture Collection",
            ordered = true,
            seriesIds = listOf(SERIES_ONE, SERIES_HIDDEN, SERIES_THREE),
            createdAtMillis = 1,
          ),
          SeriesCollection(
            id = MIXED_COLLECTION_ID,
            name = "Mixed Collection",
            ordered = true,
            seriesIds = listOf(SERIES_THREE, SERIES_HIDDEN, SERIES_ONE),
            createdAtMillis = 1,
          ),
          SeriesCollection(
            id = HIDDEN_COLLECTION_ID,
            name = "Hidden Collection",
            ordered = true,
            seriesIds = listOf(SERIES_HIDDEN),
            createdAtMillis = 1,
          ),
          SeriesCollection(
            id = BLOCKED_COLLECTION_ID,
            name = "Blocked Collection",
            ordered = true,
            seriesIds = listOf(SERIES_BLOCKED),
            createdAtMillis = 1,
          ),
        ),
      )
    val readLists =
      InMemoryReadListRepository(
        listOf(
          ReadList(
            id = VISIBLE_READ_LIST_ID,
            name = "Fixture Read List",
            summary = "Fixture summary",
            ordered = true,
            bookIds = listOf(BOOK_ONE, BOOK_HIDDEN, BOOK_THREE),
            createdAtMillis = 1,
          ),
          ReadList(
            id = MIXED_READ_LIST_ID,
            name = "Mixed Read List",
            summary = "Mixed summary",
            ordered = true,
            bookIds = listOf(BOOK_ONE, BOOK_HIDDEN, BOOK_THREE),
            createdAtMillis = 1,
          ),
          ReadList(
            id = HIDDEN_READ_LIST_ID,
            name = "Hidden Read List",
            summary = "Hidden summary",
            ordered = true,
            bookIds = listOf(BOOK_HIDDEN),
            createdAtMillis = 1,
          ),
          ReadList(
            id = BLOCKED_READ_LIST_ID,
            name = "Blocked Read List",
            summary = "Blocked summary",
            ordered = true,
            bookIds = listOf(BOOK_BLOCKED),
            createdAtMillis = 1,
          ),
        ),
      )
    private val seriesRepository = InMemorySeriesRepository(catalogSeries.map(CatalogSeries::series))
    private val bookRepository = InMemoryBookRepository(catalogBooks.map(CatalogBook::book))
    private var collectionSequence = 0
    private var readListSequence = 0
    val organization =
      OrganizationLifecycle(
        collections = collections,
        readLists = readLists,
        series = seriesRepository,
        books = bookRepository,
        collectionIdFactory = {
          collectionSequence += 1
          if (collectionSequence == 1) CREATED_COLLECTION_ID.value else
            "collection-created-$collectionSequence"
        },
        readListIdFactory = {
          readListSequence += 1
          if (readListSequence == 1) CREATED_READ_LIST_ID.value else
            "read-list-created-$readListSequence"
        },
        currentTimeMillis = { 2 },
      )
    val catalog = AccessAwareCatalog(catalogSeries, catalogBooks, collections, readLists)
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory = listOf("admin-token", "reader-token").iterator().let { it::next },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val adminToken =
      sessions.create(requireNotNull(users.findByIdOrNull(ADMIN_USER_ID))).plainToken
    val readerToken =
      sessions.create(requireNotNull(users.findByIdOrNull(READER_USER_ID))).plainToken

    fun assertNoWrites() {
      assertEquals(0, collections.insertCalls)
      assertEquals(0, collections.updateCalls)
      assertEquals(0, collections.deleteCalls)
      assertEquals(0, readLists.insertCalls)
      assertEquals(0, readLists.updateCalls)
      assertEquals(0, readLists.deleteCalls)
    }
  }

  private class AccessAwareCatalog(
    private val series: List<CatalogSeries>,
    private val books: List<CatalogBook>,
    private val collections: SeriesCollectionRepository,
    private val readLists: ReadListRepository,
  ) : CatalogReadRepository {
    override fun findBooks(
      query: BookCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogBook> {
      check(page.unpaged) { "Collection routes must request unpaged read-list members" }
      val readListId =
        ReadListId(
          query.condition.requireMembershipValue(CatalogSearchField.READ_LIST_ID),
        )
      val memberIds = readLists.findByIdOrNull(readListId)?.bookIds.orEmpty().toSet()
      val visible = books.filter { it.book.id in memberIds && it.isVisible(access) }
      return visible.toUnpagedCatalogPage(page)
    }

    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = books.firstOrNull { it.book.id == id && it.isVisible(access) }

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
    ): CatalogPage<CatalogSeries> {
      check(page.unpaged) { "Collection routes must request unpaged collection members" }
      val collectionId =
        CollectionId(
          query.condition.requireMembershipValue(CatalogSearchField.COLLECTION_ID),
        )
      val memberIds = collections.findByIdOrNull(collectionId)?.seriesIds.orEmpty().toSet()
      val visible = series.filter { it.series.id in memberIds && it.isVisible(access) }
      return visible.toUnpagedCatalogPage(page)
    }

    override fun findSeriesByIdOrNull(
      id: SeriesId,
      access: CatalogAccess,
    ): CatalogSeries? = series.firstOrNull { it.series.id == id && it.isVisible(access) }

    override fun countSeriesByFirstCharacter(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
    ): List<CatalogGroupCount> = emptyList()

    private fun CatalogSeries.isVisible(access: CatalogAccess): Boolean =
      series.deletedAtMillis == null &&
        access.includesLibrary(series.libraryId) &&
        access.restrictions.let {
          User(
            id = access.userId ?: UserId("catalog-user"),
            email = "catalog@example.invalid",
            passwordHash = "synthetic-hash",
            restrictions = it,
            createdAtMillis = 1,
          ).isContentAllowed(metadata.ageRating, metadata.normalizedSharingLabels)
        }

    private fun CatalogBook.isVisible(access: CatalogAccess): Boolean =
      book.deletedAtMillis == null &&
        access.includesLibrary(book.libraryId) &&
        access.restrictions.let {
          User(
            id = access.userId ?: UserId("catalog-user"),
            email = "catalog@example.invalid",
            passwordHash = "synthetic-hash",
            restrictions = it,
            createdAtMillis = 1,
          ).isContentAllowed(
            seriesMetadata.ageRating,
            seriesMetadata.normalizedSharingLabels,
          )
        }

    private fun CatalogAccess.includesLibrary(id: LibraryId): Boolean {
      val allowedLibraryIds = libraryIds
      return allowedLibraryIds == null || id in allowedLibraryIds
    }

    private fun CatalogSearchCondition?.requireMembershipValue(
      field: CatalogSearchField,
    ): String {
      val predicate =
        this as? CatalogSearchCondition.Predicate
          ?: error("Expected a membership predicate")
      check(predicate.field == field) { "Unexpected membership field: ${predicate.field}" }
      check(predicate.operator == CatalogSearchOperator.IS) {
        "Unexpected membership operator: ${predicate.operator}"
      }
      return requireNotNull(predicate.value)
    }

    private fun <T> List<T>.toUnpagedCatalogPage(page: CatalogPageRequest): CatalogPage<T> =
      CatalogPage(
        content = this,
        page = 0,
        size = size.coerceAtLeast(1),
        totalElements = size.toLong(),
        unpaged = true,
        sorts = page.sorts,
      )
  }

  private class InMemorySeriesCollectionRepository(
    collections: Collection<SeriesCollection>,
  ) : SeriesCollectionRepository {
    private val values = collections.associateByTo(linkedMapOf(), SeriesCollection::id)
    var findByIdCalls = 0
      private set
    var insertCalls = 0
      private set
    var updateCalls = 0
      private set
    var deleteCalls = 0
      private set

    override fun findByIdOrNull(id: CollectionId): SeriesCollection? {
      findByIdCalls += 1
      return values[id]
    }

    fun snapshot(id: CollectionId): SeriesCollection? = values[id]

    override fun findAll(): List<SeriesCollection> = values.values.toList()

    override fun findAllBySeriesId(seriesId: SeriesId): List<SeriesCollection> =
      values.values.filter { seriesId in it.seriesIds }

    override fun findByNameIgnoreCaseOrNull(name: String): SeriesCollection? =
      values.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    override fun insert(collection: SeriesCollection) {
      insertCalls += 1
      values[collection.id] = collection
    }

    override fun update(collection: SeriesCollection) {
      updateCalls += 1
      values[collection.id] = collection
    }

    override fun delete(id: CollectionId) {
      deleteCalls += 1
      values.remove(id)
    }
  }

  private class InMemoryReadListRepository(
    readLists: Collection<ReadList>,
  ) : ReadListRepository {
    private val values = readLists.associateByTo(linkedMapOf(), ReadList::id)
    var findByIdCalls = 0
      private set
    var insertCalls = 0
      private set
    var updateCalls = 0
      private set
    var deleteCalls = 0
      private set

    override fun findByIdOrNull(id: ReadListId): ReadList? {
      findByIdCalls += 1
      return values[id]
    }

    fun snapshot(id: ReadListId): ReadList? = values[id]

    override fun findAll(): List<ReadList> = values.values.toList()

    override fun findAllByBookId(bookId: BookId): List<ReadList> =
      values.values.filter { bookId in it.bookIds }

    override fun findByNameIgnoreCaseOrNull(name: String): ReadList? =
      values.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    override fun insert(readList: ReadList) {
      insertCalls += 1
      values[readList.id] = readList
    }

    override fun update(readList: ReadList) {
      updateCalls += 1
      values[readList.id] = readList
    }

    override fun delete(id: ReadListId) {
      deleteCalls += 1
      values.remove(id)
    }
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
    ): Series? =
      values.values.firstOrNull {
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
    ): Book? =
      values.values.firstOrNull {
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
      values[user.id] = user
      return true
    }

    override fun update(user: User) {
      values[user.id] = user
    }

    override fun delete(id: UserId) {
      values.remove(id)
    }
  }

  private data class RouteSpec(
    val method: HttpMethod,
    val path: String,
    val forbiddenCode: String = "",
  )

  companion object {
    private const val COLLECTIONS_PATH = "$XOBORO_API_PREFIX/collections"
    private const val READ_LISTS_PATH = "$XOBORO_API_PREFIX/read-lists"
    private const val BLOCKED_LABEL = "blocked"
    private val ADMIN_USER_ID = UserId("admin-user")
    private val READER_USER_ID = UserId("reader-user")
    private val VISIBLE_LIBRARY_ID = LibraryId("library-visible")
    private val HIDDEN_LIBRARY_ID = LibraryId("library-hidden")
    private val SERIES_ONE = SeriesId("series-1")
    private val SERIES_THREE = SeriesId("series-3")
    private val SERIES_HIDDEN = SeriesId("series-hidden")
    private val SERIES_BLOCKED = SeriesId("series-blocked")
    private val BOOK_ONE = BookId("media-1")
    private val BOOK_THREE = BookId("media-3")
    private val BOOK_HIDDEN = BookId("media-hidden")
    private val BOOK_BLOCKED = BookId("media-blocked")
    private val VISIBLE_COLLECTION_ID = CollectionId("collection-visible")
    private val MIXED_COLLECTION_ID = CollectionId("collection-mixed")
    private val HIDDEN_COLLECTION_ID = CollectionId("collection-hidden")
    private val BLOCKED_COLLECTION_ID = CollectionId("collection-blocked")
    private val CREATED_COLLECTION_ID = CollectionId("collection-created")
    private val VISIBLE_READ_LIST_ID = ReadListId("read-list-visible")
    private val MIXED_READ_LIST_ID = ReadListId("read-list-mixed")
    private val HIDDEN_READ_LIST_ID = ReadListId("read-list-hidden")
    private val BLOCKED_READ_LIST_ID = ReadListId("read-list-blocked")
    private val CREATED_READ_LIST_ID = ReadListId("read-list-created")
    private const val COLLECTION_FORBIDDEN = "collection_administration_forbidden"
    private const val READ_LIST_FORBIDDEN = "read_list_administration_forbidden"
    private val MUTATION_ROUTES =
      listOf(
        RouteSpec(HttpMethod.Post, COLLECTIONS_PATH, COLLECTION_FORBIDDEN),
        RouteSpec(
          HttpMethod.Put,
          "$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}",
          COLLECTION_FORBIDDEN,
        ),
        RouteSpec(
          HttpMethod.Delete,
          "$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}",
          COLLECTION_FORBIDDEN,
        ),
        RouteSpec(HttpMethod.Post, READ_LISTS_PATH, READ_LIST_FORBIDDEN),
        RouteSpec(
          HttpMethod.Put,
          "$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}",
          READ_LIST_FORBIDDEN,
        ),
        RouteSpec(
          HttpMethod.Delete,
          "$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}",
          READ_LIST_FORBIDDEN,
        ),
      )
    private val NONEXISTENT_MUTATION_ROUTES =
      listOf(
        RouteSpec(HttpMethod.Put, "$COLLECTIONS_PATH/missing", COLLECTION_FORBIDDEN),
        RouteSpec(HttpMethod.Delete, "$COLLECTIONS_PATH/missing", COLLECTION_FORBIDDEN),
        RouteSpec(HttpMethod.Put, "$READ_LISTS_PATH/missing", READ_LIST_FORBIDDEN),
        RouteSpec(HttpMethod.Delete, "$READ_LISTS_PATH/missing", READ_LIST_FORBIDDEN),
      )
    private val ALL_ROUTES =
      listOf(
        RouteSpec(HttpMethod.Get, COLLECTIONS_PATH),
        RouteSpec(HttpMethod.Post, COLLECTIONS_PATH),
        RouteSpec(HttpMethod.Get, "$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}"),
        RouteSpec(HttpMethod.Put, "$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}"),
        RouteSpec(HttpMethod.Delete, "$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}"),
        RouteSpec(
          HttpMethod.Get,
          "$COLLECTIONS_PATH/${VISIBLE_COLLECTION_ID.value}/series",
        ),
        RouteSpec(HttpMethod.Get, READ_LISTS_PATH),
        RouteSpec(HttpMethod.Post, READ_LISTS_PATH),
        RouteSpec(HttpMethod.Get, "$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}"),
        RouteSpec(HttpMethod.Put, "$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}"),
        RouteSpec(HttpMethod.Delete, "$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}"),
        RouteSpec(
          HttpMethod.Get,
          "$READ_LISTS_PATH/${VISIBLE_READ_LIST_ID.value}/media-items",
        ),
        RouteSpec(
          HttpMethod.Get,
          "$XOBORO_API_PREFIX/series/${SERIES_ONE.value}/collections",
        ),
        RouteSpec(
          HttpMethod.Get,
          "$XOBORO_API_PREFIX/media-items/${BOOK_ONE.value}/read-lists",
        ),
      )

    private fun collectionCreation(
      name: String = "Created Collection",
      seriesIds: List<String> = listOf(SERIES_ONE.value),
    ): XoboroCollectionCreationRequest =
      XoboroCollectionCreationRequest(
        name = name,
        ordered = true,
        seriesIds = seriesIds,
      )

    private fun readListCreation(
      name: String = "Created Read List",
      mediaItemIds: List<String> = listOf(BOOK_ONE.value),
    ): XoboroReadListCreationRequest =
      XoboroReadListCreationRequest(
        name = name,
        summary = "Created summary",
        ordered = true,
        mediaItemIds = mediaItemIds,
      )

    private fun syntheticUser(
      id: UserId,
      email: String,
      roles: Set<UserRole>,
      sharesAllLibraries: Boolean = true,
      sharedLibraryIds: Set<LibraryId> = emptySet(),
      restrictions: ContentRestrictions = ContentRestrictions(),
    ): User =
      User(
        id = id,
        email = email,
        passwordHash = "synthetic-hash",
        roles = roles,
        sharedLibraryIds = sharedLibraryIds,
        sharesAllLibraries = sharesAllLibraries,
        restrictions = restrictions,
        createdAtMillis = 1,
      )

    private fun syntheticSeries(
      id: SeriesId,
      libraryId: LibraryId,
      labels: Set<String> = emptySet(),
    ): CatalogSeries {
      val series =
        Series(
          id = id,
          libraryId = libraryId,
          name = "Fixture ${id.value}",
          relativePath = "Fixture/${id.value}",
          sourceItemId = "file:///synthetic/${id.value}",
          fileModifiedAtMillis = 1,
          bookCount = 1,
          createdAtMillis = 1,
        )
      return CatalogSeries(
        series = series,
        metadata =
          SeriesMetadata(
            seriesId = id,
            title = "Fixture ${id.value}",
            sharingLabels = labels,
            createdAtMillis = 1,
          ),
        booksMetadata =
          BookMetadataAggregation(
            createdAtMillis = 1,
            updatedAtMillis = 1,
          ),
        readProgress = null,
      )
    }

    private fun syntheticBook(
      id: BookId,
      seriesId: SeriesId,
      libraryId: LibraryId,
      number: String,
      labels: Set<String>,
    ): CatalogBook {
      val seriesMetadata =
        SeriesMetadata(
          seriesId = seriesId,
          title = "Fixture $seriesId",
          sharingLabels = labels,
          createdAtMillis = 1,
        )
      return CatalogBook(
        book =
          Book(
            id = id,
            libraryId = libraryId,
            seriesId = seriesId,
            name = "Fixture ${id.value}.cbz",
            relativePath = "Fixture/${id.value}.cbz",
            sourceItemId = "file:///synthetic/${id.value}.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 1,
            fileSize = 100,
            number = number.toInt(),
            createdAtMillis = 1,
          ),
        seriesTitle = seriesMetadata.title,
        seriesMetadata = seriesMetadata,
        metadata =
          BookMetadata(
            bookId = id,
            title = "Fixture ${id.value}",
            number = number,
            numberSort = number.toFloat(),
            createdAtMillis = 1,
          ),
        media = null,
        readProgress = null,
      )
    }
  }
}
