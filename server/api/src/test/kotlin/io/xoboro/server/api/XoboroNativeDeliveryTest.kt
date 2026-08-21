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
import io.xoboro.core.application.TaskStoreUnavailableException
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
import io.xoboro.core.domain.MediaPosition
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
import kotlin.test.assertNull
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
  fun `page manifest prioritizes analysis instead of rendering an empty comic`() =
    testApplication {
      val fixture = Fixture.nonEpub(MediaStatus.UNKNOWN)
      installDelivery(fixture)

      val response = client.get(PAGES_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("media_not_ready", response.body<XoboroApiError>().code)
      assertEquals(listOf(MEDIA_ID), fixture.prioritizedBookIds)
      assertEquals(0, fixture.content.pagesCallCount)
    }

  @Test
  fun `page manifest stays retryable while its priority update is locked`() =
    testApplication {
      val fixture = Fixture.nonEpub(MediaStatus.UNKNOWN)
      fixture.priorityFailure = TaskStoreUnavailableException("ANALYZE_BOOK_media-delivery")
      installDelivery(fixture)

      val response = client.get(PAGES_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("media_not_ready", response.body<XoboroApiError>().code)
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

  /**
   * A client that cannot tell "not yet" from "never" retries forever against a book that will not
   * open. Sits next to the ERROR case above because the value is in the two codes differing.
   */
  @Test
  fun `media that can never be read returns a distinct conflict code`() =
    testApplication {
      val fixture = Fixture.visible(MediaStatus.UNSUPPORTED)
      installDelivery(fixture)

      val response = client.get(PAGE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("media_unsupported", response.body<XoboroApiError>().code)
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

  @Test
  fun `resource bytes preserve manifest path security policy and response semantics`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get(RESOURCE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(RESOURCE_BYTES.decodeToString(), response.bodyAsText())
      assertEquals("OEBPS/text/chapter-1.xhtml", fixture.content.lastResourcePath)
      assertEquals("application/xhtml+xml", response.headers[HttpHeaders.ContentType])
      val policy = assertNotNull(response.headers["Content-Security-Policy"])
      assertTrue("script-src 'none'" in policy)
      assertTrue("object-src 'none'" in policy)
      assertNull(response.headers[HttpHeaders.ContentDisposition])
      assertEquals(1, fixture.content.openResourceCallCount)
      assertTrue(assertNotNull(fixture.content.lastResourceStream).closed)
    }

  @Test
  fun `malformed resource media type falls back to octet stream`() =
    testApplication {
      val fixture = Fixture.visible()
      fixture.content.resourceStreamFactory = {
        FakeMediaContentStream(RESOURCE_BYTES, "not a media type")
      }
      installDelivery(fixture)

      val response = client.get(RESOURCE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(
        "application/octet-stream",
        response.headers[HttpHeaders.ContentType],
      )
      assertEquals(RESOURCE_BYTES.decodeToString(), response.bodyAsText())
      assertTrue(assertNotNull(fixture.content.lastResourceStream).closed)
    }

  @Test
  fun `non epub resource request returns not found without content access`() =
    testApplication {
      val fixture = Fixture.nonEpub()
      installDelivery(fixture)

      val response = client.get(RESOURCE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("resource_not_found", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.content.openResourceCallCount)
    }

  @Test
  fun `unknown and invalid resource paths return resource not found`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val unknown =
        client.get("$RESOURCES_PATH/OEBPS/text/missing.xhtml") {
          bearerAuth(fixture.token)
        }

      assertEquals(HttpStatusCode.NotFound, unknown.status)
      assertEquals("resource_not_found", unknown.body<XoboroApiError>().code)
      assertEquals(1, fixture.content.openResourceCallCount)

      fixture.content.resourceFailure = IllegalArgumentException("Synthetic resource failure")
      val invalid = client.get(RESOURCE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, invalid.status)
      assertEquals("resource_not_found", invalid.body<XoboroApiError>().code)
      assertEquals(2, fixture.content.openResourceCallCount)
    }

  @Test
  fun `blank resource tail returns not found without content access`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get("$RESOURCES_PATH/") { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("resource_not_found", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.content.openResourceCallCount)
    }

  @Test
  fun `resource entity tag revalidates matching content and rejects stale validators`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)
      val initial = client.get(RESOURCE_PATH) { bearerAuth(fixture.token) }
      val entityTag = assertNotNull(initial.headers[HttpHeaders.ETag])

      val matching =
        client.get(RESOURCE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.IfNoneMatch, entityTag)
        }
      val stale =
        client.get(RESOURCE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.IfNoneMatch, "\"stale\"")
        }

      assertEquals(HttpStatusCode.NotModified, matching.status)
      assertEquals("", matching.bodyAsText())
      assertEquals(HttpStatusCode.OK, stale.status)
      assertEquals(RESOURCE_BYTES.decodeToString(), stale.bodyAsText())
      assertEquals(3, fixture.content.openResourceCallCount)
      assertTrue(assertNotNull(fixture.content.lastResourceStream).closed)
    }

  @Test
  fun `restricted user cannot open resource bytes`() =
    testApplication {
      val fixture = Fixture.restricted()
      installDelivery(fixture)

      val response = client.get(RESOURCE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.catalog.findByIdCallCount)
      assertEquals(0, fixture.content.openResourceCallCount)
    }

  @Test
  fun `page streaming role precedes catalog lookup for resource bytes`() =
    testApplication {
      val fixture = Fixture.roleLess()
      installDelivery(fixture)

      val response = client.get(RESOURCE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("page_streaming_forbidden", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.catalog.findByIdCallCount)
      assertEquals(0, fixture.content.openResourceCallCount)
    }

  @Test
  fun `full file download streams validators ranges and conformant disposition`() =
    testApplication {
      val fixture = Fixture.downloadable()
      installDelivery(fixture)

      val response = client.get(FILE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(FILE_BYTES.decodeToString(), response.bodyAsText())
      assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
      assertEquals(
        "W/\"${FILE_BYTES.size}-2\"",
        response.headers[HttpHeaders.ETag],
      )
      assertNotNull(response.headers[HttpHeaders.LastModified])
      assertEquals(FILE_BYTES.size.toString(), response.headers[HttpHeaders.ContentLength])
      val disposition = assertNotNull(response.headers[HttpHeaders.ContentDisposition])
      assertTrue("attachment" in disposition)
      assertTrue("filename=\"Synthetic delivery.epub\"" in disposition)
      assertTrue("filename*=UTF-8''Synthetic%20delivery.epub" in disposition)
      assertFalse("=?UTF-8?Q?" in disposition)
      assertEquals(1, fixture.content.openBookCallCount)
      assertTrue(assertNotNull(fixture.content.lastBookStream).closed)
    }

  @Test
  fun `non ascii download name has ascii fallback and utf8 extended filename`() =
    testApplication {
      val fixture = Fixture.downloadable(name = "Synthetic cafe\u0301 \u2603.epub")
      installDelivery(fixture)

      val response = client.get(FILE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      val disposition = assertNotNull(response.headers[HttpHeaders.ContentDisposition])
      assertTrue("filename=\"Synthetic cafe_ _.epub\"" in disposition)
      assertTrue(
        "filename*=UTF-8''Synthetic%20cafe%CC%81%20%E2%98%83.epub" in disposition,
      )
      assertTrue(disposition.all { character -> character.code in 0x20..0x7e })
      assertFalse("=?UTF-8?Q?" in disposition)
    }

  @Test
  fun `fixed file range returns exact partial bytes and closes stream`() =
    testApplication {
      val fixture = Fixture.downloadable()
      installDelivery(fixture)

      val response =
        client.get(FILE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.Range, "bytes=2-5")
        }

      assertEquals(HttpStatusCode.PartialContent, response.status)
      assertEquals("bytes 2-5/${FILE_BYTES.size}", response.headers[HttpHeaders.ContentRange])
      assertEquals("nthe", response.bodyAsText())
      assertEquals("4", response.headers[HttpHeaders.ContentLength])
      assertTrue(assertNotNull(fixture.content.lastBookStream).closed)
    }

  @Test
  fun `suffix and open ended file ranges return requested bytes`() =
    testApplication {
      val fixture = Fixture.downloadable()
      installDelivery(fixture)

      val suffix =
        client.get(FILE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.Range, "bytes=-3")
        }
      val openEnded =
        client.get(FILE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.Range, "bytes=10-")
        }

      assertEquals(HttpStatusCode.PartialContent, suffix.status)
      assertEquals(
        "bytes ${FILE_BYTES.size - 3}-${FILE_BYTES.size - 1}/${FILE_BYTES.size}",
        suffix.headers[HttpHeaders.ContentRange],
      )
      assertEquals(FILE_BYTES.takeLast(3).toByteArray().decodeToString(), suffix.bodyAsText())
      assertEquals(HttpStatusCode.PartialContent, openEnded.status)
      assertEquals(FILE_BYTES.copyOfRange(10, FILE_BYTES.size).decodeToString(), openEnded.bodyAsText())
      assertEquals(2, fixture.content.openBookCallCount)
      assertTrue(assertNotNull(fixture.content.lastBookStream).closed)
    }

  @Test
  fun `unsatisfiable file range returns 416 and closes stream`() =
    testApplication {
      val fixture = Fixture.downloadable()
      installDelivery(fixture)

      val response =
        client.get(FILE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.Range, "bytes=999999-")
        }

      assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
      assertEquals("bytes */${FILE_BYTES.size}", response.headers[HttpHeaders.ContentRange])
      assertEquals("", response.bodyAsText())
      assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
      assertTrue(assertNotNull(fixture.content.lastBookStream).closed)
    }

  @Test
  fun `multiple and malformed file ranges serve the complete body`() =
    testApplication {
      val fixture = Fixture.downloadable()
      installDelivery(fixture)

      for (range in listOf("bytes=0-1,4-5", "items=0-1", "bytes=broken")) {
        val response =
          client.get(FILE_PATH) {
            bearerAuth(fixture.token)
            header(HttpHeaders.Range, range)
          }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(FILE_BYTES.decodeToString(), response.bodyAsText())
        assertNull(response.headers[HttpHeaders.ContentRange])
        assertTrue(assertNotNull(fixture.content.lastBookStream).closed)
      }
      assertEquals(3, fixture.content.openBookCallCount)
    }

  @Test
  fun `if range mismatch serves full body while matching validators preserve range`() =
    testApplication {
      val fixture = Fixture.downloadable()
      installDelivery(fixture)
      val initial = client.get(FILE_PATH) { bearerAuth(fixture.token) }
      val entityTag = assertNotNull(initial.headers[HttpHeaders.ETag])
      val lastModified = assertNotNull(initial.headers[HttpHeaders.LastModified])

      val mismatch =
        client.get(FILE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.Range, "bytes=2-5")
          header(HttpHeaders.IfRange, "W/\"stale\"")
        }
      val matchingTag =
        client.get(FILE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.Range, "bytes=2-5")
          header(HttpHeaders.IfRange, entityTag.removePrefix("W/"))
        }
      val matchingDate =
        client.get(FILE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.Range, "bytes=2-5")
          header(HttpHeaders.IfRange, lastModified)
        }

      assertEquals(HttpStatusCode.OK, mismatch.status)
      assertEquals(FILE_BYTES.decodeToString(), mismatch.bodyAsText())
      assertEquals(HttpStatusCode.PartialContent, matchingTag.status)
      assertEquals("nthe", matchingTag.bodyAsText())
      assertEquals(HttpStatusCode.PartialContent, matchingDate.status)
      assertEquals("nthe", matchingDate.bodyAsText())
      assertEquals(4, fixture.content.openBookCallCount)
      assertTrue(assertNotNull(fixture.content.lastBookStream).closed)
    }

  @Test
  fun `matching file entity tag returns 304 before opening content`() =
    testApplication {
      val fixture = Fixture.downloadable()
      installDelivery(fixture)

      val response =
        client.get(FILE_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.IfNoneMatch, "\"${FILE_BYTES.size}-2\"")
        }

      assertEquals(HttpStatusCode.NotModified, response.status)
      assertEquals("", response.bodyAsText())
      assertEquals("W/\"${FILE_BYTES.size}-2\"", response.headers[HttpHeaders.ETag])
      assertEquals(0, fixture.content.openBookCallCount)
      assertNull(response.headers[HttpHeaders.AcceptRanges])
    }

  @Test
  fun `file download role precedes catalog lookup`() =
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get(FILE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("file_download_forbidden", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.catalog.findByIdCallCount)
      assertEquals(0, fixture.content.openBookCallCount)
    }

  @Test
  fun `restricted user cannot open original file`() =
    testApplication {
      val fixture = Fixture.downloadRestricted()
      installDelivery(fixture)

      val response = client.get(FILE_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.catalog.findByIdCallCount)
      assertEquals(0, fixture.content.openBookCallCount)
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
        exception<TaskStoreUnavailableException> { call, _ ->
          call.respond(
            HttpStatusCode.InternalServerError,
            XoboroApiError("internal_error", "Task store unavailable"),
          )
        }
      }
      routing {
        xoboroNativeDeliveryRoutes(
          fixture.catalog,
          fixture.content,
          prioritizeAnalysis = {
            fixture.priorityFailure?.let { failure -> throw failure }
            fixture.prioritizedBookIds += it
          },
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
    mediaKind: MediaKind = MediaKind.EPUB,
    name: String = "Synthetic delivery.epub",
  ) {
    private val users = InMemoryUserRepository(user)
    private val book = syntheticBook(status, mediaKind, name)
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory = { "delivery-token" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val token = requireNotNull(sessions.create(user)).plainToken
    val catalog = RecordingCatalog(book)
    val content = FakeBookContentAccess()
    val prioritizedBookIds = mutableListOf<BookId>()
    var priorityFailure: TaskStoreUnavailableException? = null

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

      fun nonEpub(status: MediaStatus = MediaStatus.READY): Fixture =
        Fixture(
          user =
            syntheticUser(
              roles = setOf(UserRole.PAGE_STREAMING),
              sharesAllLibraries = true,
            ),
          status = status,
          mediaKind = MediaKind.COMIC_ARCHIVE,
        )

      fun downloadable(name: String = "Synthetic delivery.epub"): Fixture =
        Fixture(
          user =
            syntheticUser(
              roles = setOf(UserRole.FILE_DOWNLOAD),
              sharesAllLibraries = true,
            ),
          status = MediaStatus.READY,
          name = name,
        )

      fun downloadRestricted(): Fixture =
        Fixture(
          user =
            syntheticUser(
              roles = setOf(UserRole.FILE_DOWNLOAD),
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
    var openResourceCallCount = 0
      private set
    var lastResourcePath: String? = null
      private set
    var lastResourceStream: FakeMediaContentStream? = null
      private set
    var openBookCallCount = 0
      private set
    var lastBookStream: FakeMediaContentStream? = null
      private set
    var failure: IllegalArgumentException? = null
    var resourceFailure: IllegalArgumentException? = null
    var bookFailure: IllegalArgumentException? = null
    var streamFactory: () -> FakeMediaContentStream? = {
      FakeMediaContentStream(PAGE_BYTES)
    }
    var resourceStreamFactory: (String) -> FakeMediaContentStream? = { resource ->
      if (resource == RESOURCE_ARCHIVE_PATH) {
        FakeMediaContentStream(RESOURCE_BYTES, "application/xhtml+xml")
      } else {
        null
      }
    }
    var bookStreamFactory: () -> FakeMediaContentStream? = {
      FakeMediaContentStream(FILE_BYTES, "application/epub+zip")
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

    override fun openBook(bookId: BookId): MediaContentStream? {
      openBookCallCount += 1
      bookFailure?.let { throw it }
      return bookStreamFactory().also { lastBookStream = it }
    }

    override fun openResource(
      bookId: BookId,
      resource: String,
    ): MediaContentStream? {
      openResourceCallCount += 1
      lastResourcePath = resource
      resourceFailure?.let { throw it }
      return resourceStreamFactory(resource).also { lastResourceStream = it }
    }
  }

  private class FakeMediaContentStream(
    private val bytes: ByteArray,
    override val mediaType: String = "image/png",
  ) : MediaContentStream {
    private var position = 0
    var closed = false
      private set
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

  @Test
  fun `positions are returned in reading order`() {
    // The resource manifest is in OPF manifest order - the order the packager happened
    // to write - so an EPUB reader that followed it would show chapters in whatever
    // sequence the file was built in. Positions come from the spine, and this is the
    // only response that carries reading order.
    //
    // The ordering itself is a domain invariant rather than something this route
    // arranges: BookMedia refuses positions that are not exactly 1..n in sequence.
    testApplication {
      val fixture = Fixture.visible()
      installDelivery(fixture)

      val response = client.get(POSITIONS_PATH) { bearerAuth(fixture.token) }
      assertEquals(HttpStatusCode.OK, response.status)

      val positions = response.body<List<XoboroMediaPositionResponse>>()
      assertEquals(listOf(1, 2), positions.map(XoboroMediaPositionResponse::position))
      // The whole ordered list, not just its first entry. The previous version asserted
      // the first href twice - RESOURCE_ARCHIVE_PATH is that same literal - so it looked
      // like two checks and was one.
      //
      // The second href sorts lexically *before* the first - see the fixture's positions
      // - so a route that sorted by href instead of returning spine order would answer
      // `aaa-out-of-lexical-order.xhtml` first here, not `chapter-1.xhtml`. The earlier
      // fixture used `chapter-1.xhtml` then `chapter-2.xhtml`, where the two orders agree
      // and a sort could not have been told apart from no sort at all.
      assertEquals(
        listOf("OEBPS/text/chapter-1.xhtml", "OEBPS/text/aaa-out-of-lexical-order.xhtml"),
        positions.map(XoboroMediaPositionResponse::href),
      )
      // The href is what the resource route is asked for verbatim, so it has to match a
      // manifest path exactly rather than being an OPF-relative href.
      assertEquals(RESOURCE_ARCHIVE_PATH, positions.first().href)
      // `(position - 1) / count`, the Readium convention: a position reports where it
      // starts, so the publication opens at zero and nothing reports 1. Pinned because a
      // reader copies these into a locator - see the DTO's documentation and ADR 0106.
      assertEquals(listOf(0F, 0.5F), positions.map(XoboroMediaPositionResponse::totalProgression))
    }
  }

  @Test
  fun `positions distinguish an unanalyzed EPUB from one without positions`() {
    // An empty list means "this is not an EPUB". Answering it for an EPUB that has not
    // been analyzed yet told a reader the book has no content, when the truth is that
    // its content is not known yet - and the two are the difference between rendering an
    // empty book and waiting. Gated exactly as `/pages/{pageNumber}` is.
    testApplication {
      val fixture = Fixture.visible(MediaStatus.OUTDATED)
      installDelivery(fixture)

      val response = client.get(POSITIONS_PATH) { bearerAuth(fixture.token) }
      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("media_not_ready", response.body<XoboroApiError>().code)
    }
  }

  @Test
  fun `positions do not refuse a comic whose media is not ready`() {
    // The readiness gate is scoped to EPUBs on purpose, and this is the case that says so.
    // A comic has pages rather than positions, so an empty list is the right answer whatever
    // its analysis state - refusing with `409` would make a reader retry for content that
    // is never going to exist.
    //
    // Without this, deleting `item.book.mediaKind == MediaKind.EPUB &&` from the guard
    // passed every other positions test: all of them use an EPUB fixture, where the
    // condition is true and therefore invisible.
    testApplication {
      val fixture = Fixture.nonEpub(MediaStatus.OUTDATED)
      installDelivery(fixture)

      val response = client.get(POSITIONS_PATH) { bearerAuth(fixture.token) }
      // The status is the assertion, not the body. This fixture attaches positions to a
      // media record whatever its kind, which real analysis does not do - so asserting an
      // empty list here would be asserting the fixture. What the route owes a comic is
      // "not a 409", and that is what the deleted condition changes.
      assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }
  }

  @Test
  fun `positions report an unreadable EPUB as unsupported`() {
    testApplication {
      val fixture = Fixture.visible(MediaStatus.UNSUPPORTED)
      installDelivery(fixture)

      val response = client.get(POSITIONS_PATH) { bearerAuth(fixture.token) }
      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("media_unsupported", response.body<XoboroApiError>().code)
    }
  }

  @Test
  fun `positions require the page streaming role`() {
    testApplication {
      // FILE_DOWNLOAD without PAGE_STREAMING: allowed to download the original file,
      // not to stream what is inside it.
      val fixture = Fixture.downloadable()
      installDelivery(fixture)

      val response = client.get(POSITIONS_PATH) { bearerAuth(fixture.token) }
      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("page_streaming_forbidden", response.body<XoboroApiError>().code)
    }
  }

  @Test
  fun `positions hide an unauthorized media item behind a not found`() {
    // Same answer as a missing item, so a grant cannot be probed by the difference.
    testApplication {
      val fixture = Fixture.restricted()
      installDelivery(fixture)

      val response = client.get(POSITIONS_PATH) { bearerAuth(fixture.token) }
      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
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
    private val RESOURCE_BYTES = "<p>Synthetic chapter</p>".encodeToByteArray()
    private val FILE_BYTES = "synthetic-original-file".encodeToByteArray()
    private const val RESOURCE_ARCHIVE_PATH = "OEBPS/text/chapter-1.xhtml"
    private const val PAGES_PATH = "$XOBORO_API_PREFIX/media-items/media-delivery/pages"
    private const val PAGE_PATH = "$PAGES_PATH/1"
    private const val RESOURCES_PATH =
      "$XOBORO_API_PREFIX/media-items/media-delivery/resources"
    private const val RESOURCE_PATH = "$RESOURCES_PATH/$RESOURCE_ARCHIVE_PATH"
    private const val POSITIONS_PATH =
      "$XOBORO_API_PREFIX/media-items/media-delivery/positions"
    private const val FILE_PATH = "$XOBORO_API_PREFIX/media-items/media-delivery/file"

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

    private fun syntheticBook(
      status: MediaStatus,
      mediaKind: MediaKind,
      name: String,
    ): CatalogBook {
      val book =
        Book(
          id = MEDIA_ID,
          libraryId = HIDDEN_LIBRARY_ID,
          seriesId = SERIES_ID,
          name = name,
          relativePath = "Synthetic delivery/Synthetic delivery.epub",
          sourceItemId = "file:///synthetic/delivery/book.epub",
          mediaKind = mediaKind,
          fileModifiedAtMillis = 2,
          fileSize = FILE_BYTES.size.toLong(),
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
            // In order, because BookMedia rejects anything else: positions must be
            // exactly 1..n in sequence. That invariant is why the route does not sort.
            //
            // The second href sorts lexically *before* the first one ("aaa-..." precedes
            // "chapter-1..."), so spine order and lexical href order disagree here on
            // purpose. Reusing `chapter-1.xhtml` then `chapter-2.xhtml`, as an earlier
            // version of this fixture did, made the two orders coincide, so a route that
            // sorted positions by href instead of returning them as stored answered this
            // test correctly by accident.
            //
            // totalProgression is 0 then 0.5, which is `(position - 1) / count` - what
            // EpubMediaAnalyzer computes since ADR 0106. It read 0.5 then 1.0 while the
            // analyzer used `position / count`; the fixture has to be the shape the server
            // actually sends, or the route test describes an API that does not exist.
            positions =
              listOf(
                MediaPosition(
                  href = "OEBPS/text/chapter-1.xhtml",
                  mediaType = "application/xhtml+xml",
                  progression = 0F,
                  position = 1,
                  totalProgression = 0F,
                ),
                MediaPosition(
                  href = "OEBPS/text/aaa-out-of-lexical-order.xhtml",
                  mediaType = "application/xhtml+xml",
                  progression = 0F,
                  position = 2,
                  totalProgression = 0.5F,
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
