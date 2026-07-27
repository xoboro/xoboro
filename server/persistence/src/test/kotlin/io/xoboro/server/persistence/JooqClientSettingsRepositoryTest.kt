package io.xoboro.server.persistence

import io.xoboro.core.domain.ClientSetting
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqClientSettingsRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `persists filters updates deletes and cascades client settings`() {
    val path = tempDirectory.resolve("client-settings.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val users = JooqUserRepository(database)
      val user = syntheticUser()
      users.insert(user)
      val settings = JooqClientSettingsRepository(database)

      settings.saveGlobal(
        mapOf(
          "application.public" to ClientSetting("visible", allowUnauthorized = true),
          "application.private" to ClientSetting("hidden", allowUnauthorized = false),
        ),
      )
      settings.saveForUser(
        user.id,
        mapOf("reader.layout" to ClientSetting("compact")),
      )
      assertEquals(setOf("application.public"), settings.findGlobal(true).keys)
      assertEquals(
        setOf("application.private", "application.public"),
        settings.findGlobal().keys,
      )
      assertEquals("compact", settings.findForUser(user.id)["reader.layout"]?.value)

      settings.saveGlobal(
        mapOf("application.public" to ClientSetting("updated", allowUnauthorized = false)),
      )
      settings.saveForUser(
        user.id,
        mapOf("reader.layout" to ClientSetting("comfortable")),
      )
      assertTrue(settings.findGlobal(true).isEmpty())
      assertEquals("updated", settings.findGlobal()["application.public"]?.value)
      assertEquals("comfortable", settings.findForUser(user.id)["reader.layout"]?.value)

      settings.deleteGlobal(setOf("application.private"))
      settings.deleteForUser(user.id, setOf("reader.layout"))
      assertEquals(setOf("application.public"), settings.findGlobal().keys)
      assertTrue(settings.findForUser(user.id).isEmpty())

      settings.saveForUser(user.id, mapOf("reader.theme" to ClientSetting("dark")))
      users.delete(user.id)
      assertTrue(settings.findForUser(user.id).isEmpty())
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      assertEquals(
        "updated",
        JooqClientSettingsRepository(database).findGlobal()["application.public"]?.value,
      )
    }
  }

  private fun syntheticUser(): User =
    User(
      id = UserId("user-1"),
      email = "reader@example.invalid",
      passwordHash = "synthetic-hash",
      roles = setOf(UserRole.PAGE_STREAMING),
      createdAtMillis = 1,
    )
}
