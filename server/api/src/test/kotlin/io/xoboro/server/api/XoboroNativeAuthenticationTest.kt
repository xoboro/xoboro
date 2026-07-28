package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
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
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.PasswordHasher
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.server.security.InMemoryUserSessionRepository
import io.xoboro.server.security.Sha512TokenEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json

class XoboroNativeAuthenticationTest {
  @Test
  fun `claims server and manages a same-origin cookie session`() =
    testApplication {
      val fixture = installNativeAuthentication()

      assertFalse(client.get("$XOBORO_API_PREFIX/setup").body<SetupStatusResponse>().claimed)
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
      val session = setup.body<SessionResponse>()
      assertEquals("admin@example.invalid", session.user.email)
      assertNull(session.accessToken)
      val setCookie = assertNotNull(setup.headers[HttpHeaders.SetCookie])
      assertTrue(setCookie.contains("HttpOnly"))
      assertTrue(setCookie.contains("SameSite=Strict"))
      val cookieValue = setCookie.substringAfter("$XOBORO_SESSION_COOKIE=").substringBefore(';')

      assertTrue(client.get("$XOBORO_API_PREFIX/setup").body<SetupStatusResponse>().claimed)
      assertEquals(
        "admin@example.invalid",
        client
          .get("$XOBORO_API_PREFIX/session") {
            cookie(XOBORO_SESSION_COOKIE, cookieValue)
          }.body<SessionResponse>()
          .user.email,
      )

      val rejected =
        client.delete("$XOBORO_API_PREFIX/session") {
          cookie(XOBORO_SESSION_COOKIE, cookieValue)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
        }
      assertEquals(HttpStatusCode.Forbidden, rejected.status)
      assertEquals(CrossSiteRequestRejectedException.CODE, rejected.body<XoboroApiError>().code)

      val logout =
        client.delete("$XOBORO_API_PREFIX/session") {
          cookie(XOBORO_SESSION_COOKIE, cookieValue)
          trustedBrowserMutation()
        }
      assertEquals(HttpStatusCode.NoContent, logout.status)
      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get("$XOBORO_API_PREFIX/session") {
          cookie(XOBORO_SESSION_COOKIE, cookieValue)
        }.status,
      )
      assertEquals(1, fixture.users.findAll().size)
    }

  @Test
  fun `issues and revokes bearer sessions without cookie CSRF requirements`() =
    testApplication {
      installNativeAuthentication()
      claimAdministrator()

      val login =
        client.post("$XOBORO_API_PREFIX/session") {
          contentType(ContentType.Application.Json)
          setBody(
            LoginRequest(
              email = "admin@example.invalid",
              password = "synthetic-password",
              transport = SessionTransport.BEARER,
            ),
          )
        }
      assertEquals(HttpStatusCode.OK, login.status)
      assertNull(login.headers[HttpHeaders.SetCookie])
      val accessToken = assertNotNull(login.body<SessionResponse>().accessToken)

      assertEquals(
        "admin@example.invalid",
        client
          .get("$XOBORO_API_PREFIX/session") {
            bearerAuth(accessToken)
          }.body<SessionResponse>()
          .user.email,
      )
      assertEquals(
        HttpStatusCode.NoContent,
        client.delete("$XOBORO_API_PREFIX/session") {
          bearerAuth(accessToken)
        }.status,
      )
      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get("$XOBORO_API_PREFIX/session") {
          bearerAuth(accessToken)
        }.status,
      )
    }

  @Test
  fun `requires browser provenance before issuing a cookie session`() =
    testApplication {
      installNativeAuthentication()
      claimAdministrator()

      val request =
        LoginRequest(
          email = "admin@example.invalid",
          password = "synthetic-password",
        )
      val missingProvenance =
        client.post("$XOBORO_API_PREFIX/session") {
          contentType(ContentType.Application.Json)
          setBody(request)
        }
      assertEquals(HttpStatusCode.Forbidden, missingProvenance.status)
      assertEquals(
        CrossSiteRequestRejectedException.CODE,
        missingProvenance.body<XoboroApiError>().code,
      )

      val sameOrigin =
        client.post("$XOBORO_API_PREFIX/session") {
          contentType(ContentType.Application.Json)
          trustedBrowserMutation()
          setBody(request)
        }
      assertEquals(HttpStatusCode.OK, sameOrigin.status)
      assertNotNull(sameOrigin.headers[HttpHeaders.SetCookie])
    }

  @Test
  fun `rejects cross-site cookie login and limits repeated attempts by client`() =
    testApplication {
      installNativeAuthentication(loginLimit = 3)
      claimAdministrator()

      val crossSite =
        client.post("$XOBORO_API_PREFIX/session") {
          contentType(ContentType.Application.Json)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
          setBody(LoginRequest("admin@example.invalid", "synthetic-password"))
        }
      assertEquals(HttpStatusCode.Forbidden, crossSite.status)

      repeat(2) {
        assertEquals(HttpStatusCode.Unauthorized, invalidBearerLogin().status)
      }
      val limited = invalidBearerLogin()
      assertEquals(HttpStatusCode.TooManyRequests, limited.status)
      assertNotNull(limited.headers[HttpHeaders.RetryAfter])
    }

  private fun ApplicationTestBuilder.installNativeAuthentication(
    loginLimit: Int = 10,
  ): Fixture {
    val fixture = Fixture()
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(RateLimit) {
        configureXoboroNativeRateLimits(loginLimit, 1.minutes)
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
      }
      routing {
        xoboroNativeAuthenticationRoutes(fixture.users, fixture.sessions)
      }
    }
    createClient {
      install(ClientContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
      }
    }.also { configuredClient ->
      client = configuredClient
    }
    return fixture
  }

  private suspend fun ApplicationTestBuilder.claimAdministrator() {
    val response =
      client.post("$XOBORO_API_PREFIX/setup") {
        contentType(ContentType.Application.Json)
        trustedBrowserMutation()
        setBody(
          SetupRequest(
            email = "admin@example.invalid",
            password = "synthetic-password",
            transport = SessionTransport.BEARER,
          ),
        )
      }
    assertEquals(HttpStatusCode.Created, response.status)
  }

  private suspend fun ApplicationTestBuilder.invalidBearerLogin() =
    client.post("$XOBORO_API_PREFIX/session") {
      contentType(ContentType.Application.Json)
      setBody(
        LoginRequest(
          email = "missing@example.invalid",
          password = "wrong-password",
          transport = SessionTransport.BEARER,
        ),
      )
    }

  private fun io.ktor.client.request.HttpRequestBuilder.trustedBrowserMutation() {
    header(HttpHeaders.Origin, "http://localhost")
    header("Sec-Fetch-Site", "same-origin")
  }

  private class Fixture {
    private val repository = InMemoryUserRepository()
    private val tokenSequence = AtomicInteger()
    val sessions =
      UserSessionLifecycle(
        users = repository,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = Sha512TokenEncoder(),
        plainTokenFactory = { "session-${tokenSequence.incrementAndGet()}" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val users =
      UserLifecycle(
        users = repository,
        passwordHasher =
          object : PasswordHasher {
            override fun hash(rawPassword: String): String = "hash:$rawPassword"

            override fun matches(
              rawPassword: String,
              passwordHash: String,
            ): Boolean = passwordHash == hash(rawPassword)
          },
        userIdFactory = { "user-1" },
        currentTimeMillis = { 1_000 },
      )
  }

  private class InMemoryUserRepository : UserRepository {
    private var user: User? = null

    override fun count(): Long = if (user == null) 0 else 1

    override fun findByIdOrNull(id: UserId): User? = user?.takeIf { it.id == id }

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      user?.takeIf { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = listOfNotNull(user)

    override fun insert(user: User) {
      if (this.user != null) throw UserEmailAlreadyExistsException(user.email)
      this.user = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (this.user != null) return false
      this.user = user
      return true
    }

    override fun update(user: User) {
      this.user = user
    }

    override fun delete(id: UserId) {
      if (user?.id == id) user = null
    }
  }
}
