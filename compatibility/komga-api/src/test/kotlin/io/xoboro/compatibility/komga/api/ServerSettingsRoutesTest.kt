package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
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
import io.xoboro.core.application.ServerSettingsLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqServerSettingRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class ServerSettingsRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `matches Komga server settings authorization updates and null clearing`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("settings.sqlite"))).use { database ->
      val users = JooqUserRepository(database)
      val userIds = listOf("admin-1", "reader-1").iterator()
      val userLifecycle =
        UserLifecycle(
          users = users,
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = userIds::next,
          currentTimeMillis = AtomicLong(1_000)::get,
        )
      val workerSizes = mutableListOf<Int>()
      var keySequence = 0
      val settings =
        ServerSettingsLifecycle(
          store = JooqServerSettingRepository(database),
          configuredServerPort = 8_080,
          effectiveServerPort = { 8_081 },
          configuredServerContextPath = "/configured",
          effectiveServerContextPath = { "/effective" },
          configuredKepubifyPath = "/configured/kepubify",
          effectiveKepubifyPath = { "/effective/kepubify" },
          defaultTaskPoolSize = 3,
          rememberMeKeyFactory = {
            keySequence += 1
            "synthetic-key-$keySequence"
          },
          onTaskPoolSizeChanged = workerSizes::add,
        )

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(userLifecycle)
          routing {
            komgaClaimRoutes(userLifecycle)
            komgaServerSettingsRoutes(settings)
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
        userLifecycle.createUser(READER_EMAIL, READER_PASSWORD)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/settings").status)
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/api/v1/settings") { readerCredentials() }.status,
        )
        val initialWire =
          client.get("/api/v1/settings") {
            adminCredentials()
          }.body<JsonObject>()
        assertEquals(JsonNull, initialWire.getValue("koboPort"))
        assertEquals(
          JsonNull,
          initialWire
            .getValue("serverPort")
            .let { it as JsonObject }
            .getValue("databaseSource"),
        )

        val initial =
          client.get("/api/v1/settings") {
            adminCredentials()
          }.body<SettingsDto>()
        assertEquals(365, initial.rememberMeDurationDays)
        assertEquals(ThumbnailSizeDto.DEFAULT, initial.thumbnailSize)
        assertEquals(3, initial.taskPoolSize)
        assertEquals(8_080, initial.serverPort.configurationSource)
        assertNull(initial.serverPort.databaseSource)
        assertEquals(8_081, initial.serverPort.effectiveValue)
        val originalKey = settings.rememberMeKey()

        val update =
          buildJsonObject {
            put("deleteEmptyCollections", true)
            put("deleteEmptyReadLists", true)
            put("rememberMeDurationDays", 14)
            put("renewRememberMeKey", true)
            put("thumbnailSize", "XLARGE")
            put("taskPoolSize", 7)
            put("serverPort", 9_001)
            put("serverContextPath", "/reader")
            put("koboProxy", true)
            put("koboPort", 9_002)
            put("kepubifyPath", "/tools/kepubify")
          }
        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/settings") {
            adminCredentials()
            jsonBody(update)
          }.status,
        )

        val changed =
          client.get("/api/v1/settings") {
            adminCredentials()
          }.body<SettingsDto>()
        assertEquals(true, changed.deleteEmptyCollections)
        assertEquals(true, changed.deleteEmptyReadLists)
        assertEquals(14, changed.rememberMeDurationDays)
        assertEquals(ThumbnailSizeDto.XLARGE, changed.thumbnailSize)
        assertEquals(7, changed.taskPoolSize)
        assertEquals(9_001, changed.serverPort.databaseSource)
        assertEquals("/reader", changed.serverContextPath.databaseSource)
        assertEquals(true, changed.koboProxy)
        assertEquals(9_002, changed.koboPort)
        assertEquals("/tools/kepubify", changed.kepubifyPath.databaseSource)
        assertEquals(listOf(7), workerSizes)
        assertNotEquals(originalKey, settings.rememberMeKey())

        val clear =
          buildJsonObject {
            put("serverPort", JsonNull)
            put("serverContextPath", JsonNull)
            put("koboPort", JsonNull)
            put("kepubifyPath", JsonNull)
          }
        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/settings") {
            adminCredentials()
            jsonBody(clear)
          }.status,
        )
        val cleared =
          client.get("/api/v1/settings") {
            adminCredentials()
          }.body<SettingsDto>()
        assertNull(cleared.serverPort.databaseSource)
        assertNull(cleared.serverContextPath.databaseSource)
        assertNull(cleared.koboPort)
        assertNull(cleared.kepubifyPath.databaseSource)

        listOf(
          buildJsonObject { put("rememberMeDurationDays", 0) },
          buildJsonObject { put("taskPoolSize", 0) },
          buildJsonObject { put("serverPort", 65_536) },
          buildJsonObject { put("serverContextPath", "reader") },
        ).forEach { invalid ->
          assertEquals(
            HttpStatusCode.BadRequest,
            client.patch("/api/v1/settings") {
              adminCredentials()
              jsonBody(invalid)
            }.status,
          )
        }
      }
    }
  }

  private fun io.ktor.client.request.HttpRequestBuilder.adminCredentials() {
    basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.readerCredentials() {
    basicAuth(READER_EMAIL, READER_PASSWORD)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(value: kotlinx.serialization.json.JsonObject) {
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
