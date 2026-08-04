package io.xoboro.server

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.ScanInterval
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqServerSettingRepository
import io.xoboro.server.persistence.XoboroDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class XoboroRuntimeTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `opens a migrated database starts workers and closes idempotently`() {
    val databasePath = tempDirectory.resolve("runtime.sqlite")
    val runtime =
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

    assertTrue(runtime.isReady())

    runtime.close()
    runtime.close()

    assertFalse(runtime.isReady())
    assertTrue(java.nio.file.Files.isRegularFile(databasePath))
  }

  @Test
  fun `executes configured startup scans through the real runtime`() {
    val libraryRoot = Files.createDirectories(tempDirectory.resolve("library"))
    val series = Files.createDirectories(libraryRoot.resolve("Synthetic series"))
    Files.write(series.resolve("Book 001.cbz"), byteArrayOf(1, 2, 3))
    val databasePath = tempDirectory.resolve("startup.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LibraryId("library-1"),
          name = "Synthetic library",
          root = SourceLocation("local", libraryRoot.toUri().toString()),
          settings =
            LibrarySettings(
              scanOnStartup = true,
              scanInterval = ScanInterval.DISABLED,
              scanPdf = false,
              scanEpub = false,
            ),
          createdAtMillis = 1,
        ),
      )
    }

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
    ).use {
      awaitBookCount(databasePath, expected = 1)
    }

    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      assertEquals(1, JooqBookRepository(database).count())
    }
  }

  @Test
  fun `persists claimed users across real runtime restarts`() {
    val databasePath = tempDirectory.resolve("claimed-runtime.sqlite")
    val config =
      ServerConfig(
        port = 25_600,
        databasePath = databasePath,
        workerCount = 1,
        taskPollMillis = 10,
        taskFailurePollMillis = 10,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
      )

    XoboroRuntime.open(config).use { runtime ->
      assertFalse(runtime.userLifecycle.isClaimed())
      runtime.userLifecycle.claimInitialAdministrator(
        email = "admin@example.invalid",
        rawPassword = "synthetic-password",
      )
    }

    XoboroRuntime.open(config).use { runtime ->
      assertTrue(runtime.userLifecycle.isClaimed())
      assertTrue(
        runtime.userLifecycle.authenticate(
          email = "ADMIN@example.invalid",
          rawPassword = "synthetic-password",
        ) != null,
      )
    }
  }

  @Test
  fun `claims the configured initial administrator once and never re-claims it`() {
    val databasePath = tempDirectory.resolve("provisioned-runtime.sqlite")
    val config =
      ServerConfig(
        port = 25_600,
        databasePath = databasePath,
        workerCount = 1,
        taskPollMillis = 10,
        taskFailurePollMillis = 10,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
        initialAdministrator =
          InitialAdministrator("provisioned@example.invalid", "synthetic-provisioned-password"),
      )

    XoboroRuntime.open(config).use { runtime ->
      // Claimed at startup, without anyone making the setup call. A deployment brought up from a
      // manifest has nobody to make it, so the alternative is a server sitting unclaimed and reachable
      // by whoever finds it first.
      assertTrue(runtime.userLifecycle.isClaimed())
      assertTrue(
        runtime.userLifecycle.authenticate(
          email = "provisioned@example.invalid",
          rawPassword = "synthetic-provisioned-password",
        ) != null,
      )
      // The administrator changes their password, as they should after provisioning.
      val claimed =
        requireNotNull(
          runtime.userLifecycle.authenticate(
            email = "provisioned@example.invalid",
            rawPassword = "synthetic-provisioned-password",
          ),
        )
      runtime.userLifecycle.updatePassword(claimed.id, "chosen-by-the-administrator")
    }

    // Restarted with the same variables still set - which is the normal case, since they live in the
    // manifest.
    XoboroRuntime.open(config).use { runtime ->
      assertTrue(runtime.userLifecycle.isClaimed())
      // The chosen password still works, and the provisioning password no longer does. If startup
      // re-claimed, anyone able to edit the environment could take the account over by restarting -
      // turning a provisioning convenience into a back door.
      assertTrue(
        runtime.userLifecycle.authenticate(
          email = "provisioned@example.invalid",
          rawPassword = "chosen-by-the-administrator",
        ) != null,
      )
      assertNull(
        runtime.userLifecycle.authenticate(
          email = "provisioned@example.invalid",
          rawPassword = "synthetic-provisioned-password",
        ),
      )
    }
  }

  @Test
  fun `persists authenticated sessions across real runtime restarts`() {
    val databasePath = tempDirectory.resolve("session-runtime.sqlite")
    val config =
      ServerConfig(
        port = 25_600,
        databasePath = databasePath,
        workerCount = 1,
        taskPollMillis = 10,
        taskFailurePollMillis = 10,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
      )

    val plainToken =
      XoboroRuntime.open(config).use { runtime ->
        val user =
          runtime.userLifecycle.claimInitialAdministrator(
            email = "admin@example.invalid",
            rawPassword = "synthetic-password",
          )
        requireNotNull(runtime.userSessionLifecycle.create(user)).plainToken
      }

    XoboroRuntime.open(config).use { runtime ->
      assertEquals(
        "admin@example.invalid",
        runtime.userSessionLifecycle.authenticate(plainToken)?.email,
      )
    }
  }

  @Test
  fun `applies persisted startup settings and live worker resizing`() {
    val databasePath = tempDirectory.resolve("settings-runtime.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      val settings = JooqServerSettingRepository(database)
      settings.put("SERVER_PORT", "29001")
      settings.put("SERVER_CONTEXT_PATH", "/reader")
      settings.put("TASK_POOL_SIZE", "2")
    }
    val config =
      ServerConfig(
        port = 25_600,
        databasePath = databasePath,
        workerCount = 1,
        taskPollMillis = 10,
        taskFailurePollMillis = 10,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
        configuredPort = null,
      )

    XoboroRuntime.open(config).use { runtime ->
      assertEquals(29_001, runtime.effectiveServerPort)
      assertEquals("/reader", runtime.effectiveServerContextPath)
      assertEquals(2, runtime.taskWorkerCount())

      runtime.serverSettingsLifecycle.update(
        io.xoboro.core.application.ServerSettingsUpdate(taskPoolSize = 3),
      )
      assertEquals(3, runtime.taskWorkerCount())
    }
  }

  @Test
  fun `refreshes ComicInfo and series json metadata through durable runtime jobs`() {
    val libraryRoot = Files.createDirectories(tempDirectory.resolve("metadata-library"))
    val seriesRoot = Files.createDirectories(libraryRoot.resolve("Synthetic series"))
    val archive = seriesRoot.resolve("Synthetic 001.cbz")
    ZipOutputStream(Files.newOutputStream(archive)).use { output ->
      output.putNextEntry(ZipEntry("ComicInfo.xml"))
      output.write(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <ComicInfo>
          <Title>Synthetic chapter title</Title>
          <Series>Synthetic embedded series</Series>
          <Number>1</Number>
          <Summary>Synthetic chapter summary</Summary>
          <AlternateSeries>Synthetic chronology</AlternateSeries>
          <AlternateNumber>3</AlternateNumber>
          <SeriesGroup>Synthetic shelf</SeriesGroup>
        </ComicInfo>
        """.trimIndent().encodeToByteArray(),
      )
      output.closeEntry()
    }
    Files.writeString(
      seriesRoot.resolve("series.json"),
      """
      {
        "metadata": {
          "publisher": "Synthetic publisher",
          "name": "Synthetic sidecar series",
          "year": 2026,
          "description_text": "Synthetic series summary",
          "volume": 1,
          "total_issues": 1,
          "status": "Ended"
        }
      }
      """.trimIndent(),
    )
    val databasePath = tempDirectory.resolve("metadata-runtime.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LibraryId("library-1"),
          name = "Synthetic metadata library",
          root = SourceLocation("local", libraryRoot.toUri().toString()),
          settings =
            LibrarySettings(
              scanOnStartup = true,
              scanInterval = ScanInterval.DISABLED,
              scanPdf = false,
              scanEpub = false,
            ),
          createdAtMillis = 1,
        ),
      )
    }

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
    ).use { runtime ->
      awaitBookCount(databasePath, expected = 1)
      runtime.libraryMaintenanceRequester.refreshMetadata(LibraryId("library-1"))
      awaitMetadataTitle(
        databasePath = databasePath,
        table = "book_metadata",
        idColumn = "book_id",
        expected = "Synthetic chapter title",
      )
      awaitMetadataTitle(
        databasePath = databasePath,
        table = "series_metadata",
        idColumn = "series_id",
        expected = "Synthetic sidecar series",
      )
      awaitCondition("ComicInfo read-list import") {
        runtime.readListRepository
          .findByNameIgnoreCaseOrNull("Synthetic chronology")
          ?.bookIds
          ?.size == 1
      }
      awaitCondition("ComicInfo collection import") {
        runtime.seriesCollectionRepository
          .findByNameIgnoreCaseOrNull("Synthetic shelf")
          ?.seriesIds
          ?.size == 1
      }
    }
  }

  private fun awaitCondition(
    description: String,
    condition: () -> Boolean,
  ) {
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
      if (condition()) return
      Thread.sleep(20)
    }
    throw AssertionError("Timed out waiting for $description")
  }

  private fun awaitBookCount(
    databasePath: Path,
    expected: Long,
  ) {
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
    java.sql.DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
      while (System.nanoTime() < deadline) {
        val count =
          connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM book").use { result ->
              result.next()
              result.getLong(1)
            }
          }
        if (count == expected) return
        Thread.sleep(20)
      }
    }
    throw AssertionError("Timed out waiting for $expected scanned books")
  }

  private fun awaitMetadataTitle(
    databasePath: Path,
    table: String,
    idColumn: String,
    expected: String,
  ) {
    require(table in setOf("book_metadata", "series_metadata"))
    require(idColumn in setOf("book_id", "series_id"))
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
    java.sql.DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
      while (System.nanoTime() < deadline) {
        val title =
          connection.createStatement().use { statement ->
            statement.executeQuery("SELECT title FROM $table ORDER BY $idColumn LIMIT 1").use {
                result ->
              if (result.next()) result.getString(1) else null
            }
          }
        if (title == expected) return
        Thread.sleep(20)
      }
    }
    throw AssertionError("Timed out waiting for metadata title '$expected'")
  }
}
