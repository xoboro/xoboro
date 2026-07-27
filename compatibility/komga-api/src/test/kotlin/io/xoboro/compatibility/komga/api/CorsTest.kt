package io.xoboro.compatibility.komga.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorsTest {
  @Test
  fun `allows configured origins with Komga credentials and exposed headers`() =
    testApplication {
      application {
        installKomgaSecurityHeaders()
        installKomgaCors(setOf(ALLOWED_ORIGIN))
        routing {
          get("/reader/api/v1/synthetic") {
            call.respondText("synthetic")
          }
        }
      }

      val response =
        client.get("/reader/api/v1/synthetic") {
          header(HttpHeaders.Origin, ALLOWED_ORIGIN)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("synthetic", response.bodyAsText())
      assertEquals(ALLOWED_ORIGIN, response.headers[HttpHeaders.AccessControlAllowOrigin])
      assertEquals("true", response.headers[HttpHeaders.AccessControlAllowCredentials])
      assertEquals(
        "Content-Disposition, X-Auth-Token",
        response.headers[HttpHeaders.AccessControlExposeHeaders],
      )
      assertEquals("SAMEORIGIN", response.headers["X-Frame-Options"])
    }

  @Test
  fun `answers Komga preflight and rejects untrusted origins and methods`() =
    testApplication {
      application {
        installKomgaSecurityHeaders()
        installKomgaCors(setOf(ALLOWED_ORIGIN))
        routing {
          get("/api/v1/synthetic") {
            call.respondText("synthetic")
          }
        }
      }

      val preflight =
        client.options("/api/v1/synthetic") {
          header(HttpHeaders.Origin, ALLOWED_ORIGIN)
          header(HttpHeaders.AccessControlRequestMethod, "PATCH")
          header(HttpHeaders.AccessControlRequestHeaders, "X-Synthetic, Content-Type")
        }
      assertEquals(HttpStatusCode.OK, preflight.status)
      assertEquals("", preflight.bodyAsText())
      assertEquals(
        "GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS,TRACE",
        preflight.headers[HttpHeaders.AccessControlAllowMethods],
      )
      assertEquals(
        "X-Synthetic, Content-Type",
        preflight.headers[HttpHeaders.AccessControlAllowHeaders],
      )
      assertEquals("1800", preflight.headers[HttpHeaders.AccessControlMaxAge])
      assertEquals("nosniff", preflight.headers["X-Content-Type-Options"])

      val deniedOrigin =
        client.get("/api/v1/synthetic") {
          header(HttpHeaders.Origin, "https://denied.example.invalid")
        }
      assertEquals(HttpStatusCode.Forbidden, deniedOrigin.status)
      assertEquals("Invalid CORS request", deniedOrigin.bodyAsText())
      assertEquals("SAMEORIGIN", deniedOrigin.headers["X-Frame-Options"])

      val deniedMethod =
        client.options("/api/v1/synthetic") {
          header(HttpHeaders.Origin, ALLOWED_ORIGIN)
          header(HttpHeaders.AccessControlRequestMethod, "CONNECT")
        }
      assertEquals(HttpStatusCode.Forbidden, deniedMethod.status)
      assertEquals("Invalid CORS request", deniedMethod.bodyAsText())
    }

  @Test
  fun `ignores origins without configuration and outside Komga protocols`() =
    testApplication {
      application {
        installKomgaSecurityHeaders()
        installKomgaCors(emptySet())
        routing {
          get("/api/v1/synthetic") {
            call.respondText("api")
          }
          get("/outside") {
            call.respondText("outside")
          }
        }
      }

      val unconfigured =
        client.get("/api/v1/synthetic") {
          header(HttpHeaders.Origin, ALLOWED_ORIGIN)
        }
      assertEquals(HttpStatusCode.OK, unconfigured.status)
      assertNull(unconfigured.headers[HttpHeaders.AccessControlAllowOrigin])

      val outside =
        client.get("/outside") {
          header(HttpHeaders.Origin, ALLOWED_ORIGIN)
        }
      assertEquals(HttpStatusCode.OK, outside.status)
      assertNull(outside.headers[HttpHeaders.AccessControlAllowOrigin])
    }

  private companion object {
    const val ALLOWED_ORIGIN = "https://reader.example.invalid"
  }
}
