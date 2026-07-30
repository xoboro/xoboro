package io.xoboro.server.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalDatabaseBackupRequesterTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `lists no backups before any have been created`() {
    val databasePath = tempDirectory.resolve("live.sqlite")
    val backupsDirectory = tempDirectory.resolve("backups")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      val requester = requester(database, backupsDirectory)

      assertEquals(emptyList(), requester.list())
    }
  }

  @Test
  fun `creates a real backup file without exposing its filesystem path`() {
    val backupsDirectory = tempDirectory.resolve("backups")
    val databasePath = tempDirectory.resolve("live.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      database.dsl.execute(
        """
        INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
        VALUES ('library-1', 'Synthetic library', 'file:///synthetic', 1, 1)
        """.trimIndent(),
      )
      val requester = requester(database, backupsDirectory)

      val descriptor = requester.create()

      assertEquals("backup-1", descriptor.id)
      assertTrue(descriptor.sizeBytes > 0)
      assertTrue(Files.isRegularFile(backupsDirectory.resolve("backup-1.sqlite")))
      DatabaseBackupManager.verify(backupsDirectory.resolve("backup-1.sqlite"))
    }
  }

  @Test
  fun `lists created backups newest first`() {
    val backupsDirectory = tempDirectory.resolve("backups")
    val databasePath = tempDirectory.resolve("live.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      val requester = requester(database, backupsDirectory, ids = listOf("backup-1", "backup-2"))

      val first = requester.create()
      // Force distinct modification times so ordering is unambiguous regardless of the
      // filesystem clock's resolution.
      Files.setLastModifiedTime(backupsDirectory.resolve("backup-1.sqlite"), FileTime.fromMillis(1_000))
      val second = requester.create()
      Files.setLastModifiedTime(backupsDirectory.resolve("backup-2.sqlite"), FileTime.fromMillis(2_000))

      assertEquals(
        listOf(first.copy(createdAtMillis = 1_000), second.copy(createdAtMillis = 2_000)).reversed(),
        requester.list(),
      )
    }
  }

  @Test
  fun `deletes an existing backup once and then reports it missing`() {
    val backupsDirectory = tempDirectory.resolve("backups")
    val databasePath = tempDirectory.resolve("live.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      val requester = requester(database, backupsDirectory)
      val descriptor = requester.create()

      val deleted = requester.delete(descriptor.id)
      val deletedAgain = requester.delete(descriptor.id)

      assertTrue(deleted)
      assertFalse(deletedAgain)
      assertFalse(Files.exists(backupsDirectory.resolve("${descriptor.id}.sqlite")))
    }
  }

  @Test
  fun `refuses path traversal identifiers without touching the filesystem`() {
    val backupsDirectory = tempDirectory.resolve("backups")
    val databasePath = tempDirectory.resolve("live.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      val requester = requester(database, backupsDirectory)
      requester.create()
      val outsideFile = tempDirectory.resolve("outside.sqlite")
      Files.writeString(outsideFile, "sentinel")

      val traversalAttempts =
        listOf("../outside", "sub/dir", "/etc/passwd", "backup-1/../../outside")
      for (attempt in traversalAttempts) {
        assertFalse(requester.delete(attempt), attempt)
      }

      assertEquals("sentinel", Files.readString(outsideFile))
    }
  }

  private fun requester(
    database: XoboroDatabase,
    backupsDirectory: Path,
    ids: List<String> = listOf("backup-1"),
  ): LocalDatabaseBackupRequester {
    val idFactory = ids.iterator()::next
    return LocalDatabaseBackupRequester(
      backups = database.backups,
      directory = backupsDirectory,
      idFactory = idFactory,
    )
  }
}
