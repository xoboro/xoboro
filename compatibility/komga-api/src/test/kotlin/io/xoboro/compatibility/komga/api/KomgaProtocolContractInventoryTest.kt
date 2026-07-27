package io.xoboro.compatibility.komga.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KomgaProtocolContractInventoryTest {
  @Test
  fun `pins every non-OpenAPI protocol operation`() {
    assertEquals(18, OPDS_V1.size)
    assertEquals(30, OPDS_V2.size)
    assertEquals(15, KOBO.size)
    assertEquals(4, KOREADER.size)
    assertEquals(1, SSE.size)
    assertEquals(2, OAUTH_BROWSER.size)

    val all = OPDS_V1 + OPDS_V2 + KOBO + KOREADER + SSE + OAUTH_BROWSER
    assertEquals(70, all.size)
    assertEquals(70, all.toSet().size)
    assertEquals(70, PARTIALLY_IMPLEMENTED.size)
    assertTrue(PARTIALLY_IMPLEMENTED.all(all::contains))
    assertTrue(all.none { endpoint -> endpoint.path.any { it in '\uAC00'..'\uD7A3' } })
  }

  private data class ProtocolEndpoint(
    val method: String,
    val path: String,
  )

  private companion object {
    fun get(path: String) = ProtocolEndpoint("GET", path)

    fun post(path: String) = ProtocolEndpoint("POST", path)

    fun put(path: String) = ProtocolEndpoint("PUT", path)

    fun patch(path: String) = ProtocolEndpoint("PATCH", path)

    fun delete(path: String) = ProtocolEndpoint("DELETE", path)

    val OPDS_V1 =
      setOf(
        get("/opds/v1.2/catalog"),
        get("/opds/v1.2/search"),
        get("/opds/v1.2/ondeck"),
        get("/opds/v1.2/keep-reading"),
        get("/opds/v1.2/series"),
        get("/opds/v1.2/series/latest"),
        get("/opds/v1.2/books/latest"),
        get("/opds/v1.2/libraries"),
        get("/opds/v1.2/collections"),
        get("/opds/v1.2/readlists"),
        get("/opds/v1.2/publishers"),
        get("/opds/v1.2/series/{id}"),
        get("/opds/v1.2/libraries/{id}"),
        get("/opds/v1.2/collections/{id}"),
        get("/opds/v1.2/readlists/{id}"),
        get("/opds/v1.2/books/{bookId}/thumbnail"),
        get("/opds/v1.2/books/{bookId}/thumbnail/small"),
        get("/opds/v1.2/books/{bookId}/pages/{pageNumber}"),
      )

    val OPDS_V2 =
      setOf(
        get("/opds/v2/catalog"),
        get("/opds/v2/libraries"),
        get("/opds/v2/libraries/{id}"),
        get("/opds/v2/libraries/keep-reading"),
        get("/opds/v2/libraries/{id}/keep-reading"),
        get("/opds/v2/libraries/on-deck"),
        get("/opds/v2/libraries/{id}/on-deck"),
        get("/opds/v2/libraries/books/latest"),
        get("/opds/v2/libraries/{id}/books/latest"),
        get("/opds/v2/libraries/series/latest"),
        get("/opds/v2/libraries/{id}/series/latest"),
        get("/opds/v2/libraries/browse"),
        get("/opds/v2/libraries/{id}/browse"),
        get("/opds/v2/libraries/collections"),
        get("/opds/v2/libraries/{id}/collections"),
        get("/opds/v2/collections/{id}"),
        get("/opds/v2/libraries/readlists"),
        get("/opds/v2/libraries/{id}/readlists"),
        get("/opds/v2/readlists/{id}"),
        get("/opds/v2/series/{id}"),
        get("/opds/v2/search"),
        get("/opds/v2/auth"),
        get("/opds/v2/books/{bookId}/pages/{pageNumber}"),
        get("/opds/v2/books/{bookId}/thumbnail"),
        get("/opds/v2/books/{bookId}/progression"),
        put("/opds/v2/books/{bookId}/progression"),
        get("/opds/v2/books/{bookId}/manifest"),
        get("/opds/v2/books/{bookId}/manifest/epub"),
        get("/opds/v2/books/{bookId}/manifest/pdf"),
        get("/opds/v2/books/{bookId}/manifest/divina"),
      )

    val KOBO =
      setOf(
        get("/kobo/{authToken}/ping"),
        get("/kobo/{authToken}/v1/initialization"),
        post("/kobo/{authToken}/v1/auth/device"),
        get("/kobo/{authToken}/v1/library/sync"),
        get("/kobo/{authToken}/v1/library/{bookId}/metadata"),
        get("/kobo/{authToken}/v1/library/{bookId}/state"),
        put("/kobo/{authToken}/v1/library/{bookId}/state"),
        get("/kobo/{authToken}/v1/books/{bookId}/file/epub"),
        get("/kobo/{authToken}/v1/books/{thumbnailId}/thumbnail/{width}/{height}/{isGreyScale}/image.jpg"),
        get("/kobo/{authToken}/v1/books/{thumbnailId}/thumbnail/{width}/{height}/{quality}/{isGreyScale}/image.jpg"),
        get("/kobo/{authToken}/{*path}"),
        post("/kobo/{authToken}/{*path}"),
        put("/kobo/{authToken}/{*path}"),
        patch("/kobo/{authToken}/{*path}"),
        delete("/kobo/{authToken}/{*path}"),
      )

    val KOREADER =
      setOf(
        post("/koreader/users/create"),
        get("/koreader/users/auth"),
        get("/koreader/syncs/progress/{bookHash}"),
        put("/koreader/syncs/progress"),
      )

    val SSE = setOf(get("/sse/v1/events"))

    val OAUTH_BROWSER =
      setOf(
        get("/oauth2/authorization/{registrationId}"),
        get("/login/oauth2/code/{registrationId}"),
      )

    val PARTIALLY_IMPLEMENTED =
      OPDS_V1 + OPDS_V2 + KOBO + KOREADER + SSE + OAUTH_BROWSER
  }
}
