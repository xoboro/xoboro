package io.xoboro.server.persistence

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqServerSettingRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `creates updates and preserves server secrets across restart`() {
    val path = tempDirectory.resolve("server-settings.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqServerSettingRepository(database)
      assertEquals("synthetic-secret-1", repository.findOrCreate("REMEMBER_ME_KEY") { "synthetic-secret-1" })
      assertEquals("synthetic-secret-1", repository.findOrCreate("REMEMBER_ME_KEY") { "unused-secret" })
      repository.put("REMEMBER_ME_KEY", "synthetic-secret-2")
      assertEquals("synthetic-secret-2", repository.find("REMEMBER_ME_KEY"))
      repository.put("EPHEMERAL_SETTING", "synthetic-value")
      repository.delete("EPHEMERAL_SETTING")
      assertNull(repository.find("EPHEMERAL_SETTING"))
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqServerSettingRepository(database)
      assertEquals("synthetic-secret-2", repository.findOrCreate("REMEMBER_ME_KEY") { "unused-secret" })
    }
  }
}
