package io.xoboro.server.sources.webdav

/**
 * Resolves [WebDavCredentials] from environment variables, never from the database.
 *
 * This follows the same rationale documented for OAuth2 client secrets
 * (`docs/api/native-v1.md`): the database is the one artifact that gets backed up, copied
 * elsewhere to debug, and restored onto other hosts, so it must never hold a secret that grants
 * access to a remote system. Anyone who can set environment variables for the process can
 * already configure this; the point is that a copy of `xoboro.sqlite` never can.
 *
 * The default credential comes from [USERNAME_KEY] / [PASSWORD_KEY]. A library can override it
 * by carrying an operator-chosen credential id in its stored root URL, as a URL fragment (see
 * [parseWebDavRoot]) - for example `https://nas.example.com/dav/manga#nas1` resolves against
 * `XOBORO_WEBDAV_NAS1_USERNAME` / `XOBORO_WEBDAV_NAS1_PASSWORD` first, falling back to the
 * unsuffixed default when the id-specific variable is not set. The fragment is never sent to the
 * server - it exists only so an operator can name a credential without changing the SPI.
 */
object WebDavCredentialsResolver {
  const val USERNAME_KEY: String = "XOBORO_WEBDAV_USERNAME"
  const val PASSWORD_KEY: String = "XOBORO_WEBDAV_PASSWORD"

  private val UNSAFE_SUFFIX_CHARACTERS = Regex("[^A-Z0-9_]")

  /**
   * A credential is resolved as a **pair**, never half at a time.
   *
   * Falling back per variable let a library that set only its own username be sent that
   * username with the default password. The server answers `401` and nothing in the message
   * says the two halves came from different places. So an id whose variables are present at all
   * is used on its own, and only an id with neither variable set falls through to the default.
   */
  fun resolve(
    credentialId: String?,
    environment: Map<String, String> = System.getenv(),
  ): WebDavCredentials? {
    val suffix = credentialId?.takeIf(String::isNotBlank)?.let(::environmentSuffix)
    val scoped = suffix?.let { environment.pairAt("XOBORO_WEBDAV$it") }
    return scoped ?: environment.pairAt("XOBORO_WEBDAV")
  }

  /**
   * Reads one `<prefix>_USERNAME`/`<prefix>_PASSWORD` pair. `null` when neither is set; a half-set
   * pair is an operator mistake and is refused rather than completed from somewhere else.
   */
  private fun Map<String, String>.pairAt(prefix: String): WebDavCredentials? {
    val username = this["${prefix}_USERNAME"]
    val password = this["${prefix}_PASSWORD"]
    if (username.isNullOrBlank() && password.isNullOrBlank()) return null
    require(!username.isNullOrBlank() && !password.isNullOrBlank()) {
      "WebDAV credentials at $prefix must set both a username and a password, or neither"
    }
    return WebDavCredentials(username, password)
  }

  private fun environmentSuffix(credentialId: String): String =
    "_" + credentialId.uppercase().replace(UNSAFE_SUFFIX_CHARACTERS, "_")
}
