package io.xoboro.server

import io.xoboro.core.application.OAuth2AccountLinking
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
  val oauth2AccountLinking: OAuth2AccountLinking = OAuth2AccountLinking.VERIFIED_EMAIL,
  val fontsDirectory: Path = Path.of("config/fonts"),
  val backupsDirectory: Path = Path.of("config/backups"),
  /**
   * Where the built web UI lives.
   *
   * Not under `config/`: this is a build product shipped with the release, not
   * something an operator edits. A missing directory is not an error - the server
   * runs headless perfectly well, and refusing to start without a UI would make
   * the API unusable for anyone who only wants the API.
   */
  val webDirectory: Path = Path.of("web"),
  val corsAllowedOrigins: Set<String> = emptySet(),
  val trustedProxyHosts: Set<String> = emptySet(),
  val metricsToken: String? = null,
  /**
   * Provisions the first administrator at startup when the server is still unclaimed.
   *
   * Held here rather than resolved at the point of use so that a malformed or unreadable secret fails
   * during configuration - loudly, before the port is bound - instead of halfway through opening the
   * runtime.
   */
  val initialAdministrator: InitialAdministrator? = null,
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
    require(trustedProxyHosts.all(PROXY_HOST_PATTERN::matches)) {
      "Trusted proxy hosts must be plain host names or IP addresses"
    }
    require(corsAllowedOrigins.all(::isValidCorsOrigin)) {
      "CORS allowed origins must be absolute HTTP origins without paths"
    }
    metricsToken?.let {
      require(it.length >= MINIMUM_METRICS_TOKEN_LENGTH) {
        "Metrics token must contain at least $MINIMUM_METRICS_TOKEN_LENGTH characters"
      }
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
        // Deliberately a Xoboro-prefixed name: Komga has no equivalent setting, so borrowing its
        // prefix would imply a compatibility this does not have.
        oauth2AccountLinking =
          environment["XOBORO_OAUTH2_ACCOUNT_LINKING"]
            ?.takeIf(String::isNotBlank)
            ?.trim()
            ?.uppercase()
            ?.let { value ->
              OAuth2AccountLinking.entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                  "XOBORO_OAUTH2_ACCOUNT_LINKING must be one of " +
                    OAuth2AccountLinking.entries.joinToString(", ") { it.name },
                )
            }
            ?: OAuth2AccountLinking.VERIFIED_EMAIL,
        fontsDirectory =
          environment["XOBORO_FONTS_PATH"]
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.let { if (it.isAbsolute) it.normalize() else normalizedWorkingDirectory.resolve(it).normalize() }
            ?: environment["KOMGA_FONTS_DATA_DIRECTORY"]
              ?.takeIf(String::isNotBlank)
              ?.let(Path::of)
              ?.let { if (it.isAbsolute) it.normalize() else normalizedWorkingDirectory.resolve(it).normalize() }
            ?: normalizedWorkingDirectory.resolve("config/fonts"),
        backupsDirectory =
          environment["XOBORO_BACKUPS_PATH"]
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.let { if (it.isAbsolute) it.normalize() else normalizedWorkingDirectory.resolve(it).normalize() }
            ?: normalizedWorkingDirectory.resolve("config/backups"),
        webDirectory =
          environment[XOBORO_WEB_PATH_KEY]
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.let { if (it.isAbsolute) it.normalize() else normalizedWorkingDirectory.resolve(it).normalize() }
            ?: normalizedWorkingDirectory.resolve("web"),
        corsAllowedOrigins =
          environment["KOMGA_CORS_ALLOWEDORIGINS"]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty(),
        trustedProxyHosts =
          environment["XOBORO_TRUSTED_PROXIES"]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty(),
        metricsToken =
          environment["XOBORO_METRICS_TOKEN"]
            ?.takeIf(String::isNotBlank),
        initialAdministrator = InitialAdministrator.fromEnvironment(environment),
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
    private val PROXY_HOST_PATTERN = Regex("^[\\[\\]A-Za-z0-9._:%-]+$")

    private fun isValidCorsOrigin(value: String): Boolean =
      value == "null" ||
        runCatching {
          val uri = java.net.URI(value)
          uri.scheme in setOf("http", "https") &&
            !uri.rawAuthority.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawPath.orEmpty().isEmpty() &&
            uri.rawQuery == null &&
            uri.rawFragment == null
        }.getOrDefault(false)
  }
}
