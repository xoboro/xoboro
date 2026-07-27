package io.xoboro.compatibility.komga.api

import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.basicAuth
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE as ServerSSE
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
          passwordHasher = BCryptPasswordHasher(),
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
    const val PASSWORD: String = "SyntheticPassword1!"
  }
}
