package io.xoboro.compatibility.komga.api

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.UserLifecycle
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class BasicAuthenticationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `challenges anonymous access while leaving claim routes public`() {
    withApplication("anonymous.sqlite") {
      val claim = client.get("/api/v1/claim")
      val currentUser = client.get("/api/v2/users/me")

      assertEquals(HttpStatusCode.OK, claim.status)
      assertEquals(
        ClaimStatusDto(false),
        Json.decodeFromString<ClaimStatusDto>(claim.bodyAsText()),
      )
      assertEquals(HttpStatusCode.Unauthorized, currentUser.status)
      assertEquals(
        """Basic realm="$KOMGA_BASIC_REALM"""",
        currentUser.headers[HttpHeaders.WWWAuthenticate],
      )
      assertEquals("application/json", currentUser.headers[HttpHeaders.ContentType])
      currentUser.assertSpringError(
        status = HttpStatusCode.Unauthorized,
        requestPath = "/api/v2/users/me",
        message = "Unauthorized",
      )
    }
  }

  @Test
  fun `returns the OPDS authentication document when v2 authentication is required`() {
    withApplication("opds-v2.sqlite") {
      val response = client.get("/opds/v2/catalog")

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals(
        """Basic realm="$KOMGA_BASIC_REALM"""",
        response.headers[HttpHeaders.WWWAuthenticate],
      )
      assertEquals(
        """<http://localhost/opds/v2/auth>; rel="http://opds-spec.org/auth/document"; type="application/opds-authentication+json"""",
        response.headers[HttpHeaders.Link],
      )
      assertEquals(
        "application/opds-authentication+json; charset=UTF-8",
        response.headers[HttpHeaders.ContentType],
      )
      assertEquals(
        OpdsAuthenticationDocumentDto(
          authentication =
            listOf(
              OpdsAuthenticationFlowDto(
                type = OPDS_BASIC_AUTH_TYPE,
                labels = OpdsAuthenticationLabelsDto("Email", "Password"),
              ),
            ),
          title = "Komga",
          id = "http://localhost/opds/v2/auth",
          description = "Enter your email and password to authenticate.",
          links =
            listOf(
              WPLinkDto(rel = "help", href = "https://komga.org"),
              WPLinkDto(rel = "logo", href = "http://localhost/android-chrome-512x512.png"),
            ),
        ),
        Json.decodeFromString<OpdsAuthenticationDocumentDto>(response.bodyAsText()),
      )
    }
  }

  @Test
  fun `returns the current user for valid case-insensitive Basic credentials`() {
    withApplication("valid.sqlite") {
      claimAdministrator()

      val response =
        client.get("/api/v2/users/me?remember-me=true") {
          basicCredentials("ADMIN@example.invalid", "synthetic-password")
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val user = Json.decodeFromString<UserDto>(response.bodyAsText())
      assertEquals("admin@example.invalid", user.email)
      assertTrue("USER" in user.roles)
      assertTrue("ADMIN" in user.roles)
    }
  }

  @Test
  fun `rejects wrong unknown and malformed Basic credentials`() {
    withApplication("invalid.sqlite") {
      claimAdministrator()

      val wrongPassword =
        client.get("/api/v2/users/me") {
          basicCredentials("admin@example.invalid", "wrong-password")
        }
      val unknownUser =
        client.get("/api/v2/users/me") {
          basicCredentials("unknown@example.invalid", "synthetic-password")
        }
      val malformed =
        client.get("/api/v2/users/me") {
          header(HttpHeaders.Authorization, "Basic not-base64")
        }

      assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
      assertEquals(HttpStatusCode.Unauthorized, unknownUser.status)
      assertEquals(HttpStatusCode.Unauthorized, malformed.status)
    }
  }

  private fun withApplication(
    databaseName: String,
    assertions: suspend ApplicationTestBuilder.() -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve(databaseName))).use { database ->
      val lifecycle =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { 1_000 },
        )
      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(lifecycle)
          routing {
            komgaClaimRoutes(lifecycle)
            komgaAuthenticatedUserRoutes(lifecycle, JooqLibraryRepository(database))
            authenticate(
              KOMGA_BASIC_AUTHENTICATION,
              KOMGA_API_KEY_AUTHENTICATION,
              KOMGA_SESSION_AUTHENTICATION,
              KOMGA_REMEMBER_ME_AUTHENTICATION,
              strategy = AuthenticationStrategy.FirstSuccessful,
            ) {
              get("/opds/v2/catalog") {
                call.respondText("authenticated")
              }
            }
          }
        }
        assertions()
      }
    }
  }

  private suspend fun ApplicationTestBuilder.claimAdministrator() {
    val response =
      client.post("/api/v1/claim") {
        header("X-Komga-Email", "admin@example.invalid")
        header("X-Komga-Password", "synthetic-password")
      }
    assertEquals(HttpStatusCode.OK, response.status)
  }

  private fun HttpRequestBuilder.basicCredentials(
    email: String,
    password: String,
  ) {
    val token =
      Base64
        .getEncoder()
        .encodeToString("$email:$password".toByteArray(StandardCharsets.UTF_8))
    header(HttpHeaders.Authorization, "Basic $token")
  }

  private suspend fun io.ktor.client.statement.HttpResponse.assertSpringError(
    status: HttpStatusCode,
    requestPath: String,
    message: String,
  ) {
    val error = Json.decodeFromString<KomgaErrorResponse>(bodyAsText())
    assertTrue(OffsetDateTime.parse(error.timestamp).year >= 2026)
    assertEquals(status.value, error.status)
    assertEquals(status.description, error.error)
    assertEquals(message, error.message)
    assertEquals(requestPath, error.path)
  }

  private companion object {
    val komgaJson =
      Json {
        explicitNulls = false
      }
  }
}
