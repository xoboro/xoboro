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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
  fun `closes an open stream once the subscriber's authorization changes`() =
    withReaderStream("sse-revoked.sqlite") { users, _, reader ->
      // Any of the five fields UserLifecycle.updateUser compares must close the stream; roles
      // is used here because it needs no library row. Library-grant scoping itself is covered
      // by the hub-level filter tests, which need no database at all.
      users.updateUser(reader.copy(roles = setOf(UserRole.FILE_DOWNLOAD)))
    }

  @Test
  fun `closes an open stream once the subscriber's account is deleted`() =
    withReaderStream("sse-deleted.sqlite") { users, _, reader ->
      users.deleteUser(reader.id)
    }

  @Test
  fun `keeps an open stream when only the password hash is rewritten`() {
    // The adaptive password hasher rewrites passwordHash and updatedAtMillis on an ordinary
    // successful login. Comparing the whole User would therefore disconnect a subscriber
    // merely for signing in elsewhere, so this pins the narrow field comparison.
    var closed = false
    try {
      withReaderStream("sse-rehash.sqlite") { _, repository, reader ->
        repository.update(reader.copy(passwordHash = "synthetic-rehash", updatedAtMillis = 99))
      }
      closed = true
    } catch (_: TimeoutCancellationException) {
      // Expected: the stream stayed open, so collecting it never completed.
    }
    assertFalse(closed, "stream must stay open when no authorization field changed")
  }

  /**
   * Opens an authenticated stream for a non-administrator reader, runs [mutate], then collects
   * the stream to completion. Completion only happens if the server closed the stream, and the
   * timeout turns a failure to close into a test failure rather than a hang.
   */
  private fun withReaderStream(
    databaseName: String,
    mutate: (UserLifecycle, JooqUserRepository, User) -> Unit,
  ) {
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
      try {
        testApplication {
          application {
            install(ServerSSE)
            installKomgaBasicAuthentication(users)
            routing {
              komgaSseRoutes(
                events = hub,
                users = KomgaSseUserSnapshot(users::findByIdOrNull),
                heartbeatPeriod = RECHECK,
                taskPeriod = RECHECK,
                authorizationRecheckPeriod = RECHECK,
              )
            }
          }
          val client = createClient { install(ClientSSE) }
          client.sse(
            urlString = "/sse/v1/events",
            request = { basicAuth(READER_EMAIL, PASSWORD) },
          ) {
            mutate(users, repository, reader)
            withTimeout(STREAM_CLOSE_TIMEOUT) { incoming.toList() }
          }
        }
      } finally {
        hub.close()
      }
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
    val STREAM_CLOSE_TIMEOUT: Duration = 10.seconds
  }
}
