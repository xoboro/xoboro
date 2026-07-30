package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.server.api.LoginRequest
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XOBORO_SESSION_COOKIE
import io.xoboro.server.api.XoboroApiError
import io.xoboro.server.api.XoboroUserCreationRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

/**
 * Pins the native error code contract through the real production pipeline (`xoboroModule`), which
 * is where a global StatusPages handler previously flattened every route-level "*_not_found" and
 * "*_forbidden" code to a generic "not_found"/"forbidden" — see [XoboroNativeOpsApplicationTest] for
 * the "*_not_found" regression coverage. Route-level tests cannot catch this class of defect because
 * they install their own minimal StatusPages without the flattening handler.
 */
class XoboroNativeErrorContractApplicationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `forbidden native error code is not flattened through the production boundary`() {
    val databasePath = tempDirectory.resolve("error-contract-forbidden.sqlite")
    val runtime = openRuntime(databasePath)

    testApplication {
      application { xoboroModule(runtime) }
      val client = jsonClient()
      val adminToken = adminToken(client)

      val created =
        client.post("$XOBORO_API_PREFIX/users") {
          bearerAuth(adminToken)
          contentType(ContentType.Application.Json)
          setBody(
            XoboroUserCreationRequest(
              email = "reader@example.invalid",
              password = "synthetic-password",
            ),
          )
        }
      assertEquals(HttpStatusCode.Created, created.status)

      val readerSession =
        client.post("$XOBORO_API_PREFIX/session") {
          contentType(ContentType.Application.Json)
          setBody(
            LoginRequest(
              email = "reader@example.invalid",
              password = "synthetic-password",
              transport = SessionTransport.BEARER,
            ),
          )
        }
      assertEquals(HttpStatusCode.OK, readerSession.status)
      val readerToken = requireNotNull(readerSession.body<SessionResponse>().accessToken)

      val forbidden =
        client.get("$XOBORO_API_PREFIX/users") { bearerAuth(readerToken) }
      assertEquals(HttpStatusCode.Forbidden, forbidden.status)
      assertEquals("user_administration_forbidden", forbidden.body<XoboroApiError>().code)
    }
  }

  @Test
  fun `not-found native error code is not flattened through the production boundary`() {
    val databasePath = tempDirectory.resolve("error-contract-not-found.sqlite")
    val runtime = openRuntime(databasePath)

    testApplication {
      application { xoboroModule(runtime) }
      val client = jsonClient()
      val token = adminToken(client)

      val missing =
        client.get("$XOBORO_API_PREFIX/libraries/missing-library") { bearerAuth(token) }
      assertEquals(HttpStatusCode.NotFound, missing.status)
      assertEquals("library_not_found", missing.body<XoboroApiError>().code)
    }
  }

  @Test
  fun `cross-site cookie mutation is rejected with a specific code through the production boundary`() {
    val databasePath = tempDirectory.resolve("error-contract-csrf.sqlite")
    val runtime = openRuntime(databasePath)

    testApplication {
      application { xoboroModule(runtime) }
      val client = jsonClient()

      val setup =
        client.post("$XOBORO_API_PREFIX/setup") {
          contentType(ContentType.Application.Json)
          trustedBrowserMutation()
          setBody(
            SetupRequest(
              email = "admin@example.invalid",
              password = "synthetic-password",
            ),
          )
        }
      assertEquals(HttpStatusCode.Created, setup.status)
      val setCookie = assertNotNull(setup.headers[HttpHeaders.SetCookie])
      val cookieValue = setCookie.substringAfter("$XOBORO_SESSION_COOKIE=").substringBefore(';')

      // Cookie-authenticated mutations from a cross-site origin are rejected by the
      // XoboroCookieCsrfProtection route plugin, which throws CrossSiteRequestRejectedException.
      // Application.kt's exception<CrossSiteRequestRejectedException> handler answers 403 with a
      // specific code, and this call proves the global status(Forbidden) StatusPages handler does
      // not re-intercept that already-written response and flatten it to a generic "forbidden".
      val rejected =
        client.delete("$XOBORO_API_PREFIX/session") {
          cookie(XOBORO_SESSION_COOKIE, cookieValue)
          crossSite()
        }
      assertEquals(HttpStatusCode.Forbidden, rejected.status)
      assertEquals("cross_site_request_rejected", rejected.body<XoboroApiError>().code)
    }
  }

  private fun openRuntime(databasePath: Path): XoboroRuntime =
    XoboroRuntime.open(
      ServerConfig(
        port = 25_600,
        databasePath = databasePath,
        workerCount = 1,
        taskPollMillis = 10,
        taskFailurePollMillis = 10,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
        backupsDirectory = tempDirectory.resolve("backups"),
      ),
    )

  private fun ApplicationTestBuilder.jsonClient(): HttpClient =
    createClient {
      install(ContentNegotiation) {
        json()
      }
    }

  private suspend fun adminToken(client: HttpClient): String {
    val setup =
      client.post("$XOBORO_API_PREFIX/setup") {
        contentType(ContentType.Application.Json)
        setBody(
          SetupRequest(
            email = "admin@example.invalid",
            password = "synthetic-password",
            transport = SessionTransport.BEARER,
          ),
        )
      }
    assertEquals(HttpStatusCode.Created, setup.status)
    return requireNotNull(setup.body<SessionResponse>().accessToken)
  }

  private fun HttpRequestBuilder.trustedBrowserMutation() {
    header(HttpHeaders.Origin, "http://localhost")
    header("Sec-Fetch-Site", "same-origin")
  }

  private fun HttpRequestBuilder.crossSite() {
    header(HttpHeaders.Origin, "https://cross-site.example.invalid")
    header("Sec-Fetch-Site", "cross-site")
  }
}
