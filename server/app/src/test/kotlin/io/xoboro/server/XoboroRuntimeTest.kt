package io.xoboro.server

import java.nio.file.Path
import kotlin.test.Test
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
}
