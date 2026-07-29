package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.server.api.LoginRequest
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XoboroApiError
import io.xoboro.server.api.XoboroLibraryAdministrationRequest
import io.xoboro.server.api.XoboroLibraryResponse
import io.xoboro.server.api.XoboroLibrarySourceRequest
import io.xoboro.server.api.XoboroUserCreationRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.io.TempDir

/**
 * Exercises the native event stream through the same production wiring path as
 * [XoboroNativeCatalogApplicationTest] and [XoboroNativeOpsApplicationTest] — a real SQLite
 * database and a real [XoboroRuntime] rather than the in-memory fakes used by
 * `XoboroNativeEventRoutesTest` in `server:api`. That route-level suite already covers the
 * resume/backpressure/revocation contract in isolation; this suite proves the endpoint is
 * actually mounted, that a real domain mutation reaches it through
 * `XoboroNativeEventBridge`, and that per-subscriber library-grant filtering holds end to end.
 */
class XoboroNativeEventApplicationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `requires authentication and opens with a fresh ready frame`() {
    val databasePath = tempDirectory.resolve("events-auth.sqlite")
    val runtime = openRuntime(databasePath)

    testApplication {
      application { xoboroModule(runtime) }
      val client = sseClient()
      val adminToken = adminToken(client)

      val unauthenticated = client.get("$XOBORO_API_PREFIX/events")
      assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
      assertEquals("authentication_required", unauthenticated.body<XoboroApiError>().code)

      withTimeout(TEST_TIMEOUT) {
        client.sse(
          urlString = "$XOBORO_API_PREFIX/events",
          request = { bearerAuth(adminToken) },
        ) {
          assertEquals("stream.ready", incoming.first().event)
        }
      }
    }
  }

  @Test
  fun `a real domain mutation produces the expected native event on a connected stream`() {
    val databasePath = tempDirectory.resolve("events-mutation.sqlite")
    val runtime = openRuntime(databasePath)
    val libraryRoot = Files.createDirectories(tempDirectory.resolve("library-root"))

    testApplication {
      application { xoboroModule(runtime) }
      val client = sseClient()
      val adminToken = adminToken(client)

      var created: XoboroLibraryResponse? = null
      withTimeout(TEST_TIMEOUT) {
        client.sse(
          urlString = "$XOBORO_API_PREFIX/events",
          request = { bearerAuth(adminToken) },
        ) {
          // A single collection of `incoming`: it is a cold flow over the raw byte channel, so a
          // second, separate `first()` call on the same session would restart the parser against
          // a channel the first call already partly consumed. The mutation is triggered from
          // inside the predicate the first time it sees the leading `stream.ready` frame, which
          // guarantees the subscription is already registered server-side (the route's guard
          // calls `hub.subscribe()` and commits response headers before the `sse {}` handler,
          // and this client only starts receiving once those headers arrive).
          val event =
            incoming.first { frame ->
              if (frame.event == "stream.ready") {
                created = createLibrary(client, adminToken, libraryRoot)
                false
              } else {
                frame.event == "library.added"
              }
            }
          assertEquals("library.added", event.event)
          assertEquals(
            """{"ids":["${requireNotNull(created).id}"]}""",
            event.data,
          )
        }
      }
    }
  }

  @Test
  fun `a subscriber without the library grant does not receive the event`() {
    val databasePath = tempDirectory.resolve("events-unauthorized.sqlite")
    val runtime = openRuntime(databasePath)
    val libraryRoot = Files.createDirectories(tempDirectory.resolve("library-root"))

    testApplication {
      application { xoboroModule(runtime) }
      val client = sseClient()
      val adminToken = adminToken(client)

      val restrictedCreated =
        client.post("$XOBORO_API_PREFIX/users") {
          bearerAuth(adminToken)
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            XoboroUserCreationRequest(
              email = "restricted@example.invalid",
              password = "synthetic-password",
              sharedLibraryIds = emptyList(),
              sharesAllLibraries = false,
            ),
          )
        }
      assertEquals(HttpStatusCode.Created, restrictedCreated.status)

      val restrictedSession =
        client.post("$XOBORO_API_PREFIX/session") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            LoginRequest(
              email = "restricted@example.invalid",
              password = "synthetic-password",
              transport = SessionTransport.BEARER,
            ),
          )
        }
      assertEquals(HttpStatusCode.OK, restrictedSession.status)
      val restrictedToken =
        requireNotNull(restrictedSession.body<SessionResponse>().accessToken)

      var observed: String? = null
      withTimeout(TEST_TIMEOUT) {
        client.sse(
          urlString = "$XOBORO_API_PREFIX/events",
          request = { bearerAuth(restrictedToken) },
        ) {
          // Single collection, as above. The predicate never legitimately matches here — the
          // restricted subscriber has no library grant at all — so the whole call is wrapped in
          // `withTimeoutOrNull` rather than depending on a second, separate collection attempt.
          observed =
            withTimeoutOrNull(NO_EVENT_WINDOW) {
              incoming.first { frame ->
                if (frame.event == "stream.ready") {
                  createLibrary(client, adminToken, libraryRoot)
                  false
                } else {
                  frame.event == "library.added"
                }
              }
            }?.event
        }
      }
      assertNull(observed, "a subscriber without any library grant must not see library.added")
    }
  }

  private fun openRuntime(databasePath: Path): XoboroRuntime =
    XoboroRuntime.open(
      ServerConfig(
        port = 25_600,
        databasePath = databasePath,
        workerCount = 1,
        taskPollMillis = 10,
        taskFailurePollMillis = 10,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
      ),
    )

  private fun ApplicationTestBuilder.sseClient(): HttpClient =
    createClient {
      install(ContentNegotiation) { json() }
      install(SSE)
    }

  private suspend fun adminToken(client: HttpClient): String {
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
    return requireNotNull(setup.body<SessionResponse>().accessToken)
  }

  private suspend fun createLibrary(
    client: HttpClient,
    adminToken: String,
    root: Path,
  ): XoboroLibraryResponse {
    val response =
      client.post("$XOBORO_API_PREFIX/libraries") {
        bearerAuth(adminToken)
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
          XoboroLibraryAdministrationRequest(
            name = "Synthetic library",
            source =
              XoboroLibrarySourceRequest(
                provider = "local",
                location = root.toUri().toString(),
              ),
          ),
        )
      }
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
  }

  private companion object {
    val TEST_TIMEOUT = 5.seconds
    val NO_EVENT_WINDOW = 1500.milliseconds
  }
}
