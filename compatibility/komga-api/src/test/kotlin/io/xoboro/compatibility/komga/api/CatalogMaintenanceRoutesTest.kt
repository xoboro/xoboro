package io.xoboro.compatibility.komga.api

import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.CatalogMaintenanceRequester
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class CatalogMaintenanceRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `protects target maintenance and returns Komga status semantics`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("maintenance-api.sqlite"))).use {
        database ->
      var userSequence = 0
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-${++userSequence}" },
          currentTimeMillis = { 10 },
        )
      val requester = RecordingCatalogMaintenance()

      testApplication {
        application {
          install(ContentNegotiation) {
            json()
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaCatalogMaintenanceRoutes(requester)
          }
        }
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )
        users.createUser(
          email = USER_EMAIL,
          rawPassword = USER_PASSWORD,
          roles = emptySet<UserRole>(),
        )

        assertEquals(
          HttpStatusCode.Forbidden,
          client.post("/api/v1/books/book-1/analyze") {
            basicAuth(USER_EMAIL, USER_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Accepted,
          client.post("/api/v1/books/book-1/analyze") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.NotFound,
          client.post("/api/v1/books/missing/metadata/refresh") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Accepted,
          client.post("/api/v1/series/unknown/analyze") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        val clear =
          client.delete("/api/v1/tasks") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, clear.status)
        assertEquals("3", clear.bodyAsText())
        assertEquals(
          listOf("analyze-book:book-1", "refresh-book:missing", "analyze-series:unknown", "clear"),
          requester.calls,
        )
      }
    }
  }

  private class RecordingCatalogMaintenance : CatalogMaintenanceRequester {
    val calls = mutableListOf<String>()

    override fun analyzeBook(id: BookId): Boolean {
      calls += "analyze-book:${id.value}"
      return id.value != "missing"
    }

    override fun analyzeSeries(id: SeriesId): Int {
      calls += "analyze-series:${id.value}"
      return 0
    }

    override fun refreshBookMetadata(id: BookId): Boolean {
      calls += "refresh-book:${id.value}"
      return id.value != "missing"
    }

    override fun refreshSeriesMetadata(id: SeriesId): Int {
      calls += "refresh-series:${id.value}"
      return 0
    }

    override fun clearUnclaimedTasks(): Int {
      calls += "clear"
      return 3
    }
  }

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-admin-password"
    const val USER_EMAIL = "reader@example.invalid"
    const val USER_PASSWORD = "synthetic-reader-password"
  }
}
