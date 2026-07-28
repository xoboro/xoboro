package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import io.xoboro.server.security.InMemoryUserSessionRepository
import io.xoboro.server.security.Sha512TokenEncoder
import io.xoboro.server.security.SpringCompatibleRememberMeTokenService
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class RememberMeRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `issues authenticates invalidates and clears Komga remember-me cookies`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("remember-me.sqlite"))).use { database ->
      val users = JooqUserRepository(database)
      val clock = AtomicLong(1_000)
      val lifecycle =
        UserLifecycle(
          users = users,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = clock::get,
        )
      val sessions =
        UserSessionLifecycle(
          users = users,
          sessions = InMemoryUserSessionRepository(),
          tokenEncoder = Sha512TokenEncoder(),
          plainTokenFactory = sessionTokens().iterator()::next,
          currentTimeMillis = clock::get,
          inactivityTimeoutMillis = SESSION_TIMEOUT,
        )
      val rememberMe =
        SpringCompatibleRememberMeTokenService(
          users = users,
          secretKey = "synthetic-remember-me-secret",
          currentTimeMillis = clock::get,
          tokenValidityMillis = REMEMBER_ME_TIMEOUT,
        )

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(
            users = lifecycle,
            sessions = sessions,
            rememberMe = rememberMe,
          )
          routing {
            komgaClaimRoutes(lifecycle)
            komgaAuthenticatedUserRoutes(
              users = lifecycle,
              libraries = JooqLibraryRepository(database),
            )
            komgaSessionRoutes(sessions)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(komgaJson)
            }
          }
        client.post("/api/v1/claim") {
          header("X-Komga-Email", USER_EMAIL)
          header("X-Komga-Password", PASSWORD)
        }

        val login =
          client.get("/api/v2/users/me?remember-me=true") {
            basicAuth(USER_EMAIL, PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, login.status)
        val rememberToken = login.cookieValue(KOMGA_REMEMBER_ME_COOKIE)
        assertTrue(
          login.headers
            .getAll(HttpHeaders.SetCookie)
            .orEmpty()
            .single { it.startsWith("$KOMGA_REMEMBER_ME_COOKIE=") }
            .contains("Max-Age=$REMEMBER_ME_MAX_AGE_SECONDS"),
        )

        val restored =
          client.get("/api/v2/users/me") {
            header(HttpHeaders.Cookie, "$KOMGA_REMEMBER_ME_COOKIE=$rememberToken")
          }
        assertEquals(HttpStatusCode.OK, restored.status)
        assertEquals(USER_EMAIL, restored.body<UserDto>().email)
        assertTrue(
          restored.headers
            .getAll(HttpHeaders.SetCookie)
            .orEmpty()
            .any { it.startsWith("$KOMGA_SESSION_COOKIE=") },
        )

        lifecycle.updatePassword(lifecycle.findAll().single().id, "updated-password")
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v2/users/me") {
            header(HttpHeaders.Cookie, "$KOMGA_REMEMBER_ME_COOKIE=$rememberToken")
          }.status,
        )

        val replacement =
          client.get("/api/v2/users/me?remember-me=true") {
            basicAuth(USER_EMAIL, "updated-password")
          }.cookieValue(KOMGA_REMEMBER_ME_COOKIE)
        val logout =
          client.post("/api/logout") {
            header(HttpHeaders.Cookie, "$KOMGA_REMEMBER_ME_COOKIE=$replacement")
          }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertTrue(
          logout.headers
            .getAll(HttpHeaders.SetCookie)
            .orEmpty()
            .single { it.startsWith("$KOMGA_REMEMBER_ME_COOKIE=") }
            .contains("Max-Age=0"),
        )

        clock.addAndGet(REMEMBER_ME_TIMEOUT + 1)
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v2/users/me") {
            header(HttpHeaders.Cookie, "$KOMGA_REMEMBER_ME_COOKIE=$replacement")
          }.status,
        )
      }
    }
  }

  private fun io.ktor.client.statement.HttpResponse.cookieValue(name: String): String {
    val setCookie =
      assertNotNull(
        headers
          .getAll(HttpHeaders.SetCookie)
          ?.singleOrNull { it.startsWith("$name=") },
      )
    return setCookie
      .substringAfter("$name=")
      .substringBefore(';')
      .also { assertTrue(it.isNotBlank()) }
  }

  private fun sessionTokens(): List<String> =
    listOf(
      "00000000000000000000000000000001",
      "00000000000000000000000000000002",
      "00000000000000000000000000000003",
      "00000000000000000000000000000004",
    )

  private companion object {
    const val USER_EMAIL = "reader@example.invalid"
    const val PASSWORD = "synthetic-password"
    const val SESSION_TIMEOUT = 7L * 24 * 60 * 60 * 1_000
    const val REMEMBER_ME_TIMEOUT = 365L * 24 * 60 * 60 * 1_000
    const val REMEMBER_ME_MAX_AGE_SECONDS = 365 * 24 * 60 * 60
    val komgaJson = Json { explicitNulls = false }
  }
}
