package io.xoboro.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ClosedWriteChannelException
import io.xoboro.compatibility.komga.api.ClaimStatusDto
import io.xoboro.compatibility.komga.api.LibraryCreationDto
import io.xoboro.compatibility.komga.api.LibraryDto
import io.xoboro.compatibility.komga.api.KomgaErrorResponse
import io.xoboro.compatibility.komga.api.OAuth2ClientDto
import io.xoboro.compatibility.komga.api.UserDto
import io.xoboro.server.api.LoginRequest
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.SetupStatusResponse
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XoboroApiError
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import org.slf4j.Logger

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
  fun `rejects spoofed forwarding headers from a direct client`() =
    testApplication {
      application {
        xoboroModule()
      }

      val response =
        client.get("/health") {
          header(HttpHeaders.XForwardedFor, "198.51.100.20")
        }

      assertEquals(HttpStatusCode.BadRequest, response.status)
    }

  @Test
  fun `ordinary requests do not resolve the physical peer`() =
    testApplication {
      application {
        installTrustedProxyHeaders(setOf("127.0.0.1")) {
          error("ordinary requests must not inspect the physical peer")
        }
        routing {
          get("/ordinary") {
            call.respondText("ok")
          }
        }
      }

      val response = client.get("/ordinary")

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("ok", response.bodyAsText())
    }

  @Test
  fun `preserves the configured CORS rejection body`() =
    testApplication {
      application {
        xoboroModule(corsAllowedOrigins = setOf("https://reader.example.invalid"))
      }

      val response =
        client.get("/api/v1/claim") {
          header(HttpHeaders.Origin, "https://denied.example.invalid")
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("Invalid CORS request", response.bodyAsText())
    }

  @Test
  fun `uses forwarding headers only when the physical peer is trusted`() {
    var peerAddressReads = 0
    testApplication {
      application {
        installTrustedProxyHeaders(setOf("127.0.0.1")) {
          peerAddressReads += 1
          "127.0.0.1"
        }
        routing {
          get("/origin") {
            val origin = call.request.origin
            call.respondText("${origin.scheme}|${origin.serverHost}|${origin.remoteHost}")
          }
        }
      }

      val response =
        client.get("/origin") {
          header(
            HttpHeaders.Forwarded,
            "for=198.51.100.21;proto=https;host=reader.example",
          )
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("https|reader.example|198.51.100.21", response.bodyAsText())

      val xForwardedResponse =
        client.get("/origin") {
          header(HttpHeaders.XForwardedFor, "198.51.100.22")
          header(HttpHeaders.XForwardedProto, "https")
          header(HttpHeaders.XForwardedHost, "reader-x.example")
        }
      assertEquals(HttpStatusCode.OK, xForwardedResponse.status)
      assertEquals("https|reader-x.example|198.51.100.22", xForwardedResponse.bodyAsText())
      assertEquals(2, peerAddressReads)
    }
  }

  @Test
  fun `protects bounded Prometheus metrics with a dedicated token`() =
    testApplication {
      application {
        xoboroModule(
          readiness = { false },
          metricsToken = "synthetic-metrics-token-000000000",
          taskQueueSize = { 7 },
          workerCount = { 3 },
        )
      }

      assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
      val response =
        client.get("/metrics") {
          header(HttpHeaders.Authorization, "Bearer synthetic-metrics-token-000000000")
        }
      val body = response.bodyAsText()

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.headers[HttpHeaders.ContentType]?.startsWith("text/plain") == true)
      assertTrue(body.contains("xoboro_http_requests_total{method=\"GET\",status=\"4xx\"} 1"))
      assertTrue(body.contains("xoboro_ready 0"))
      assertTrue(body.contains("xoboro_task_queue_size 7"))
      assertTrue(body.contains("xoboro_task_workers 3"))
      assertFalse(body.contains("/metrics"))
      assertFalse(body.contains("synthetic-metrics-token"))
    }

  @Test
  fun `does not expose a metrics route without an explicit token`() =
    testApplication {
      application {
        xoboroModule()
      }

      assertEquals(HttpStatusCode.NotFound, client.get("/metrics").status)
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
  fun `production status pages treats a closed SSE response as normal completion`() =
    testApplication {
      var unhandledFailures = 0
      environment {
        log = recordingLogger { unhandledFailures += 1 }
      }
      application {
        xoboroModule()
        routing {
          get("/synthetic-sse-disconnect") {
            val responseChannel = ByteChannel()
            responseChannel.close()
            SSEServerContent(call) {
              send(ServerSentEvent(data = "too-late"))
            }.writeTo(responseChannel)
          }
        }
      }

      val response = client.get("/synthetic-sse-disconnect")

      assertFalse(response.bodyAsText().contains("internal_error"))
      assertEquals(0, unhandledFailures)
    }

  @Test
  fun `production status pages treats an engine-wrapped closed response as normal completion`() =
    testApplication {
      var unhandledFailures = 0
      environment {
        log = recordingLogger { unhandledFailures += 1 }
      }
      application {
        xoboroModule()
        routing {
          get("/synthetic-engine-disconnect") {
            throw ChannelWriteException(
              "Synthetic client disconnect",
              ClosedWriteChannelException(),
            )
          }
        }
      }

      val response = client.get("/synthetic-engine-disconnect")

      assertFalse(response.bodyAsText().contains("internal_error"))
      assertEquals(0, unhandledFailures)
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

  @Test
  fun `production module exposes restart-safe native authentication`() {
    val runtime =
      XoboroRuntime.open(
        ServerConfig(
          port = 25_600,
          databasePath = tempDirectory.resolve("native-authentication.sqlite"),
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

      assertEquals(
        SetupStatusResponse(claimed = false),
        client.get("$XOBORO_API_PREFIX/setup").body(),
      )
      val setup =
        client.post("$XOBORO_API_PREFIX/setup") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            SetupRequest(
              email = "admin@example.invalid",
              password = "synthetic-password",
              transport = SessionTransport.BEARER,
            ),
          )
        }
      assertEquals(HttpStatusCode.Created, setup.status)
      val accessToken = requireNotNull(setup.body<SessionResponse>().accessToken)
      assertEquals(
        "admin@example.invalid",
        client
          .get("$XOBORO_API_PREFIX/session") {
            bearerAuth(accessToken)
          }.body<SessionResponse>()
          .user.email,
      )

      val malformed =
        client.post("$XOBORO_API_PREFIX/session") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":""}""")
        }
      assertEquals(HttpStatusCode.BadRequest, malformed.status)
      assertEquals("invalid_request", malformed.body<XoboroApiError>().code)

      repeat(9) {
        assertEquals(
          HttpStatusCode.Unauthorized,
          client
            .post("$XOBORO_API_PREFIX/session") {
              header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
              setBody(
                LoginRequest(
                  email = "missing@example.invalid",
                  password = "wrong-password",
                  transport = SessionTransport.BEARER,
                ),
              )
            }.status,
        )
      }
      val limited =
        client.post("$XOBORO_API_PREFIX/session") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            LoginRequest(
              email = "missing@example.invalid",
              password = "wrong-password",
              transport = SessionTransport.BEARER,
            ),
          )
        }
      assertEquals(HttpStatusCode.TooManyRequests, limited.status)
      assertEquals("rate_limit_exceeded", limited.body<XoboroApiError>().code)
      assertNotNull(limited.headers[HttpHeaders.RetryAfter])
    }

    assertFalse(runtime.isReady())
  }

  @Test
  fun `production module renders authenticated missing resources as Spring errors`() {
    val runtime =
      XoboroRuntime.open(
        ServerConfig(
          port = 25_600,
          databasePath = tempDirectory.resolve("missing-resource.sqlite"),
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
      client.post("/api/v1/claim") {
        header("X-Komga-Email", "admin@example.invalid")
        header("X-Komga-Password", "synthetic-password")
      }

      val response =
        client.get("/api/v1/books/missing-synthetic") {
          basicAuth("admin@example.invalid", "synthetic-password")
        }
      val error = Json.decodeFromString<KomgaErrorResponse>(response.bodyAsText())

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("application/json", response.headers[HttpHeaders.ContentType])
      assertTrue(OffsetDateTime.parse(error.timestamp).year >= 2026)
      assertEquals(404, error.status)
      assertEquals("Not Found", error.error)
      assertEquals("404 NOT_FOUND", error.message)
      assertEquals("/api/v1/books/missing-synthetic", error.path)
    }
  }

  @Test
  fun `production module maps malformed Komga JSON requests to bad request`() {
    val runtime =
      XoboroRuntime.open(
        ServerConfig(
          port = 25_600,
          databasePath = tempDirectory.resolve("malformed-json.sqlite"),
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
      client.post("/api/v1/claim") {
        header("X-Komga-Email", "admin@example.invalid")
        header("X-Komga-Password", "synthetic-password")
      }

      val response =
        client.post("/api/v2/users") {
          basicAuth("admin@example.invalid", "synthetic-password")
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":""}""")
        }
      val error = Json.decodeFromString<KomgaErrorResponse>(response.bodyAsText())

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(OffsetDateTime.parse(error.timestamp).year >= 2026)
      assertEquals(400, error.status)
      assertEquals("Bad Request", error.error)
      assertTrue(error.message.isNotBlank())
      assertEquals("/api/v2/users", error.path)
    }
  }

  private fun recordingLogger(onUnhandledFailure: () -> Unit): Logger =
    Proxy.newProxyInstance(
      Logger::class.java.classLoader,
      arrayOf(Logger::class.java),
    ) { _, method, arguments ->
      if (
        method.name == "error" &&
          arguments?.firstOrNull() == "Unhandled request failure"
      ) {
        onUnhandledFailure()
      }
      if (method.returnType == Boolean::class.javaPrimitiveType) false else null
    } as Logger
}
