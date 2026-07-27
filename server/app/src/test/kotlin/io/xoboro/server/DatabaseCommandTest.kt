package io.xoboro.server

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.XoboroDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DatabaseCommandTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `backs up verifies and restores through offline commands`() {
    val databasePath = tempDirectory.resolve("config/xoboro.sqlite")
    val backupPath = tempDirectory.resolve("backups/manual.sqlite")
    val config = serverConfig(databasePath)
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LibraryId("library-1"),
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
    }

    assertTrue(runDatabaseCommand(arrayOf("backup", backupPath.toString()), config, {}))
    assertTrue(runDatabaseCommand(arrayOf("verify-backup", backupPath.toString()), config, {}))
    Files.delete(databasePath)
    assertTrue(
      runDatabaseCommand(
        arrayOf("restore", backupPath.toString(), "--replace"),
        config,
        {},
      ),
    )

    XoboroDatabase.open(DatabaseConfig(databasePath)).use { restored ->
      assertEquals(
        "Synthetic library",
        JooqLibraryRepository(restored).findById(LibraryId("library-1")).name,
      )
    }
  }

  @Test
  fun `starts the server only when no database command is present`() {
    assertFalse(runDatabaseCommand(emptyArray(), serverConfig(tempDirectory.resolve("db.sqlite"))))
    assertFailsWith<IllegalArgumentException> {
      runDatabaseCommand(
        arrayOf("verify-backup", "unused", "--replace"),
        serverConfig(tempDirectory.resolve("db.sqlite")),
      )
    }
  }

  private fun serverConfig(databasePath: Path): ServerConfig =
    ServerConfig(
      port = 25_600,
      databasePath = databasePath,
      workerCount = 1,
      taskPollMillis = 1,
      taskFailurePollMillis = 1,
      taskLeaseMillis = 3,
      shutdownTimeoutMillis = 1,
    )
}
