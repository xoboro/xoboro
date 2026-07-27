package io.xoboro.server.persistence

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DatabaseBackupTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `creates an integrity checked snapshot while the WAL database is live`() {
    val databasePath = tempDirectory.resolve("live.sqlite")
    val backupPath = tempDirectory.resolve("backups/snapshot.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      database.dsl.execute(
        """
        INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
        VALUES ('library-1', 'Synthetic library', 'file:///synthetic', 1, 1)
        """.trimIndent(),
      )

      assertEquals(backupPath.toAbsolutePath(), database.backups.create(backupPath))
      DatabaseBackupManager.verify(backupPath)
      database.dsl.execute("UPDATE library SET name = 'Changed after backup'")
    }

    XoboroDatabase.open(DatabaseConfig(backupPath)).use { restored ->
      assertEquals(
        "Synthetic library",
        restored.dsl.fetchValue(
          "SELECT name FROM library WHERE id = 'library-1'",
          String::class.java,
        ),
      )
    }
  }

  @Test
  fun `restores atomically and removes stale WAL sidecars`() {
    val sourcePath = tempDirectory.resolve("source.sqlite")
    val targetPath = tempDirectory.resolve("target.sqlite")
    XoboroDatabase.open(DatabaseConfig(sourcePath)).use { source ->
      source.dsl.execute(
        """
        INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
        VALUES ('library-source', 'Snapshot library', 'file:///snapshot', 1, 1)
        """.trimIndent(),
      )
      source.backups.create(tempDirectory.resolve("snapshot.sqlite"))
    }
    XoboroDatabase.open(DatabaseConfig(targetPath)).use { target ->
      target.dsl.execute(
        """
        INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
        VALUES ('library-target', 'Old library', 'file:///old', 1, 1)
        """.trimIndent(),
      )
    }
    Files.writeString(targetPath.resolveSibling("target.sqlite-wal"), "stale")
    Files.writeString(targetPath.resolveSibling("target.sqlite-shm"), "stale")

    DatabaseBackupManager.restore(
      source = tempDirectory.resolve("snapshot.sqlite"),
      destination = targetPath,
      replaceExisting = true,
    )

    assertFalse(Files.exists(targetPath.resolveSibling("target.sqlite-wal")))
    assertFalse(Files.exists(targetPath.resolveSibling("target.sqlite-shm")))
    XoboroDatabase.open(DatabaseConfig(targetPath)).use { restored ->
      assertEquals(
        listOf("library-source"),
        restored.dsl.fetchValues("SELECT id FROM library", String::class.java),
      )
    }
  }

  @Test
  fun `rejects corrupt backups without replacing the destination`() {
    val targetPath = tempDirectory.resolve("preserved.sqlite")
    XoboroDatabase.open(DatabaseConfig(targetPath)).use { database ->
      database.dsl.execute(
        """
        INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
        VALUES ('library-1', 'Preserved library', 'file:///preserved', 1, 1)
        """.trimIndent(),
      )
    }
    val corrupt = tempDirectory.resolve("corrupt.sqlite")
    Files.writeString(corrupt, "not a database")

    assertFailsWith<InvalidDatabaseBackupException> {
      DatabaseBackupManager.restore(corrupt, targetPath, replaceExisting = true)
    }

    XoboroDatabase.open(DatabaseConfig(targetPath)).use { preserved ->
      assertEquals(
        1,
        preserved.dsl.fetchValue("SELECT COUNT(*) FROM library", Int::class.java),
      )
    }
  }

  @Test
  fun `prevents restore and a second server while the database is open`() {
    val databasePath = tempDirectory.resolve("locked.sqlite")
    val backupPath = tempDirectory.resolve("locked-backup.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      database.backups.create(backupPath)

      assertFailsWith<DatabaseAlreadyOpenException> {
        XoboroDatabase.open(DatabaseConfig(databasePath))
      }
      assertFailsWith<DatabaseAlreadyOpenException> {
        DatabaseBackupManager.restore(backupPath, databasePath, replaceExisting = true)
      }
      assertTrue(database.isAvailable())
    }
  }
}
