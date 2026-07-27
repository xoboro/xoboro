package io.xoboro.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.xoboro.compatibility.komga.api.ClaimStatusDto
import io.xoboro.compatibility.komga.api.LibraryCreationDto
import io.xoboro.compatibility.komga.api.LibraryDto
import io.xoboro.compatibility.komga.api.OAuth2ClientDto
import io.xoboro.compatibility.komga.api.UserDto
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ApplicationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `health endpoint reports an available Xoboro service`() =
    testApplication {
      application {
        xoboroModule()
      }
      val client =
        createClient {
          install(ContentNegotiation) {
            json()
          }
        }

      val response = client.get("/health")

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(HealthResponse(), response.body())
    }

  @Test
  fun `readiness endpoint reflects runtime availability`() =
    testApplication {
      application {
        xoboroModule(readiness = { false })
      }
      val client =
        createClient {
          install(ContentNegotiation) {
            json()
          }
        }

      val response = client.get("/ready")

      assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
      assertEquals(HealthResponse(status = "DOWN"), response.body())
    }

  @Test
  fun `mounts every endpoint beneath the effective context path`() =
    testApplication {
      application {
        xoboroModule(contextPath = "/reader")
      }

      assertEquals(HttpStatusCode.NotFound, client.get("/health").status)
      assertEquals(HttpStatusCode.OK, client.get("/reader/health").status)
      assertEquals(HttpStatusCode.OK, client.get("/reader/ready").status)
    }

  @Test
  fun `application stop closes its runtime owner`() {
    var stopped = false

    testApplication {
      application {
        xoboroModule(onStop = { stopped = true })
      }
      startApplication()
    }

    assertEquals(true, stopped)
  }

  @Test
  fun `production module exposes the persistent Komga claim API`() {
    val runtime =
      XoboroRuntime.open(
        ServerConfig(
          port = 25_600,
          databasePath = tempDirectory.resolve("application.sqlite"),
          workerCount = 1,
          taskPollMillis = 10,
          taskFailurePollMillis = 10,
          taskLeaseMillis = 1_000,
          shutdownTimeoutMillis = 2_000,
        ),
      )

    testApplication {
      application {
        xoboroModule(runtime)
      }
      val client =
        createClient {
          install(ContentNegotiation) {
            json()
          }
        }

      assertEquals(ClaimStatusDto(false), client.get("/api/v1/claim").body())
      assertEquals(
        emptyList<OAuth2ClientDto>(),
        client.get("/api/v1/oauth2/providers").body(),
      )
      val claimed =
        client.post("/api/v1/claim") {
          header("X-Komga-Email", "admin@example.invalid")
          header("X-Komga-Password", "synthetic-password")
        }
      assertEquals(HttpStatusCode.OK, claimed.status)
      assertTrue(claimed.body<UserDto>().id.matches(Regex("[0-9A-HJKMNP-TV-Z]{13}")))
      assertEquals(ClaimStatusDto(true), client.get("/api/v1/claim").body())

      val root = java.nio.file.Files.createDirectories(tempDirectory.resolve("runtime-library"))
      val library =
        client.post("/api/v1/libraries") {
          basicAuth("admin@example.invalid", "synthetic-password")
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(LibraryCreationDto("Synthetic runtime library", root.toString()))
        }
      assertEquals(HttpStatusCode.OK, library.status)
      val created = library.body<LibraryDto>()
      assertEquals(root.toString(), created.root)
      assertEquals(
        created,
        client.get("/api/v1/libraries/${created.id}") {
          basicAuth("admin@example.invalid", "synthetic-password")
        }.body(),
      )
    }

    assertFalse(runtime.isReady())
  }
}
