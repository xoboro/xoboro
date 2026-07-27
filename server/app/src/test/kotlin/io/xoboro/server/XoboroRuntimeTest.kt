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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
