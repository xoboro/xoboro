package io.xoboro.compatibility.komga.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class UserRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `admin creates lists updates and deletes users with Komga wire semantics`() {
    withUserApi("crud.sqlite") { client, lifecycle, _, libraries ->
      client.claimAdministrator()
      libraries.insert(syntheticLibrary("library-1"))

      val createdResponse =
        client.post("/api/v2/users") {
          adminCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            UserCreationDto(
              email = "reader@example.invalid",
              password = "reader-password",
              roles = listOf("FILE_DOWNLOAD", "UNKNOWN"),
              ageRestriction = AgeRestrictionUpdateDto(12, AllowExcludeDto.ALLOW_ONLY),
              labelsAllow = setOf(" Family "),
              labelsExclude = setOf(" Restricted "),
              sharedLibraries =
                SharedLibrariesUpdateDto(
                  all = false,
                  libraryIds = setOf("library-1", "missing-library"),
                ),
            ),
          )
        }

      assertEquals(HttpStatusCode.Created, createdResponse.status)
      val created = createdResponse.body<UserDto>()
      assertEquals(setOf("USER", "FILE_DOWNLOAD"), created.roles)
      assertEquals(setOf("library-1"), created.sharedLibrariesIds)
      assertFalse(created.sharedAllLibraries)
      assertEquals(setOf("family"), created.labelsAllow)
      assertEquals(setOf("restricted"), created.labelsExclude)
      assertEquals(AgeRestrictionDto(12, io.xoboro.core.domain.RestrictionMode.ALLOW_ONLY), created.ageRestriction)

      val listed =
        client.get("/api/v2/users") {
          adminCredentials()
        }.body<List<UserDto>>()
      assertEquals(setOf("admin@example.invalid", "reader@example.invalid"), listed.mapTo(mutableSetOf(), UserDto::email))

      val updated =
        client.patch("/api/v2/users/${created.id}") {
          adminCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            """
            {
              "roles": ["PAGE_STREAMING"],
              "sharedLibraries": {"all": true, "libraryIds": []},
              "ageRestriction": null,
              "labelsAllow": [],
              "labelsExclude": null
            }
            """.trimIndent(),
          )
        }
      assertEquals(HttpStatusCode.NoContent, updated.status)
      val stored = requireNotNull(lifecycle.findByIdOrNull(io.xoboro.core.domain.UserId(created.id)))
      assertEquals(setOf(io.xoboro.core.domain.UserRole.PAGE_STREAMING), stored.roles)
      assertTrue(stored.sharesAllLibraries)
      assertTrue(stored.sharedLibraryIds.isEmpty())
      assertFalse(stored.restrictions.isRestricted)

      val deleted =
        client.delete("/api/v2/users/${created.id}") {
          adminCredentials()
        }
      assertEquals(HttpStatusCode.NoContent, deleted.status)
      assertNull(lifecycle.findByIdOrNull(io.xoboro.core.domain.UserId(created.id)))
      assertEquals(
        HttpStatusCode.NotFound,
        client.delete("/api/v2/users/${created.id}") { adminCredentials() }.status,
      )
    }
  }

  @Test
  fun `validates creation and enforces administrator-only user management`() {
    withUserApi("authorization.sqlite") { client, lifecycle, _, _ ->
      client.claimAdministrator()
      val reader = lifecycle.createUser("reader@example.invalid", "reader-password")

      val invalid =
        client.post("/api/v2/users") {
          adminCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(UserCreationDto(email = "invalid", password = " "))
        }
      assertEquals(HttpStatusCode.BadRequest, invalid.status)
      assertEquals(2, invalid.body<ValidationErrorResponse>().violations.size)

      val duplicate =
        client.post("/api/v2/users") {
          adminCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            UserCreationDto(
              email = "READER@example.invalid",
              password = "synthetic-password",
            ),
          )
        }
      assertEquals(HttpStatusCode.BadRequest, duplicate.status)

      assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v2/users") { readerCredentials() }.status,
      )
      assertEquals(
        HttpStatusCode.Forbidden,
        client.post("/api/v2/users") {
          readerCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            UserCreationDto(
              email = "other@example.invalid",
              password = "other-password",
            ),
          )
        }.status,
      )
      assertEquals(
        HttpStatusCode.Forbidden,
        client.delete("/api/v2/users/${reader.id.value}") { readerCredentials() }.status,
      )

      val admin = requireNotNull(lifecycle.authenticate("admin@example.invalid", "synthetic-password"))
      assertEquals(
        HttpStatusCode.Forbidden,
        client.patch("/api/v2/users/${admin.id.value}") {
          readerCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"roles": []}""")
        }.status,
      )
      assertEquals(
        HttpStatusCode.Forbidden,
        client.delete("/api/v2/users/${admin.id.value}") { adminCredentials() }.status,
      )
      assertEquals(
        HttpStatusCode.Forbidden,
        client.patch("/api/v2/users/${admin.id.value}") {
          adminCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"roles": []}""")
        }.status,
      )
      assertEquals(
        HttpStatusCode.NotFound,
        client.patch("/api/v2/users/missing-user") {
          adminCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"roles": []}""")
        }.status,
      )
    }
  }

  @Test
  fun `users change their own password while admins can reset another user`() {
    withUserApi("password.sqlite") { client, lifecycle, _, _ ->
      client.claimAdministrator()
      val reader = lifecycle.createUser("reader@example.invalid", "reader-password")

      val selfUpdate =
        client.patch("/api/v2/users/me/password") {
          readerCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(PasswordUpdateDto("updated-password"))
        }
      assertEquals(HttpStatusCode.NoContent, selfUpdate.status)
      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get("/api/v2/users/me") { readerCredentials() }.status,
      )
      assertEquals(
        HttpStatusCode.OK,
        client.get("/api/v2/users/me") {
          basicAuth("reader@example.invalid", "updated-password")
        }.status,
      )

      val admin = requireNotNull(lifecycle.authenticate("admin@example.invalid", "synthetic-password"))
      val forbidden =
        client.patch("/api/v2/users/${admin.id.value}/password") {
          basicAuth("reader@example.invalid", "updated-password")
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(PasswordUpdateDto("unused-password"))
        }
      assertEquals(HttpStatusCode.Forbidden, forbidden.status)

      val reset =
        client.patch("/api/v2/users/${reader.id.value}/password") {
          adminCredentials()
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(PasswordUpdateDto("reset-password"))
        }
      assertEquals(HttpStatusCode.NoContent, reset.status)
      assertEquals(
        HttpStatusCode.OK,
        client.get("/api/v2/users/me") {
          basicAuth("reader@example.invalid", "reset-password")
        }.status,
      )
    }
  }

  private fun withUserApi(
    databaseName: String,
    assertions:
      suspend ApplicationTestBuilder.(
        client: HttpClient,
        lifecycle: UserLifecycle,
        users: JooqUserRepository,
        libraries: JooqLibraryRepository,
      ) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve(databaseName))).use { database ->
      val users = JooqUserRepository(database)
      val libraries = JooqLibraryRepository(database)
      val lifecycle =
        UserLifecycle(
          users = users,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = sequenceIdFactory(),
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
            komgaAuthenticatedUserRoutes(lifecycle, libraries)
          }
        }
        val jsonClient =
          createClient {
            install(ContentNegotiation) {
              json(komgaJson)
            }
          }
        assertions(jsonClient, lifecycle, users, libraries)
      }
    }
  }

  private fun syntheticLibrary(id: String): Library =
    Library(
      id = LibraryId(id),
      name = "Synthetic library",
      root = SourceLocation("local", tempDirectory.resolve(id).toUri().toString()),
      createdAtMillis = 1,
    )

  private suspend fun HttpClient.claimAdministrator() {
    val response =
      post("/api/v1/claim") {
        header("X-Komga-Email", "admin@example.invalid")
        header("X-Komga-Password", "synthetic-password")
      }
    assertEquals(HttpStatusCode.OK, response.status)
  }

  private fun HttpRequestBuilder.adminCredentials() {
    basicAuth("admin@example.invalid", "synthetic-password")
  }

  private fun HttpRequestBuilder.readerCredentials() {
    basicAuth("reader@example.invalid", "reader-password")
  }

  private fun sequenceIdFactory(): () -> String {
    var next = 0
    return {
      next += 1
      "user-$next"
    }
  }

  private companion object {
    val komgaJson =
      Json {
        explicitNulls = false
      }
  }
}
