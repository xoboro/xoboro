package io.xoboro.compatibility.komga.api

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqApiKeyRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import io.xoboro.server.security.Sha512TokenEncoder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class ApiKeyRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `creates redacts authenticates and deletes a Komga API key`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("api-key-routes.sqlite"))).use { database ->
      val users = JooqUserRepository(database)
      val apiKeyRepository = JooqApiKeyRepository(database)
      val userLifecycle =
        UserLifecycle(
          users = users,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { 1_000 },
        )
      val tokenEncoder = Sha512TokenEncoder()
      val apiKeyLifecycle =
        ApiKeyLifecycle(
          users = users,
          apiKeys = apiKeyRepository,
          tokenEncoder = tokenEncoder,
          apiKeyIdFactory = { "key-1" },
          plainTextKeyFactory = { PLAIN_TEXT_KEY },
          currentTimeMillis = { 1_000 },
        )

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(userLifecycle, apiKeyLifecycle)
          routing {
            komgaClaimRoutes(userLifecycle)
            komgaAuthenticatedUserRoutes(
              users = userLifecycle,
              libraries = JooqLibraryRepository(database),
              apiKeys = apiKeyLifecycle,
            )
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
            header("X-Komga-Email", "admin@example.invalid")
            header("X-Komga-Password", "synthetic-password")
          }.status,
        )

        val createdResponse =
          client.post("/api/v2/users/me/api-keys") {
            basicAuth("admin@example.invalid", "synthetic-password")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ApiKeyRequestDto(" Synthetic client "))
          }
        assertEquals(HttpStatusCode.OK, createdResponse.status)
        val created = createdResponse.body<ApiKeyDto>()
        assertEquals("key-1", created.id)
        assertEquals("user-1", created.userId)
        assertEquals(PLAIN_TEXT_KEY, created.key)
        assertEquals("Synthetic client", created.comment)
        assertEquals("1970-01-01T00:00:01Z", created.createdDate)
        assertEquals(created.createdDate, created.lastModifiedDate)

        val stored = requireNotNull(apiKeyRepository.findByKeyHashOrNull(tokenEncoder.encode(PLAIN_TEXT_KEY)))
        assertNotEquals(PLAIN_TEXT_KEY, stored.keyHash)
        assertEquals(128, stored.keyHash.length)

        val listed =
          client.get("/api/v2/users/me/api-keys") {
            basicAuth("admin@example.invalid", "synthetic-password")
          }.body<List<ApiKeyDto>>()
        assertEquals(1, listed.size)
        assertEquals("******", listed.single().key)

        val authenticated =
          client.get("/api/v2/users/me") {
            header(KOMGA_API_KEY_HEADER, PLAIN_TEXT_KEY)
          }
        assertEquals(HttpStatusCode.OK, authenticated.status)
        assertEquals("admin@example.invalid", authenticated.body<UserDto>().email)
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v2/users/me") {
            header(KOMGA_API_KEY_HEADER, "wrong-api-key")
          }.status,
        )

        val duplicate =
          client.post("/api/v2/users/me/api-keys") {
            basicAuth("admin@example.invalid", "synthetic-password")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ApiKeyRequestDto("synthetic CLIENT"))
          }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status)
        assertEquals("ERR_1034", duplicate.body<KomgaErrorResponse>().message)

        assertEquals(
          HttpStatusCode.NoContent,
          client.delete("/api/v2/users/me/api-keys/${created.id}") {
            header(KOMGA_API_KEY_HEADER, PLAIN_TEXT_KEY)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v2/users/me") {
            header(KOMGA_API_KEY_HEADER, PLAIN_TEXT_KEY)
          }.status,
        )
        assertTrue(apiKeyLifecycle.findAll(io.xoboro.core.domain.UserId("user-1")).isEmpty())
        assertEquals(false, apiKeyLifecycle.delete(io.xoboro.core.domain.UserId("user-1"), ApiKeyId("key-1")))
      }
    }
  }

  private companion object {
    const val PLAIN_TEXT_KEY: String = "0123456789abcdef0123456789abcdef"
    val komgaJson =
      Json {
        explicitNulls = false
      }
  }
}
