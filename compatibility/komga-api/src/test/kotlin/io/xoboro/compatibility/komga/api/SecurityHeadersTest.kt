package io.xoboro.compatibility.komga.api

import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecurityHeadersTest {
  @Test
  fun `matches Komga security chains through a context path`() =
    testApplication {
      application {
        installKomgaSecurityHeaders()
        routing {
          listOf(
            "/api/v1/synthetic",
            "/reader/opds/v2/synthetic",
            "/sse/v1/synthetic",
            "/oauth2/authorization/synthetic",
            "/login/oauth2/code/synthetic",
            "/actuator/info",
            "/kobo/token/ping",
            "/reader/koreader/users/auth",
            "/outside",
            "/apian",
          ).forEach { path ->
            get(path) {
              call.respondText("synthetic")
            }
          }
        }
      }

      listOf(
        "/api/v1/synthetic",
        "/reader/opds/v2/synthetic",
        "/sse/v1/synthetic",
        "/oauth2/authorization/synthetic",
        "/login/oauth2/code/synthetic",
        "/actuator/info",
      ).forEach { path ->
        client.get(path).assertKomgaHeaders("SAMEORIGIN")
      }
      listOf(
        "/kobo/token/ping",
        "/reader/koreader/users/auth",
      ).forEach { path ->
        client.get(path).assertKomgaHeaders("DENY")
      }
      listOf("/outside", "/apian").forEach { path ->
        val response = client.get(path)
        assertNull(response.headers["X-Content-Type-Options"])
        assertNull(response.headers["X-XSS-Protection"])
        assertNull(response.headers["X-Frame-Options"])
        assertNull(response.headers[HttpHeaders.Vary])
      }
    }

  @Test
  fun `classifies exact security path segments`() {
    assertEquals("SAMEORIGIN", "/reader/api".komgaFramePolicyOrNull())
    assertEquals("SAMEORIGIN", "/reader/api/v1/books".komgaFramePolicyOrNull())
    assertEquals("DENY", "/reader/kobo/token".komgaFramePolicyOrNull())
    assertEquals("DENY", "/koreader".komgaFramePolicyOrNull())
    assertNull("/reader/apian".komgaFramePolicyOrNull())
    assertNull("/reader/kobold".komgaFramePolicyOrNull())
  }
}

private fun io.ktor.client.statement.HttpResponse.assertKomgaHeaders(framePolicy: String) {
  assertEquals("nosniff", headers["X-Content-Type-Options"])
  assertEquals("0", headers["X-XSS-Protection"])
  assertEquals(framePolicy, headers["X-Frame-Options"])
  assertEquals(
    listOf(
      "Origin",
      "Access-Control-Request-Method",
      "Access-Control-Request-Headers",
    ),
    headers.getAll(HttpHeaders.Vary),
  )
}
