package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.OAuth2ClientRegistration
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.OAuth2ExternalIdentity
import io.xoboro.core.application.OAuth2IdentityGateway
import io.xoboro.core.application.OAuth2LoginLifecycle
import io.xoboro.core.application.OAuth2Protocol
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqAuthenticationActivityRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import io.xoboro.server.security.InMemoryOAuth2PendingAuthorizationStore
import io.xoboro.server.security.InMemoryUserSessionRepository
import io.xoboro.server.security.Sha512TokenEncoder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class OAuth2RoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `lists providers completes login and issues a Komga session`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("oauth-routes.sqlite"))).use {
        database ->
      val users = JooqUserRepository(database)
      val userLifecycle =
        UserLifecycle(
          users = users,
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { 1_000 },
        )
      val sessions =
        UserSessionLifecycle(
          users = users,
          sessions = InMemoryUserSessionRepository(),
          tokenEncoder = Sha512TokenEncoder(),
          plainTokenFactory = { "synthetic-session-token" },
          currentTimeMillis = { 1_000 },
          inactivityTimeoutMillis = 60_000,
        )
      val gateway = FakeGateway()
      val activities =
        AuthenticationActivityLifecycle(
          activities = JooqAuthenticationActivityRepository(database),
          currentTimeMillis = { 1_000 },
        )
      val oauth2 =
        OAuth2LoginLifecycle(
          registrations = listOf(registration()),
          users = userLifecycle,
          pendingAuthorizations = InMemoryOAuth2PendingAuthorizationStore(),
          identityGateway = gateway,
          accountCreationEnabled = false,
          oidcEmailVerificationEnabled = true,
          randomPasswordFactory = { "unused-password" },
          stateFactory = { "synthetic-state" },
          browserBindingFactory = { "synthetic-browser-binding" },
          nonceFactory = { "synthetic-nonce" },
          currentTimeMillis = { 1_000 },
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
            komgaOAuth2Routes(oauth2, sessions, activities)
          }
        }
        val client =
          createClient {
            followRedirects = false
            install(ContentNegotiation) {
              json(komgaJson)
            }
          }
        client.post("/api/v1/claim") {
          header("X-Komga-Email", USER_EMAIL)
          header("X-Komga-Password", "synthetic-password")
        }

        assertEquals(
          listOf(OAuth2ClientDto("Synthetic Identity", "synthetic")),
          client.get("/api/v1/oauth2/providers").body(),
        )
        val authorization = client.get("/oauth2/authorization/synthetic")
        assertEquals(HttpStatusCode.Found, authorization.status)
        val authorizationLocation =
          Url(assertNotNull(authorization.headers[HttpHeaders.Location]))
        assertEquals("synthetic-state", authorizationLocation.parameters["state"])
        assertEquals("synthetic-nonce", authorizationLocation.parameters["nonce"])
        assertEquals(
          "http://localhost/login/oauth2/code/synthetic",
          authorizationLocation.parameters["redirect_uri"],
        )
        val bindingCookie =
          assertNotNull(
            authorization.headers
              .getAll(HttpHeaders.SetCookie)
              ?.firstOrNull { it.startsWith("$OAUTH2_BINDING_COOKIE=") },
          ).substringBefore(';')

        val callback =
          client.get(
            "/login/oauth2/code/synthetic?state=synthetic-state&code=synthetic-code",
          ) {
            header(HttpHeaders.Cookie, bindingCookie)
          }
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/?server_redirect=Y", callback.headers[HttpHeaders.Location])
        assertEquals("synthetic-code", gateway.authorizationCode)
        val cookie =
          assertNotNull(
            callback.headers
              .getAll(HttpHeaders.SetCookie)
              ?.firstOrNull { it.startsWith("$KOMGA_SESSION_COOKIE=") },
          )
        assertTrue(cookie.startsWith("$KOMGA_SESSION_COOKIE=synthetic-session-token"))

        val me =
          client.get("/api/v2/users/me") {
            header(HttpHeaders.Cookie, "$KOMGA_SESSION_COOKIE=synthetic-session-token")
          }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(USER_EMAIL, me.body<UserDto>().email)
        val user = assertNotNull(userLifecycle.findByEmailIgnoreCaseOrNull(USER_EMAIL))
        assertEquals("OAuth2:Synthetic Identity", activities.findMostRecentByUser(user)?.source)

        val replay =
          client.get(
            "/login/oauth2/code/synthetic?state=synthetic-state&code=synthetic-code",
          )
        assertEquals(
          "/login?server_redirect=Y&error=invalid_state",
          replay.headers[HttpHeaders.Location],
        )
      }
    }
  }

  private class FakeGateway : OAuth2IdentityGateway {
    var authorizationCode: String? = null

    override fun authorizationUrl(
      registration: OAuth2ClientRegistration,
      redirectUri: String,
      state: String,
      nonce: String?,
    ): String =
      "${registration.authorizationUri}?" +
        "state=$state&nonce=$nonce&redirect_uri=${redirectUri}"

    override suspend fun exchange(
      registration: OAuth2ClientRegistration,
      redirectUri: String,
      authorizationCode: String,
      expectedNonce: String?,
    ): OAuth2ExternalIdentity {
      this.authorizationCode = authorizationCode
      return OAuth2ExternalIdentity(USER_EMAIL, emailVerified = true)
    }
  }

  private companion object {
    const val USER_EMAIL = "reader@example.invalid"
    val komgaJson = Json { explicitNulls = false }

    fun registration(): OAuth2ClientRegistration =
      OAuth2ClientRegistration(
        registrationId = "synthetic",
        clientName = "Synthetic Identity",
        clientId = "synthetic-client",
        clientSecret = "synthetic-secret",
        authorizationUri = "https://identity.example.invalid/authorize",
        tokenUri = "https://identity.example.invalid/token",
        userInfoUri = "https://identity.example.invalid/userinfo",
        scopes = listOf("openid", "email"),
        protocol = OAuth2Protocol.OIDC,
        issuerUri = "https://identity.example.invalid",
        jwkSetUri = "https://identity.example.invalid/jwks",
      )
  }
}
