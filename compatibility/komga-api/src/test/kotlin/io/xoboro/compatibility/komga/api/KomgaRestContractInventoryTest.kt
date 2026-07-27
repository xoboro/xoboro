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
    assertEquals(18, PARTIALLY_IMPLEMENTED_ENDPOINTS.size)
    assertTrue(endpoints.containsAll(PARTIALLY_IMPLEMENTED_ENDPOINTS))
    assertEquals(147, endpoints.minus(PARTIALLY_IMPLEMENTED_ENDPOINTS).size)
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
        RestEndpoint("GET", "/api/v1/claim"),
        RestEndpoint("POST", "/api/v1/claim"),
        RestEndpoint("GET", "/api/v1/login/set-cookie"),
        RestEndpoint("GET", "/api/v2/users"),
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
