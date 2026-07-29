package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.client.plugins.sse.SSEConfig
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.sse.ServerSentEvent
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE as ServerSSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogGroupCount
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

class XoboroNativeEventRoutesTest {
  @Test
  fun `rejects cookie transport from a foreign origin`() =
    testApplication {
      val fixture = Fixture(syntheticUser())
      installEvents(fixture)

      val response =
        client.get("$XOBORO_API_PREFIX/events") {
          header(HttpHeaders.Cookie, "$XOBORO_SESSION_COOKIE=${fixture.token}")
          header(HttpHeaders.Origin, "https://evil.example.invalid")
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("cross_site_request_rejected", response.body<XoboroApiError>().code)
    }

  @Test
  fun `accepts bearer transport from a foreign origin`() =
    testApplication {
      val fixture = Fixture(syntheticUser())
      installEvents(fixture)

      withTimeout(TEST_TIMEOUT) {
        client.sse(
          urlString = "$XOBORO_API_PREFIX/events",
          request = {
            bearerAuth(fixture.token)
            header(HttpHeaders.Origin, "https://evil.example.invalid")
          },
        ) {
          assertEquals("stream.ready", incoming.first().event)
        }
      }
    }

  @Test
  fun `opens a fresh stream with resumed false when no Last-Event-ID is supplied`() =
    testApplication {
      val fixture = Fixture(syntheticUser())
      installEvents(fixture)

      withTimeout(TEST_TIMEOUT) {
        client.sse(
          urlString = "$XOBORO_API_PREFIX/events",
          request = { bearerAuth(fixture.token) },
        ) {
          val first = incoming.first()
          assertEquals("stream.ready", first.event)
          assertEquals("""{"resumed":false}""", first.data)
        }
      }
    }

  @Test
  fun `forwards Last-Event-ID to the hub and resumes without a gap`() =
    testApplication {
      val fixture = Fixture(syntheticUser())
      installEvents(fixture)

      var firstId: String? = null
      withTimeout(TEST_TIMEOUT) {
        client.sse(
          urlString = "$XOBORO_API_PREFIX/events",
          request = { bearerAuth(fixture.token) },
        ) {
          firstId = incoming.first().id
        }
      }

      withTimeout(TEST_TIMEOUT) {
        client.sse(
          urlString = "$XOBORO_API_PREFIX/events",
          request = {
            bearerAuth(fixture.token)
            header(HttpHeaders.LastEventID, requireNotNull(firstId))
          },
        ) {
          val first = incoming.first()
          assertEquals("stream.ready", first.event)
          assertEquals("""{"seq":0,"resumed":true}""", first.data)
        }
      }
    }

  @Test
  fun `rejects a new stream once the server is at capacity`() =
    testApplication {
      val fixture = Fixture(syntheticUser(), hub = XoboroNativeEventHub(NoOpCatalog, maxTotalStreams = 1))
      installEvents(fixture)

      coroutineScope {
        val streamReady = CompletableDeferred<Unit>()
        val holder =
          launch {
            client.sse(
              urlString = "$XOBORO_API_PREFIX/events",
              request = { bearerAuth(fixture.token) },
            ) {
              // A single collection: `incoming` is a cold flow over the raw byte channel, so a
              // separate `first()` followed by `toList()` would restart the parser against a
              // channel the first call already partly consumed.
              var signaled = false
              incoming.collect {
                if (!signaled) {
                  signaled = true
                  streamReady.complete(Unit)
                }
              }
            }
          }

        withTimeout(TEST_TIMEOUT) { streamReady.await() }

        val response =
          client.get("$XOBORO_API_PREFIX/events") {
            bearerAuth(fixture.token)
          }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("event_stream_capacity", response.body<XoboroApiError>().code)
        assertNotNull(response.headers[HttpHeaders.RetryAfter])

        holder.cancel()
      }
    }

  @Test
  fun `heartbeat arrives as a comment with no event name`() =
    testApplication {
      val fixture = Fixture(syntheticUser())
      installEvents(fixture, heartbeatPeriod = HEARTBEAT_TEST_PERIOD) {
        showCommentEvents()
      }

      withTimeout(TEST_TIMEOUT) {
        client.sse(
          urlString = "$XOBORO_API_PREFIX/events",
          request = { bearerAuth(fixture.token) },
        ) {
          // A single collection of `incoming`: it is a cold flow backed by the raw byte channel,
          // so a second, separate `first()` call on the same session would re-run the parser
          // from scratch and read a channel already partly consumed by the first call. The
          // predicate itself skips the leading `stream.ready` frame, so one collection is enough.
          val heartbeat = incoming.first { it.comments != null }
          assertNull(heartbeat.event)
          assertNull(heartbeat.data)
          assertEquals("heartbeat", heartbeat.comments)
        }
      }
    }

  @Test
  fun `closes a busy stream once the subscriber's authorization changes`() =
    testApplication {
      val user = syntheticUser(roles = setOf(UserRole.PAGE_STREAMING))
      val fixture = Fixture(user)
      installEvents(fixture, heartbeatPeriod = RECHECK, reauthenticationPeriod = RECHECK)

      val publisher =
        thread(isDaemon = true) {
          while (!Thread.currentThread().isInterrupted) {
            fixture.hub.publish(
              XoboroNativeEvent(
                "catalog.ping",
                XoboroNativeEventScope.Owner(user.id),
                """{"synthetic":true}""",
              ),
            )
            Thread.sleep(BUSY_PUBLISH_INTERVAL_MILLIS)
          }
        }
      try {
        withTimeout(TEST_TIMEOUT) {
          client.sse(
            urlString = "$XOBORO_API_PREFIX/events",
            request = { bearerAuth(fixture.token) },
          ) {
            // One single collection of `incoming` for the whole assertion: `incoming` is a cold
            // flow over the raw byte channel, so a second, separate `first()`/`toList()` call on
            // the same session would restart the parser against a channel the first call already
            // partly consumed. The mutation is applied the first time collect() sees anything
            // (the leading `stream.ready` frame), then collection continues in the same pass
            // until the server actually closes the connection.
            var mutated = false
            val received = mutableListOf<ServerSentEvent>()
            incoming.collect { event ->
              received += event
              if (!mutated) {
                mutated = true
                fixture.users.update(user.copy(roles = setOf(UserRole.FILE_DOWNLOAD)))
              }
            }

            val closing = received.singleOrNull { it.event == "stream.resync-required" }
            assertEquals("""{"reason":"revoked"}""", closing?.data)
          }
        }
      } finally {
        publisher.interrupt()
        publisher.join()
      }
    }

  @Test
  fun `keeps a stream open when only the password hash is rewritten`() =
    testApplication {
      val user = syntheticUser()
      val fixture = Fixture(user)
      installEvents(fixture, heartbeatPeriod = RECHECK, reauthenticationPeriod = RECHECK)

      var sawResyncRequired = false
      withTimeoutOrNull(NEGATIVE_CHECK_TIMEOUT) {
        client.sse(
          urlString = "$XOBORO_API_PREFIX/events",
          request = { bearerAuth(fixture.token) },
        ) {
          // A single collection, as in the revocation test above: the mutation is applied once
          // collect() sees the leading `stream.ready` frame, then collection continues in the
          // same pass. Nothing should ever close the stream here, so the timeout (not a matched
          // predicate) is what ends this collection.
          var mutated = false
          incoming.collect { event ->
            if (!mutated) {
              mutated = true
              fixture.users.update(user.copy(passwordHash = "synthetic-rehash", updatedAtMillis = 2))
            }
            if (event.event == "stream.resync-required") {
              sawResyncRequired = true
            }
          }
        }
      }

      assertFalse(sawResyncRequired, "stream must stay open when no authorization field changed")
    }

  private fun ApplicationTestBuilder.installEvents(
    fixture: Fixture,
    heartbeatPeriod: Duration = 1.hours,
    reauthenticationPeriod: Duration = heartbeatPeriod,
    maxStreamLifetime: Duration = 1.hours,
    configureClient: SSEConfig.() -> Unit = {},
  ) {
    application {
      install(ContentNegotiation) { json() }
      install(ServerSSE)
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      routing {
        xoboroNativeEventRoutes(
          hub = fixture.hub,
          sessions = fixture.sessions,
          heartbeatPeriod = heartbeatPeriod,
          reauthenticationPeriod = reauthenticationPeriod,
          maxStreamLifetime = maxStreamLifetime,
        )
      }
    }
    client =
      createClient {
        install(ClientContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
        install(ClientSSE, configureClient)
      }
  }

  private class Fixture(
    user: User,
    val hub: XoboroNativeEventHub = XoboroNativeEventHub(NoOpCatalog),
  ) {
    val users = InMemoryUserRepository(user)
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory = { "event-token" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val token = sessions.create(user).plainToken
  }

  private class InMemoryUserRepository(
    user: User,
  ) : UserRepository {
    @Volatile
    private var value: User? = user

    override fun count(): Long = if (value == null) 0 else 1

    override fun findByIdOrNull(id: UserId): User? = value?.takeIf { it.id == id }

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      value?.takeIf { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = listOfNotNull(value)

    override fun insert(user: User) {
      if (value != null) throw UserEmailAlreadyExistsException(user.email)
      value = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (value != null) return false
      value = user
      return true
    }

    override fun update(user: User) {
      value = user
    }

    override fun delete(id: UserId) {
      if (value?.id == id) value = null
    }
  }

  /**
   * Only [XoboroNativeEventScope.Owner]-scoped events are published in this test file, and
   * [io.xoboro.server.api.isVisibleTo] never consults the catalog for that scope, so every
   * method here is unreachable; each throws to fail loudly if that assumption ever stops holding.
   */
  private object NoOpCatalog : CatalogReadRepository {
    override fun findBooks(
      query: BookCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogBook> = error("Unused by owner-scoped native event tests")

    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = error("Unused by owner-scoped native event tests")

    override fun findPreviousBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = error("Unused by owner-scoped native event tests")

    override fun findNextBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = error("Unused by owner-scoped native event tests")

    override fun findSeries(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogSeries> = error("Unused by owner-scoped native event tests")

    override fun findSeriesByIdOrNull(
      id: SeriesId,
      access: CatalogAccess,
    ): CatalogSeries? = error("Unused by owner-scoped native event tests")

    override fun countSeriesByFirstCharacter(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
    ): List<CatalogGroupCount> = error("Unused by owner-scoped native event tests")
  }

  private companion object {
    val TEST_TIMEOUT: Duration = 10.seconds
    val NEGATIVE_CHECK_TIMEOUT: Duration = 500.milliseconds
    val RECHECK: Duration = 50.milliseconds
    const val BUSY_PUBLISH_INTERVAL_MILLIS: Long = 10
    val HEARTBEAT_TEST_PERIOD: Duration = 50.milliseconds

    fun syntheticUser(roles: Set<UserRole> = setOf(UserRole.PAGE_STREAMING)): User =
      User(
        id = UserId("user-1"),
        email = "reader@example.invalid",
        passwordHash = "synthetic-hash",
        roles = roles,
        createdAtMillis = 1,
      )
  }
}
