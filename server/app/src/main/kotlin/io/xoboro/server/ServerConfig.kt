package io.xoboro.server

import io.xoboro.core.application.OAuth2ClientRegistration
import java.nio.file.Path

data class ServerConfig(
  val port: Int,
  val databasePath: Path,
  val workerCount: Int,
  val taskPollMillis: Long,
  val taskFailurePollMillis: Long,
  val taskLeaseMillis: Long,
  val shutdownTimeoutMillis: Long,
  val configuredPort: Int? = port,
  val configuredContextPath: String? = null,
  val oauth2Registrations: List<OAuth2ClientRegistration> = emptyList(),
  val oauth2AccountCreation: Boolean = false,
  val oidcEmailVerification: Boolean = true,
) {
  init {
    require(port in 1..65_535) { "Server port must be between 1 and 65535" }
    require(workerCount in 1..64) { "Worker count must be between 1 and 64" }
    require(taskPollMillis > 0) { "Task poll duration must be positive" }
    require(taskFailurePollMillis > 0) { "Task failure poll duration must be positive" }
    require(taskLeaseMillis >= 3) { "Task lease duration must be at least 3 ms" }
    require(shutdownTimeoutMillis > 0) { "Shutdown timeout must be positive" }
    configuredPort?.let {
      require(it in 1..65_535) { "Configured server port must be between 1 and 65535" }
    }
    configuredContextPath?.let {
      require(CONTEXT_PATH_PATTERN.matches(it)) { "Configured server context path is invalid" }
    }
  }

  companion object {
    const val DEFAULT_PORT: Int = 25_600
    const val DEFAULT_TASK_POLL_MILLIS: Long = 500
    const val DEFAULT_TASK_FAILURE_POLL_MILLIS: Long = 1_000
    const val DEFAULT_TASK_LEASE_MILLIS: Long = 600_000
    const val DEFAULT_SHUTDOWN_TIMEOUT_MILLIS: Long = 30_000

    fun fromEnvironment(
      environment: Map<String, String> = System.getenv(),
      workingDirectory: Path = Path.of("").toAbsolutePath(),
      availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    ): ServerConfig {
      require(availableProcessors > 0) { "Available processor count must be positive" }
      val normalizedWorkingDirectory = workingDirectory.toAbsolutePath().normalize()
      val databasePath =
        environment["XOBORO_DATABASE_PATH"]
          ?.takeIf(String::isNotBlank)
          ?.let { Path.of(it) }
          ?.let { path ->
            if (path.isAbsolute) path.normalize() else normalizedWorkingDirectory.resolve(path).normalize()
          }
          ?: normalizedWorkingDirectory.resolve("config/xoboro.sqlite")
      val configuredPort = environment.optionalIntValue("XOBORO_PORT")
      return ServerConfig(
        port = configuredPort ?: DEFAULT_PORT,
        databasePath = databasePath,
        workerCount =
          environment.intValue(
            "XOBORO_WORKER_COUNT",
            availableProcessors.coerceIn(1, 4),
          ),
        taskPollMillis =
          environment.longValue("XOBORO_TASK_POLL_MILLIS", DEFAULT_TASK_POLL_MILLIS),
        taskFailurePollMillis =
          environment.longValue(
            "XOBORO_TASK_FAILURE_POLL_MILLIS",
            DEFAULT_TASK_FAILURE_POLL_MILLIS,
          ),
        taskLeaseMillis =
          environment.longValue("XOBORO_TASK_LEASE_MILLIS", DEFAULT_TASK_LEASE_MILLIS),
        shutdownTimeoutMillis =
          environment.longValue(
            "XOBORO_SHUTDOWN_TIMEOUT_MILLIS",
            DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
          ),
        configuredPort = configuredPort,
        configuredContextPath =
          environment["XOBORO_CONTEXT_PATH"]
            ?.takeIf(String::isNotBlank),
        oauth2Registrations = OAuth2EnvironmentConfig.registrations(environment),
        oauth2AccountCreation =
          environment.booleanValue("KOMGA_OAUTH2_ACCOUNT_CREATION", false),
        oidcEmailVerification =
          environment.booleanValue("KOMGA_OIDC_EMAIL_VERIFICATION", true),
      )
    }

    private fun Map<String, String>.intValue(
      key: String,
      default: Int,
    ): Int =
      get(key)?.toIntOrNull()
        ?: if (containsKey(key)) error("$key must be an integer") else default

    private fun Map<String, String>.longValue(
      key: String,
      default: Long,
    ): Long =
      get(key)?.toLongOrNull()
        ?: if (containsKey(key)) error("$key must be an integer") else default

    private fun Map<String, String>.optionalIntValue(key: String): Int? =
      get(key)?.toIntOrNull()
        ?: if (containsKey(key)) error("$key must be an integer") else null

    private fun Map<String, String>.booleanValue(
      key: String,
      default: Boolean,
    ): Boolean =
      get(key)?.toBooleanStrictOrNull()
        ?: if (containsKey(key)) error("$key must be true or false") else default

    private val CONTEXT_PATH_PATTERN = Regex("^/[\\w-/]*[a-zA-Z0-9]$")
  }
}
