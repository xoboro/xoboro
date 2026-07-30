package io.xoboro.server

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerConfigTest {
  @Test
  fun `uses portable defaults relative to the working directory`() {
    val workingDirectory = Path.of("build/synthetic-work").toAbsolutePath().normalize()

    val config =
      ServerConfig.fromEnvironment(
        environment = emptyMap(),
        workingDirectory = workingDirectory,
        availableProcessors = 8,
      )

    assertEquals(ServerConfig.DEFAULT_PORT, config.port)
    assertEquals(null, config.configuredPort)
    assertEquals(null, config.configuredContextPath)
    assertEquals(workingDirectory.resolve("config/xoboro.sqlite"), config.databasePath)
    assertEquals(workingDirectory.resolve("config/fonts"), config.fontsDirectory)
    assertEquals(workingDirectory.resolve("config/backups"), config.backupsDirectory)
    assertEquals(emptySet(), config.corsAllowedOrigins)
    assertEquals(4, config.workerCount)
    assertEquals(emptySet(), config.trustedProxyHosts)
    assertEquals(null, config.metricsToken)
  }

  @Test
  fun `strictly parses environment overrides and resolves relative database paths`() {
    val workingDirectory = Path.of("build/synthetic-work").toAbsolutePath().normalize()

    val config =
      ServerConfig.fromEnvironment(
        environment =
          mapOf(
            "XOBORO_PORT" to "28000",
            "XOBORO_CONTEXT_PATH" to "/reader",
            "XOBORO_DATABASE_PATH" to "state/catalog.sqlite",
            "XOBORO_WORKER_COUNT" to "2",
            "XOBORO_TASK_POLL_MILLIS" to "25",
            "XOBORO_TASK_FAILURE_POLL_MILLIS" to "50",
            "XOBORO_TASK_LEASE_MILLIS" to "1000",
            "XOBORO_SHUTDOWN_TIMEOUT_MILLIS" to "2000",
            "XOBORO_FONTS_PATH" to "assets/fonts",
            "XOBORO_BACKUPS_PATH" to "state/backups",
            "KOMGA_CORS_ALLOWEDORIGINS" to
              "https://reader.example.invalid, http://localhost:1234,https://reader.example.invalid",
            "XOBORO_TRUSTED_PROXIES" to "127.0.0.1, proxy.internal,127.0.0.1",
            "XOBORO_METRICS_TOKEN" to "synthetic-metrics-token-000000000",
          ),
        workingDirectory = workingDirectory,
      )

    assertEquals(28_000, config.port)
    assertEquals(28_000, config.configuredPort)
    assertEquals("/reader", config.configuredContextPath)
    assertEquals(workingDirectory.resolve("state/catalog.sqlite"), config.databasePath)
    assertEquals(2, config.workerCount)
    assertEquals(25L, config.taskPollMillis)
    assertEquals(50L, config.taskFailurePollMillis)
    assertEquals(1_000L, config.taskLeaseMillis)
    assertEquals(2_000L, config.shutdownTimeoutMillis)
    assertEquals(workingDirectory.resolve("assets/fonts"), config.fontsDirectory)
    assertEquals(workingDirectory.resolve("state/backups"), config.backupsDirectory)
    assertEquals(
      setOf("https://reader.example.invalid", "http://localhost:1234"),
      config.corsAllowedOrigins,
    )
    assertEquals(setOf("127.0.0.1", "proxy.internal"), config.trustedProxyHosts)
    assertEquals("synthetic-metrics-token-000000000", config.metricsToken)
  }

  @Test
  fun `rejects malformed and unsafe environment values`() {
    assertFailsWith<IllegalStateException> {
      ServerConfig.fromEnvironment(mapOf("XOBORO_PORT" to "not-a-number"))
    }
    assertFailsWith<IllegalArgumentException> {
      ServerConfig.fromEnvironment(mapOf("XOBORO_PORT" to "70000"))
    }
    assertFailsWith<IllegalArgumentException> {
      ServerConfig.fromEnvironment(mapOf("XOBORO_WORKER_COUNT" to "0"))
    }
    assertFailsWith<IllegalArgumentException> {
      ServerConfig.fromEnvironment(mapOf("XOBORO_CONTEXT_PATH" to "reader"))
    }
    assertFailsWith<IllegalArgumentException> {
      ServerConfig.fromEnvironment(mapOf("XOBORO_TASK_LEASE_MILLIS" to "2"))
    }
    assertFailsWith<IllegalArgumentException> {
      ServerConfig.fromEnvironment(mapOf("XOBORO_TRUSTED_PROXIES" to "proxy/unsafe"))
    }
    assertFailsWith<IllegalArgumentException> {
      ServerConfig.fromEnvironment(mapOf("XOBORO_METRICS_TOKEN" to "too-short"))
    }
    listOf(
      "*",
      "reader.example.invalid",
      "https://reader.example.invalid/path",
      "https://user@reader.example.invalid",
      "https://reader.example.invalid?query=value",
    ).forEach { origin ->
      assertFailsWith<IllegalArgumentException> {
        ServerConfig.fromEnvironment(mapOf("KOMGA_CORS_ALLOWEDORIGINS" to origin))
      }
    }
    assertEquals(
      setOf("null"),
      ServerConfig
        .fromEnvironment(mapOf("KOMGA_CORS_ALLOWEDORIGINS" to "null"))
        .corsAllowedOrigins,
    )
  }
}
