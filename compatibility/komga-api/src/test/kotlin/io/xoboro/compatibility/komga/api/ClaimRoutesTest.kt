package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class ClaimRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `reports claim status and creates a Komga-compatible administrator`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("claim.sqlite"))).use { database ->
      val repository = JooqUserRepository(database)
      val hasher = AdaptivePasswordHasher()
      val lifecycle = lifecycle(repository, hasher)

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          routing {
            komgaClaimRoutes(lifecycle)
          }
        }
        val client = jsonClient()

        assertEquals(ClaimStatusDto(false), client.get("/api/v1/claim").body())

        val response =
          client.post("/api/v1/claim") {
            header("X-Komga-Email", "admin@example.invalid")
            header("X-Komga-Password", "synthetic-password")
          }

        assertEquals(HttpStatusCode.OK, response.status)
        val responseBody = response.bodyAsText()
        val user = Json.decodeFromString<UserDto>(responseBody)
        assertEquals("user-1", user.id)
        assertEquals("admin@example.invalid", user.email)
        assertEquals(UserRole.entries.mapTo(mutableSetOf("USER")) { it.name }, user.roles)
        assertTrue(user.sharedAllLibraries)
        assertTrue(user.sharedLibrariesIds.isEmpty())
        assertTrue(user.labelsAllow.isEmpty())
        assertTrue(user.labelsExclude.isEmpty())
        assertEquals(null, user.ageRestriction)
        assertFalse(responseBody.contains("password", ignoreCase = true))
        assertFalse(responseBody.contains("ageRestriction"))
        assertEquals(ClaimStatusDto(true), client.get("/api/v1/claim").body())

        val stored = requireNotNull(repository.findByEmailIgnoreCaseOrNull("ADMIN@example.invalid"))
        assertNotEquals("synthetic-password", stored.passwordHash)
        assertTrue(hasher.matches("synthetic-password", stored.passwordHash))
      }
    }
  }

  @Test
  fun `rejects missing invalid and blank credential headers`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("validation.sqlite"))).use { database ->
      val repository = JooqUserRepository(database)
      val lifecycle = lifecycle(repository, AdaptivePasswordHasher())

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          routing {
            komgaClaimRoutes(lifecycle)
          }
        }
        val client = jsonClient()

        val missing = client.post("/api/v1/claim")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals(2, missing.body<ValidationErrorResponse>().violations.size)

        val invalid =
          client.post("/api/v1/claim") {
            header("X-Komga-Email", "invalid-email")
            header("X-Komga-Password", " ")
          }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(
          listOf("claimServer.email", "claimServer.password"),
          invalid.body<ValidationErrorResponse>().violations.map(ViolationDto::fieldName),
        )
        assertEquals(0, repository.count())
      }
    }
  }

  @Test
  fun `rejects a second claim without replacing the administrator`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("claimed.sqlite"))).use { database ->
      val repository = JooqUserRepository(database)
      val lifecycle = lifecycle(repository, AdaptivePasswordHasher())

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          routing {
            komgaClaimRoutes(lifecycle)
          }
        }
        val client = jsonClient()
        client.post("/api/v1/claim") {
          header("X-Komga-Email", "first@example.invalid")
          header("X-Komga-Password", "first-password")
        }

        val rejected =
          client.post("/api/v1/claim") {
            header("X-Komga-Email", "second@example.invalid")
            header("X-Komga-Password", "second-password")
          }

        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertEquals(
          "This server has already been claimed",
          rejected.body<KomgaErrorResponse>().message,
        )
        assertEquals("first@example.invalid", repository.findAll().single().email)
      }
    }
  }

  private fun lifecycle(
    repository: JooqUserRepository,
    hasher: AdaptivePasswordHasher,
  ): UserLifecycle =
    UserLifecycle(
      users = repository,
      passwordHasher = hasher,
      userIdFactory = { "user-1" },
      currentTimeMillis = { 1_000 },
    )

  private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
    createClient {
      install(ContentNegotiation) {
        json(komgaJson)
      }
    }

  private companion object {
    val komgaJson =
      Json {
        explicitNulls = false
      }
  }
}
