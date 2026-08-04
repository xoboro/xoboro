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
import io.xoboro.core.domain.SessionInsert
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserSession
import io.xoboro.core.domain.UserSessionRepository
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import io.xoboro.server.security.InMemoryUserSessionRepository
import io.xoboro.server.security.Sha512TokenEncoder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class SessionRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `creates reuses converts expires and logs out Komga sessions`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("sessions.sqlite"))).use { database ->
      val users = JooqUserRepository(database)
      val clock = AtomicLong(1_000)
      val userLifecycle =
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

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(
            users = userLifecycle,
            sessions = sessions,
          )
          routing {
            komgaClaimRoutes(userLifecycle)
            komgaAuthenticatedUserRoutes(
              users = userLifecycle,
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

        val cookieLogin =
          client.get("/api/v2/users/me") {
            basicAuth(USER_EMAIL, PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, cookieLogin.status)
        val cookieToken = cookieLogin.sessionCookieToken()
        assertEquals(USER_EMAIL, cookieLogin.body<UserDto>().email)
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v2/users/me") {
            header(HttpHeaders.Cookie, "$KOMGA_SESSION_COOKIE=$cookieToken")
          }.status,
        )

        val headerLogin =
          client.get("/api/v2/users/me") {
            basicAuth(USER_EMAIL, PASSWORD)
            header(KOMGA_SESSION_HEADER, "request-header-session")
          }
        val headerToken = assertNotNull(headerLogin.headers[KOMGA_SESSION_HEADER])
        assertNotEquals("request-header-session", headerToken)
        assertTrue(headerLogin.headers.getAll(HttpHeaders.SetCookie).orEmpty().isEmpty())
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v2/users/me") {
            header(KOMGA_SESSION_HEADER, headerToken)
          }.status,
        )

        val converted =
          client.get("/api/v1/login/set-cookie") {
            header(KOMGA_SESSION_HEADER, headerToken)
          }
        assertEquals(HttpStatusCode.NoContent, converted.status)
        assertEquals(headerToken, converted.sessionCookieToken())

        assertEquals(
          HttpStatusCode.NoContent,
          client.post("/api/logout") {
            header(KOMGA_SESSION_HEADER, headerToken)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v2/users/me") {
            header(KOMGA_SESSION_HEADER, headerToken)
          }.status,
        )

        assertEquals(
          HttpStatusCode.NoContent,
          client.get("/api/logout") {
            header(HttpHeaders.Cookie, "$KOMGA_SESSION_COOKIE=$cookieToken")
          }.status,
        )
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v2/users/me") {
            header(HttpHeaders.Cookie, "$KOMGA_SESSION_COOKIE=$cookieToken")
          }.status,
        )

        val expiring =
          client.get("/api/v2/users/me") {
            basicAuth(USER_EMAIL, PASSWORD)
          }.sessionCookieToken()
        clock.addAndGet(SESSION_TIMEOUT + 1)
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v2/users/me") {
            header(HttpHeaders.Cookie, "$KOMGA_SESSION_COOKIE=$expiring")
          }.status,
        )
      }
    }
  }

  @Test
  fun `serves a Basic request when the session store cannot open a session`() {
    // Found on a public deployment: while a scan of 18,211 archives held the SQLite write lock, the
    // INSERT that opens a session threw and a request with perfectly good credentials answered 500.
    // The first request every Komga client and the PWA make is exactly this one.
    //
    // For `Basic` the session is an optimization - it saves re-hashing the password - so losing it
    // costs one Argon2 hash on the next request. Losing the request costs the page.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("busy-sessions.sqlite"))).use {
        database ->
      val users = JooqUserRepository(database)
      val userLifecycle =
        UserLifecycle(
          users = users,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { 1_000 },
        )
      val sessions =
        UserSessionLifecycle(
          users = users,
          sessions = UnavailableInsertSessionRepository(),
          tokenEncoder = Sha512TokenEncoder(),
          plainTokenFactory = sessionTokens().iterator()::next,
          currentTimeMillis = { 1_000 },
          inactivityTimeoutMillis = SESSION_TIMEOUT,
        )

      testApplication {
        application {
          install(ServerContentNegotiation) { json(komgaJson) }
          installKomgaBasicAuthentication(users = userLifecycle, sessions = sessions)
          routing {
            komgaClaimRoutes(userLifecycle)
            komgaAuthenticatedUserRoutes(
              users = userLifecycle,
              libraries = JooqLibraryRepository(database),
            )
            komgaSessionRoutes(sessions)
          }
        }
        val client = createClient { install(ContentNegotiation) { json(komgaJson) } }
        client.post("/api/v1/claim") {
          header("X-Komga-Email", USER_EMAIL)
          header("X-Komga-Password", PASSWORD)
        }

        val response =
          client.get("/api/v2/users/me") {
            basicAuth(USER_EMAIL, PASSWORD)
          }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(USER_EMAIL, response.body<UserDto>().email)
        // No session was opened, so none is handed out. Silently sending a cookie whose token was
        // never stored would be worse than sending none: every later request would present a token
        // the server cannot find and be treated as anonymous.
        assertNull(response.headers.getAll(HttpHeaders.SetCookie))
      }
    }
  }

  /** A store that accepts nothing, the way SQLite answers while another writer holds the lock. */
  private class UnavailableInsertSessionRepository : UserSessionRepository {
    private val delegate = InMemoryUserSessionRepository()

    override fun insertIfAbsent(session: UserSession): SessionInsert = SessionInsert.UNAVAILABLE

    override fun findByTokenDigestOrNull(tokenDigest: String) =
      delegate.findByTokenDigestOrNull(tokenDigest)

    override fun touchIfActive(
      tokenDigest: String,
      accessedAtMillis: Long,
      expiresAtMillis: Long,
    ) = delegate.touchIfActive(tokenDigest, accessedAtMillis, expiresAtMillis)

    override fun deleteByTokenDigest(tokenDigest: String) =
      delegate.deleteByTokenDigest(tokenDigest)

    override fun deleteByUserId(userId: UserId) = delegate.deleteByUserId(userId)

    override fun deleteExpired(nowMillis: Long) = delegate.deleteExpired(nowMillis)
  }

  private fun io.ktor.client.statement.HttpResponse.sessionCookieToken(): String {
    val setCookie = assertNotNull(headers.getAll(HttpHeaders.SetCookie)?.singleOrNull())
    val prefix = "$KOMGA_SESSION_COOKIE="
    return setCookie
      .substringAfter(prefix)
      .substringBefore(';')
      .also { assertTrue(it.isNotBlank()) }
  }

  private fun sessionTokens(): List<String> =
    listOf(
      "00000000000000000000000000000001",
      "00000000000000000000000000000002",
      "00000000000000000000000000000003",
    )

  private companion object {
    const val USER_EMAIL = "reader@example.invalid"
    const val PASSWORD = "synthetic-password"
    const val SESSION_TIMEOUT = 7L * 24 * 60 * 60 * 1_000
    val komgaJson = Json { explicitNulls = false }
  }
}
