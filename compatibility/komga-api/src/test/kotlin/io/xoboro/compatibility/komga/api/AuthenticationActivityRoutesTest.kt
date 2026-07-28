package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqApiKeyRepository
import io.xoboro.server.persistence.JooqAuthenticationActivityRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import io.xoboro.server.security.Sha512TokenEncoder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.io.TempDir

class AuthenticationActivityRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `records and exposes password and API key activity with Komga paging`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("activity-routes.sqlite"))).use { database ->
      val users = JooqUserRepository(database)
      val apiKeys = JooqApiKeyRepository(database)
      val clock = AtomicLong(1_000)
      val userIds = AtomicLong(1)
      val userLifecycle =
        UserLifecycle(
          users = users,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-${userIds.getAndIncrement()}" },
          currentTimeMillis = clock::getAndIncrement,
        )
      val apiKeyLifecycle =
        ApiKeyLifecycle(
          users = users,
          apiKeys = apiKeys,
          tokenEncoder = Sha512TokenEncoder(),
          apiKeyIdFactory = { "key-1" },
          plainTextKeyFactory = { PLAIN_TEXT_KEY },
          currentTimeMillis = clock::getAndIncrement,
        )
      val activityLifecycle =
        AuthenticationActivityLifecycle(
          activities = JooqAuthenticationActivityRepository(database),
          currentTimeMillis = clock::getAndIncrement,
        )

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(userLifecycle, apiKeyLifecycle, activityLifecycle)
          routing {
            komgaClaimRoutes(userLifecycle)
            komgaAuthenticatedUserRoutes(
              users = userLifecycle,
              libraries = JooqLibraryRepository(database),
              apiKeys = apiKeyLifecycle,
            )
            komgaAuthenticationActivityRoutes(userLifecycle, activityLifecycle)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(komgaJson)
            }
          }
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", PASSWORD)
          }.status,
        )

        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v2/users/me") {
            basicAuth(ADMIN_EMAIL, "wrong-password")
            header(HttpHeaders.UserAgent, "Synthetic failure client")
          }.status,
        )
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v2/users/me") {
            basicAuth(ADMIN_EMAIL, PASSWORD)
            header(HttpHeaders.UserAgent, "Synthetic password client")
          }.status,
        )

        val createdKey =
          client.post("/api/v2/users/me/api-keys") {
            basicAuth(ADMIN_EMAIL, PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ApiKeyRequestDto("Synthetic API client"))
          }.body<ApiKeyDto>()
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v2/users/me") {
            header(KOMGA_API_KEY_HEADER, createdKey.key)
            header(HttpHeaders.UserAgent, "Synthetic API client")
          }.status,
        )

        val pageResponse =
          client.get(
            "/api/v2/users/me/authentication-activity?page=0&size=20&sort=dateTime,desc",
          ) {
            basicAuth(ADMIN_EMAIL, PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, pageResponse.status)
        val page = pageResponse.body<AuthenticationActivityPageDto>()
        assertEquals(5, page.totalElements)
        assertEquals(5, page.numberOfElements)
        assertEquals(0, page.number)
        assertEquals(20, page.size)
        assertEquals(true, page.first)
        assertEquals(true, page.last)
        assertEquals("Password", page.content.first().source)
        assertEquals(false, page.content.last().success)
        assertEquals("Bad credentials", page.content.last().error)
        assertEquals("Synthetic failure client", page.content.last().userAgent)

        val latest =
          client.get(
            "/api/v2/users/${createdKey.userId}/authentication-activity/latest?apikey_id=${createdKey.id}",
          ) {
            basicAuth(ADMIN_EMAIL, PASSWORD)
          }.body<AuthenticationActivityDto>()
        assertEquals("ApiKey", latest.source)
        assertEquals(createdKey.id, latest.apiKeyId)
        assertEquals("Synthetic API client", latest.apiKeyComment)

        client.get("/api/v2/users/me") {
          header(KOMGA_API_KEY_HEADER, "invalid-plain-token")
        }
        val all =
          client.get("/api/v2/users/authentication-activity?unpaged=true") {
            basicAuth(ADMIN_EMAIL, PASSWORD)
          }.body<AuthenticationActivityPageDto>()
        val failedApiKey = all.content.first { it.source == "ApiKey" && !it.success }
        assertNotEquals("invalid-plain-token", failedApiKey.apiKeyComment)
        assertFalse(failedApiKey.apiKeyComment.isNullOrBlank())

        assertEquals(
          HttpStatusCode.BadRequest,
          client.get("/api/v2/users/me/authentication-activity?page=invalid") {
            basicAuth(ADMIN_EMAIL, PASSWORD)
          }.status,
        )
        userLifecycle.createUser(
          email = "reader@example.invalid",
          rawPassword = PASSWORD,
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/api/v2/users/authentication-activity") {
            basicAuth("reader@example.invalid", PASSWORD)
          }.status,
        )
      }
    }
  }

  private companion object {
    const val ADMIN_EMAIL: String = "admin@example.invalid"
    const val PASSWORD: String = "synthetic-password"
    const val PLAIN_TEXT_KEY: String = "0123456789abcdef0123456789abcdef"
    val komgaJson =
      kotlinx.serialization.json.Json {
        explicitNulls = false
      }
  }
}
