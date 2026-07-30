package io.xoboro.server

import java.nio.file.Files
import java.nio.file.Path

/**
 * Credentials for provisioning the first administrator without the interactive setup call.
 *
 * A deployment that comes up from a Compose file or a Kubernetes manifest has nobody to make the
 * `POST /setup` request, so it either stays unclaimed — reachable and waiting for whoever finds it
 * first — or somebody scripts a curl against it. This makes the first case impossible to reach by
 * accident.
 *
 * [toString] redacts the password. [ServerConfig] holds one of these, and a config object is exactly
 * the sort of thing that ends up in a log line or an exception message.
 */
data class InitialAdministrator(
  val email: String,
  val password: String,
) {
  init {
    require(email.isNotBlank()) { "Initial administrator email must not be blank" }
    require(password.isNotBlank()) { "Initial administrator password must not be blank" }
  }

  override fun toString(): String = "InitialAdministrator(email=$email, password=[REDACTED])"

  companion object {
    /**
     * Resolves the configured initial administrator, or null when none is configured.
     *
     * **The file form is preferred and takes precedence.** A password in the process environment is
     * readable from `/proc/<pid>/environ`, appears in `docker inspect`, and ends up committed in the
     * Compose file that sets it. A file can carry restrictive permissions and is what Docker and
     * Kubernetes secrets already mount. The environment form exists because it is what people reach
     * for first, not because it is the right one.
     *
     * A configured email with no password is an **error, not a silent skip**. An operator who set one
     * half of this expected provisioning to happen, and a server that quietly came up unclaimed
     * instead would be reachable by whoever found it first — the exact outcome this exists to prevent.
     *
     * A password file that is missing or empty is likewise an error. A secret mount that failed is not
     * the same as "no secret configured", and treating them alike would turn a broken deployment into
     * an open one.
     */
    fun fromEnvironment(
      environment: Map<String, String>,
      readFile: (Path) -> String = { Files.readString(it) },
    ): InitialAdministrator? {
      val email = environment[EMAIL_KEY]?.takeIf(String::isNotBlank)
      val passwordFile = environment[PASSWORD_FILE_KEY]?.takeIf(String::isNotBlank)
      val password = environment[PASSWORD_KEY]?.takeIf(String::isNotBlank)
      if (email == null && passwordFile == null && password == null) return null
      requireNotNull(email) {
        "$EMAIL_KEY must be set when $PASSWORD_KEY or $PASSWORD_FILE_KEY is set"
      }
      val resolved =
        if (passwordFile != null) {
          val contents =
            try {
              readFile(Path.of(passwordFile))
            } catch (failure: Exception) {
              throw IllegalArgumentException(
                "$PASSWORD_FILE_KEY could not be read: ${failure.message}",
                failure,
              )
            }
          // Trailing newline only. A password may legitimately contain leading or inner whitespace, and
          // trimming it would silently change the secret rather than reject a malformed file.
          contents.removeSuffix("\n").removeSuffix("\r").takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$PASSWORD_FILE_KEY is empty")
        } else {
          requireNotNull(password) {
            "$PASSWORD_KEY or $PASSWORD_FILE_KEY must be set when $EMAIL_KEY is set"
          }
        }
      return InitialAdministrator(email = email, password = resolved)
    }

    const val EMAIL_KEY: String = "XOBORO_INITIAL_ADMIN_EMAIL"
    const val PASSWORD_KEY: String = "XOBORO_INITIAL_ADMIN_PASSWORD"
    const val PASSWORD_FILE_KEY: String = "XOBORO_INITIAL_ADMIN_PASSWORD_FILE"
  }
}
