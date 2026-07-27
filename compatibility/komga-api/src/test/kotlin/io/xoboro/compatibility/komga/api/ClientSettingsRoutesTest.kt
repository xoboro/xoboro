package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqClientSettingsRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class ClientSettingsRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `matches global and per-user Komga client settings behavior`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("client-settings.sqlite"))).use {
        database ->
      val users = JooqUserRepository(database)
      val ids = listOf("admin-1", "reader-1").iterator()
      val lifecycle =
        UserLifecycle(
          users = users,
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = ids::next,
          currentTimeMillis = AtomicLong(1_000)::get,
        )
      val settings = ClientSettingsLifecycle(JooqClientSettingsRepository(database))

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(lifecycle)
          routing {
            komgaClaimRoutes(lifecycle)
            komgaAuthenticatedUserRoutes(
              users = lifecycle,
              libraries = JooqLibraryRepository(database),
            )
            komgaClientSettingsRoutes(settings)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(komgaJson)
            }
          }
        client.post("/api/v1/claim") {
          header("X-Komga-Email", ADMIN_EMAIL)
          header("X-Komga-Password", ADMIN_PASSWORD)
        }
        lifecycle.createUser(READER_EMAIL, READER_PASSWORD)

        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/client-settings/global") {
            adminCredentials()
            jsonBody(
              mapOf(
                "application.public" to
                  ClientSettingGlobalUpdateDto("visible", allowUnauthorized = true),
                "application.private" to
                  ClientSettingGlobalUpdateDto("hidden", allowUnauthorized = false),
              ),
            )
          }.status,
        )
        val anonymous =
          client.get("/api/v1/client-settings/global/list")
            .body<Map<String, ClientSettingDto>>()
        assertEquals(setOf("application.public"), anonymous.keys)
        assertTrue(anonymous.getValue("application.public").allowUnauthorized == true)

        val authenticated =
          client.get("/api/v1/client-settings/global/list") {
            readerCredentials()
          }.body<Map<String, ClientSettingDto>>()
        assertEquals(setOf("application.public", "application.private"), authenticated.keys)

        assertEquals(
          HttpStatusCode.Forbidden,
          client.patch("/api/v1/client-settings/global") {
            readerCredentials()
            jsonBody(
              mapOf(
                "application.forbidden" to
                  ClientSettingGlobalUpdateDto("unused", allowUnauthorized = false),
              ),
            )
          }.status,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/client-settings/user") {
            readerCredentials()
            jsonBody(mapOf("reader.layout" to ClientSettingUserUpdateDto("compact")))
          }.status,
        )
        val readerSettings =
          client.get("/api/v1/client-settings/user/list") {
            readerCredentials()
          }.body<Map<String, ClientSettingDto>>()
        assertEquals("compact", readerSettings.getValue("reader.layout").value)
        assertEquals(null, readerSettings.getValue("reader.layout").allowUnauthorized)
        assertTrue(
          client.get("/api/v1/client-settings/user/list") {
            adminCredentials()
          }.body<Map<String, ClientSettingDto>>().isEmpty(),
        )

        assertEquals(
          HttpStatusCode.BadRequest,
          client.patch("/api/v1/client-settings/user") {
            readerCredentials()
            jsonBody(mapOf("Invalid Key" to ClientSettingUserUpdateDto("")))
          }.status,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.delete("/api/v1/client-settings/user") {
            readerCredentials()
            jsonBody(setOf("reader.layout"))
          }.status,
        )
        assertTrue(
          client.get("/api/v1/client-settings/user/list") {
            readerCredentials()
          }.body<Map<String, ClientSettingDto>>().isEmpty(),
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.delete("/api/v1/client-settings/global") {
            adminCredentials()
            jsonBody(setOf("application.private"))
          }.status,
        )
        assertFalse(
          client.get("/api/v1/client-settings/global/list") {
            adminCredentials()
          }.body<Map<String, ClientSettingDto>>().containsKey("application.private"),
        )
      }
    }
  }

  private fun io.ktor.client.request.HttpRequestBuilder.adminCredentials() {
    basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.readerCredentials() {
    basicAuth(READER_EMAIL, READER_PASSWORD)
  }

  private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.jsonBody(value: T) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    setBody(value)
  }

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-admin-password"
    const val READER_EMAIL = "reader@example.invalid"
    const val READER_PASSWORD = "synthetic-reader-password"
    val komgaJson = Json { explicitNulls = false }
  }
}
