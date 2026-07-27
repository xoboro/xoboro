package io.xoboro.compatibility.komga.api

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KomgaRestContractInventoryTest {
  @Test
  fun `pins the complete Komga 1_25_0 REST contract`() {
    val bytes =
      requireNotNull(javaClass.getResourceAsStream(OPENAPI_RESOURCE)) {
        "Missing Komga REST contract snapshot"
      }.use { it.readAllBytes() }
    val document = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    val paths = requireNotNull(document["paths"]).jsonObject
    val schemas =
      requireNotNull(
        requireNotNull(document["components"]).jsonObject["schemas"],
      ).jsonObject
    val endpoints =
      paths.flatMap { (path, rawPathItem) ->
        rawPathItem.jsonObject.keys
          .filter(HTTP_METHODS::contains)
          .map { method -> RestEndpoint(method.uppercase(), path) }
      }.toSet()
    val version =
      requireNotNull(
        requireNotNull(document["info"]).jsonObject["version"],
      ).jsonPrimitive.content

    assertEquals(KOMGA_VERSION, version)
    assertEquals(OPENAPI_SHA256, bytes.sha256())
    assertEquals(130, paths.size)
    assertEquals(165, endpoints.size)
    assertEquals(167, schemas.size)
    assertEquals(137, PARTIALLY_IMPLEMENTED_ENDPOINTS.size)
    assertTrue(endpoints.containsAll(PARTIALLY_IMPLEMENTED_ENDPOINTS))
    assertEquals(28, endpoints.minus(PARTIALLY_IMPLEMENTED_ENDPOINTS).size)
    assertTrue(bytes.decodeToString().none { it in '\uAC00'..'\uD7A3' })
  }

  private fun ByteArray.sha256(): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(this)
      .joinToString("") { byte -> "%02x".format(byte) }

  private data class RestEndpoint(
    val method: String,
    val path: String,
  )

  private companion object {
    const val KOMGA_VERSION = "1.25.0"
    const val OPENAPI_RESOURCE = "/contracts/komga-1.25.0-openapi.json"
    const val OPENAPI_SHA256 = "5a24d10252fc0932e66ecf8a6d86ea9386522b8aef039a2b7d702799a1f12935"
    val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options")
    val PARTIALLY_IMPLEMENTED_ENDPOINTS =
      setOf(
        RestEndpoint("GET", "/api/logout"),
        RestEndpoint("POST", "/api/logout"),
        RestEndpoint("GET", "/api/v1/announcements"),
        RestEndpoint("PUT", "/api/v1/announcements"),
        RestEndpoint("GET", "/api/v1/claim"),
        RestEndpoint("POST", "/api/v1/claim"),
        RestEndpoint("GET", "/api/v1/login/set-cookie"),
        RestEndpoint("GET", "/api/v1/age-ratings"),
        RestEndpoint("GET", "/api/v1/authors"),
        RestEndpoint("GET", "/api/v1/authors/names"),
        RestEndpoint("GET", "/api/v1/authors/roles"),
        RestEndpoint("GET", "/api/v1/books"),
        RestEndpoint("GET", "/api/v1/books/duplicates"),
        RestEndpoint("PATCH", "/api/v1/books/metadata"),
        RestEndpoint("GET", "/api/v1/books/latest"),
        RestEndpoint("POST", "/api/v1/books/list"),
        RestEndpoint("GET", "/api/v1/books/ondeck"),
        RestEndpoint("GET", "/api/v1/books/{bookId}"),
        RestEndpoint("POST", "/api/v1/books/{bookId}/analyze"),
        RestEndpoint("PATCH", "/api/v1/books/{bookId}/metadata"),
        RestEndpoint("POST", "/api/v1/books/{bookId}/metadata/refresh"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/file"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/file/*"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/manifest"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/manifest/divina"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/next"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/pages"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/pages/{pageNumber}"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/pages/{pageNumber}/raw"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/pages/{pageNumber}/thumbnail"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/previous"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/progression"),
        RestEndpoint("PUT", "/api/v1/books/{bookId}/progression"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/thumbnail"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/thumbnails"),
        RestEndpoint("POST", "/api/v1/books/{bookId}/thumbnails"),
        RestEndpoint("DELETE", "/api/v1/books/{bookId}/thumbnails/{thumbnailId}"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/thumbnails/{thumbnailId}"),
        RestEndpoint("PUT", "/api/v1/books/{bookId}/thumbnails/{thumbnailId}/selected"),
        RestEndpoint("DELETE", "/api/v1/books/{bookId}/read-progress"),
        RestEndpoint("PATCH", "/api/v1/books/{bookId}/read-progress"),
        RestEndpoint("GET", "/api/v1/books/{bookId}/readlists"),
        RestEndpoint("GET", "/api/v1/collections"),
        RestEndpoint("POST", "/api/v1/collections"),
        RestEndpoint("DELETE", "/api/v1/collections/{id}"),
        RestEndpoint("GET", "/api/v1/collections/{id}"),
        RestEndpoint("PATCH", "/api/v1/collections/{id}"),
        RestEndpoint("GET", "/api/v1/collections/{id}/series"),
        RestEndpoint("GET", "/api/v1/collections/{id}/thumbnail"),
        RestEndpoint("GET", "/api/v1/collections/{id}/thumbnails"),
        RestEndpoint("POST", "/api/v1/collections/{id}/thumbnails"),
        RestEndpoint("DELETE", "/api/v1/collections/{id}/thumbnails/{thumbnailId}"),
        RestEndpoint("GET", "/api/v1/collections/{id}/thumbnails/{thumbnailId}"),
        RestEndpoint("PUT", "/api/v1/collections/{id}/thumbnails/{thumbnailId}/selected"),
        RestEndpoint("GET", "/api/v1/genres"),
        RestEndpoint("GET", "/api/v1/languages"),
        RestEndpoint("GET", "/api/v1/libraries"),
        RestEndpoint("POST", "/api/v1/libraries"),
        RestEndpoint("GET", "/api/v1/libraries/{libraryId}"),
        RestEndpoint("PATCH", "/api/v1/libraries/{libraryId}"),
        RestEndpoint("PUT", "/api/v1/libraries/{libraryId}"),
        RestEndpoint("DELETE", "/api/v1/libraries/{libraryId}"),
        RestEndpoint("POST", "/api/v1/libraries/{libraryId}/analyze"),
        RestEndpoint("POST", "/api/v1/libraries/{libraryId}/empty-trash"),
        RestEndpoint("POST", "/api/v1/libraries/{libraryId}/metadata/refresh"),
        RestEndpoint("POST", "/api/v1/libraries/{libraryId}/scan"),
        RestEndpoint("GET", "/api/v1/oauth2/providers"),
        RestEndpoint("GET", "/api/v1/page-hashes"),
        RestEndpoint("PUT", "/api/v1/page-hashes"),
        RestEndpoint("GET", "/api/v1/page-hashes/unknown"),
        RestEndpoint("GET", "/api/v1/page-hashes/unknown/{pageHash}/thumbnail"),
        RestEndpoint("GET", "/api/v1/page-hashes/{pageHash}"),
        RestEndpoint("GET", "/api/v1/page-hashes/{pageHash}/thumbnail"),
        RestEndpoint("GET", "/api/v1/publishers"),
        RestEndpoint("GET", "/api/v1/readlists"),
        RestEndpoint("POST", "/api/v1/readlists"),
        RestEndpoint("DELETE", "/api/v1/readlists/{id}"),
        RestEndpoint("GET", "/api/v1/readlists/{id}"),
        RestEndpoint("PATCH", "/api/v1/readlists/{id}"),
        RestEndpoint("GET", "/api/v1/readlists/{id}/books"),
        RestEndpoint("GET", "/api/v1/readlists/{id}/books/{bookId}/next"),
        RestEndpoint("GET", "/api/v1/readlists/{id}/books/{bookId}/previous"),
        RestEndpoint("GET", "/api/v1/readlists/{id}/thumbnail"),
        RestEndpoint("GET", "/api/v1/readlists/{id}/thumbnails"),
        RestEndpoint("POST", "/api/v1/readlists/{id}/thumbnails"),
        RestEndpoint("DELETE", "/api/v1/readlists/{id}/thumbnails/{thumbnailId}"),
        RestEndpoint("GET", "/api/v1/readlists/{id}/thumbnails/{thumbnailId}"),
        RestEndpoint("PUT", "/api/v1/readlists/{id}/thumbnails/{thumbnailId}/selected"),
        RestEndpoint("GET", "/api/v1/series"),
        RestEndpoint("GET", "/api/v1/series/alphabetical-groups"),
        RestEndpoint("GET", "/api/v1/series/latest"),
        RestEndpoint("GET", "/api/v1/series/release-dates"),
        RestEndpoint("POST", "/api/v1/series/list"),
        RestEndpoint("POST", "/api/v1/series/list/alphabetical-groups"),
        RestEndpoint("GET", "/api/v1/series/new"),
        RestEndpoint("GET", "/api/v1/series/updated"),
        RestEndpoint("GET", "/api/v1/series/{seriesId}"),
        RestEndpoint("POST", "/api/v1/series/{seriesId}/analyze"),
        RestEndpoint("GET", "/api/v1/series/{seriesId}/books"),
        RestEndpoint("GET", "/api/v1/series/{seriesId}/collections"),
        RestEndpoint("PATCH", "/api/v1/series/{seriesId}/metadata"),
        RestEndpoint("POST", "/api/v1/series/{seriesId}/metadata/refresh"),
        RestEndpoint("GET", "/api/v1/series/{seriesId}/thumbnail"),
        RestEndpoint("GET", "/api/v1/series/{seriesId}/thumbnails"),
        RestEndpoint("POST", "/api/v1/series/{seriesId}/thumbnails"),
        RestEndpoint("DELETE", "/api/v1/series/{seriesId}/thumbnails/{thumbnailId}"),
        RestEndpoint("GET", "/api/v1/series/{seriesId}/thumbnails/{thumbnailId}"),
        RestEndpoint("PUT", "/api/v1/series/{seriesId}/thumbnails/{thumbnailId}/selected"),
        RestEndpoint("DELETE", "/api/v1/series/{seriesId}/read-progress"),
        RestEndpoint("POST", "/api/v1/series/{seriesId}/read-progress"),
        RestEndpoint("GET", "/api/v1/settings"),
        RestEndpoint("PATCH", "/api/v1/settings"),
        RestEndpoint("GET", "/api/v1/sharing-labels"),
        RestEndpoint("GET", "/api/v1/tags"),
        RestEndpoint("GET", "/api/v1/tags/book"),
        RestEndpoint("GET", "/api/v1/tags/series"),
        RestEndpoint("DELETE", "/api/v1/tasks"),
        RestEndpoint("DELETE", "/api/v1/client-settings/global"),
        RestEndpoint("PATCH", "/api/v1/client-settings/global"),
        RestEndpoint("GET", "/api/v1/client-settings/global/list"),
        RestEndpoint("DELETE", "/api/v1/client-settings/user"),
        RestEndpoint("PATCH", "/api/v1/client-settings/user"),
        RestEndpoint("GET", "/api/v1/client-settings/user/list"),
        RestEndpoint("GET", "/api/v2/users"),
        RestEndpoint("GET", "/api/v2/authors"),
        RestEndpoint("POST", "/api/v2/users"),
        RestEndpoint("GET", "/api/v2/users/authentication-activity"),
        RestEndpoint("GET", "/api/v2/users/me"),
        RestEndpoint("GET", "/api/v2/users/me/api-keys"),
        RestEndpoint("POST", "/api/v2/users/me/api-keys"),
        RestEndpoint("DELETE", "/api/v2/users/me/api-keys/{keyId}"),
        RestEndpoint("GET", "/api/v2/users/me/authentication-activity"),
        RestEndpoint("PATCH", "/api/v2/users/me/password"),
        RestEndpoint("DELETE", "/api/v2/users/{id}"),
        RestEndpoint("PATCH", "/api/v2/users/{id}"),
        RestEndpoint("GET", "/api/v2/users/{id}/authentication-activity/latest"),
        RestEndpoint("PATCH", "/api/v2/users/{id}/password"),
      )
  }
}
