package io.xoboro.compatibility.komga.api

import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.basicAuth
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE as ServerSSE
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.application.CatalogMutationKind
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.io.TempDir

class SseRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `filters events by administrator and user identity`() =
    runBlocking {
      val hub = KomgaSseEventHub()
      val admin = userFixture("admin", setOf(UserRole.ADMIN))
      val reader = userFixture("reader", setOf(UserRole.PAGE_STREAMING))
      val other = userFixture("other", setOf(UserRole.PAGE_STREAMING))
      hub.subscribe(admin).use { adminEvents ->
        hub.subscribe(reader).use { readerEvents ->
          hub.subscribe(other).use {
            hub.publish(
              name = "TaskQueueStatus",
              dataJson = """{"count":1}""",
              adminOnly = true,
            )
            hub.publish(
              name = "ReadProgressChanged",
              dataJson = """{"bookId":"media-1","userId":"reader"}""",
              userIdOnly = "reader",
            )

            assertEquals("TaskQueueStatus", adminEvents.receive().name)
            assertEquals("ReadProgressChanged", readerEvents.receive().name)
          }
        }
      }
      hub.close()
    }

  @Test
  fun `streams the authenticated administrator task snapshot`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("sse.sqlite"))).use { database ->
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "admin" },
          currentTimeMillis = { 1 },
        )
      users.claimInitialAdministrator(EMAIL, PASSWORD)
      val hub = KomgaSseEventHub()

      testApplication {
        application {
          install(ServerSSE)
          installKomgaBasicAuthentication(users)
          routing {
            komgaSseRoutes(
              events = hub,
              users = KomgaSseUserSnapshot(users::findByIdOrNull),
              tasks =
                KomgaTaskStatusProvider {
                  KomgaTaskQueueSseDto(
                    count = 2,
                    countByType = mapOf("SCAN_LIBRARY" to 2),
                  )
                },
            )
          }
        }
        val client =
          createClient {
            install(ClientSSE)
          }
        client.sse(
          urlString = "/sse/v1/events",
          request = { basicAuth(EMAIL, PASSWORD) },
        ) {
          val event = incoming.first()
          assertEquals("TaskQueueStatus", event.event)
          assertEquals(
            """{"count":2,"countByType":{"SCAN_LIBRARY":2}}""",
            event.data,
          )
        }
      }
      hub.close()
    }
  }

  @Test
  fun `scopes library-scoped events to subscribers who can access the library`() =
    runBlocking {
      val hub = KomgaSseEventHub()
      hub.subscribe(readerFixture("granted", LIBRARY_ONE)).use { grantedEvents ->
        hub.subscribe(readerFixture("excluded", LIBRARY_TWO)).use { excludedEvents ->
          hub.publish(
            name = "BookChanged",
            dataJson = """{"bookId":"media-1"}""",
            libraryId = LIBRARY_ONE,
          )
          assertEquals("BookChanged", grantedEvents.receive().name)

          hub.publish(
            name = "SeriesChanged",
            dataJson = """{"seriesId":"series-1"}""",
            libraryId = LIBRARY_TWO,
          )
          // Asserting the *name* here is what makes this a real test. The excluded
          // subscriber's first available event is the library-two event, so the library-one
          // event was never queued for it — and receiving anything at all proves the
          // subscription was live, so the earlier absence was a filter decision rather than a
          // dead channel. Without the filter this assertion sees "BookChanged" and fails.
          assertEquals("SeriesChanged", excludedEvents.receive().name)
        }
      }
      hub.close()
    }

  @Test
  fun `still delivers removals to subscribers who can access the library`() =
    runBlocking {
      val hub = KomgaSseEventHub()
      val bridge = KomgaSseEventBridge(hub)
      hub.subscribe(readerFixture("granted", LIBRARY_ONE)).use { events ->
        // A deletion's row is already gone, so any filter that had to look the entity up would
        // deliver this to nobody and leave every client showing a phantom entry forever. The
        // library identifier travels inside the domain event, so scoping stays exact here.
        bridge.publish(
          CatalogMutationEvent.Book(
            kind = CatalogMutationKind.DELETED,
            bookId = BookId("media-1"),
            seriesId = SeriesId("series-1"),
            libraryId = LIBRARY_ONE,
          ),
        )
        assertEquals("BookDeleted", events.receive().name)
      }
      hub.close()
    }

  @Test
  fun `stops delivering to an open stream once the subscriber's authorization changes`() {
    assertTrue(
      streamStopsDeliveringAfter("sse-revoked.sqlite") { users, _, reader ->
        // Any of the five fields UserLifecycle.updateUser compares must cut delivery off; roles
        // is used here because it needs no library row. Library-grant scoping itself is covered
        // by the hub-level filter tests, which need no database at all.
        users.updateUser(reader.copy(roles = setOf(UserRole.FILE_DOWNLOAD)))
      },
    )
  }

  @Test
  fun `stops delivering to an open stream once the subscriber's account is deleted`() {
    assertTrue(
      streamStopsDeliveringAfter("sse-deleted.sqlite") { users, _, reader ->
        users.deleteUser(reader.id)
      },
    )
  }

  @Test
  fun `treats only authorization fields as invalidating an open stream`() {
    // The adaptive password hasher rewrites passwordHash and updatedAtMillis on an ordinary
    // successful login, so comparing the whole User would disconnect a subscriber merely for
    // signing in elsewhere. This is a property of the comparison, not of the transport, so it
    // is asserted directly: an equivalent stream-level test could only argue it by waiting for
    // a disconnect that never comes, which proves less and leaks a live coroutine when the
    // wait expires.
    val connected = userFixture("reader", setOf(UserRole.PAGE_STREAMING))

    assertFalse(connected.invalidatesKomgaSessionFrom(connected))
    assertFalse(
      connected
        .copy(passwordHash = "synthetic-rehash", updatedAtMillis = 99)
        .invalidatesKomgaSessionFrom(connected),
    )

    // Every field UserLifecycle.updateUser compares before expiring sessions must close it.
    assertTrue(connected.copy(email = "moved@example.invalid").invalidatesKomgaSessionFrom(connected))
    assertTrue(connected.copy(roles = setOf(UserRole.ADMIN)).invalidatesKomgaSessionFrom(connected))
    assertTrue(
      connected
        .copy(sharedLibraryIds = setOf(LIBRARY_ONE), sharesAllLibraries = false)
        .invalidatesKomgaSessionFrom(connected),
    )
    assertTrue(connected.copy(sharesAllLibraries = false).invalidatesKomgaSessionFrom(connected))
    assertTrue(
      connected
        .copy(restrictions = ContentRestrictions(labelsAllow = setOf("synthetic")))
        .invalidatesKomgaSessionFrom(connected),
    )
  }

  /**
   * Opens an authenticated stream for a non-administrator reader, runs [mutate], and reports
   * whether the server then closed the stream.
   *
   * The bound uses [withTimeoutOrNull] rather than [withTimeout] deliberately: a timeout has to
   * be an observable result, not an exception escaping `testApplication`. An escaping
   * cancellation leaves the server-side SSE coroutine alive past the end of the test body, and
   * `runTest` reports that as `UncompletedCoroutinesError` instead of the assertion the test
   * meant to make — which is exactly how the earlier version of these tests failed in CI while
   * passing locally.
   */
  private fun streamStopsDeliveringAfter(
    databaseName: String,
    mutate: (UserLifecycle, JooqUserRepository, User) -> Unit,
  ): Boolean {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve(databaseName))).use { database ->
      val repository = JooqUserRepository(database)
      var nextUserId = 0
      val users =
        UserLifecycle(
          users = repository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-${++nextUserId}" },
          currentTimeMillis = { 1 },
        )
      users.claimInitialAdministrator(EMAIL, PASSWORD)
      val reader =
        users.createUser(
          email = READER_EMAIL,
          rawPassword = PASSWORD,
          roles = setOf(UserRole.PAGE_STREAMING),
          sharesAllLibraries = false,
        )
      val hub = KomgaSseEventHub()
      var stopped = false
      try {
        testApplication {
          application {
            install(ServerSSE)
            installKomgaBasicAuthentication(users)
            routing {
              komgaSseRoutes(
                events = hub,
                users = KomgaSseUserSnapshot(users::findByIdOrNull),
                // Only the recheck cadence needs to be fast. Ktor's heartbeat launches a
                // repeating coroutine, and at a 50ms period one is always in flight when the
                // session ends, which runTest reports as an uncompleted coroutine.
                heartbeatPeriod = QUIET_HEARTBEAT,
                taskPeriod = RECHECK,
                authorizationRecheckPeriod = RECHECK,
              )
            }
          }
          // Asserts that a revoked subscriber stops receiving, rather than waiting for the
          // server to close the connection. Waiting for a server-initiated close inside
          // testApplication leaves a coroutine alive often enough under CI load that runTest
          // reports UncompletedCoroutinesError instead of this assertion — with the client SSE
          // plugin and with a raw channel read alike, so the lingering coroutine is on the
          // server side, not the client's. Letting the client end the request is the same
          // teardown path the passing task-snapshot test uses.
          //
          // No delivery is also the property that actually matters: closing is the mechanism,
          // "a subscriber whose authorization went away learns nothing further" is the rule.
          val client = createClient { install(ClientSSE) }
          client.sse(
            urlString = "/sse/v1/events",
            request = { basicAuth(READER_EMAIL, PASSWORD) },
          ) {
            mutate(users, repository, reader)
            // Real time, not virtual: withTimeoutOrNull budgets in this block elapse in
            // wall-clock. Waiting several recheck periods removes the race where a publish
            // lands before the route has re-resolved the subscriber even once.
            delay(RECHECK * 6)
            hub.publish(
              name = "ReadProgressChanged",
              dataJson = """{"bookId":"media-1","userId":"${reader.id.value}"}""",
              userIdOnly = reader.id.value,
            )
            stopped =
              withTimeoutOrNull(DELIVERY_WINDOW) {
                // firstOrNull: if the server did close the stream the flow completes without a
                // match, which is the same verdict as never receiving it.
                incoming.firstOrNull { it.event == "ReadProgressChanged" }
              } == null
          }
        }
      } finally {
        hub.close()
      }
      return stopped
    }
  }

  private fun readerFixture(
    id: String,
    libraryId: LibraryId,
  ): User =
    userFixture(id, setOf(UserRole.PAGE_STREAMING)).copy(
      sharedLibraryIds = setOf(libraryId),
      sharesAllLibraries = false,
    )

  private fun userFixture(
    id: String,
    roles: Set<UserRole>,
  ): User =
    User(
      id = UserId(id),
      email = "$id@example.invalid",
      passwordHash = "synthetic-hash",
      roles = roles,
      createdAtMillis = 1,
    )

  private companion object {
    const val EMAIL: String = "admin@example.invalid"
    const val READER_EMAIL: String = "reader@example.invalid"
    const val PASSWORD: String = "SyntheticPassword1!"
    val LIBRARY_ONE: LibraryId = LibraryId("library-1")
    val LIBRARY_TWO: LibraryId = LibraryId("library-2")
    val RECHECK: Duration = 50.milliseconds
    val QUIET_HEARTBEAT: Duration = 30.seconds
    val DELIVERY_WINDOW: Duration = 2.seconds
  }
}
