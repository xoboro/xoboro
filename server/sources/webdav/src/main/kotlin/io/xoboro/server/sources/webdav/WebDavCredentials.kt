package io.xoboro.server.sources.webdav

/**
 * Basic-auth credentials for a WebDAV server.
 *
 * [toString] is overridden so a credential never appears in a log line by accident - the
 * default `data class` rendering would otherwise print [password] in plain text the first time
 * one of these ends up in an exception, a debug log, or a test failure message.
 */
data class WebDavCredentials(
  val username: String,
  val password: String,
) {
  init {
    require(username.isNotBlank()) { "WebDAV username must not be blank" }
    require(password.isNotBlank()) { "WebDAV password must not be blank" }
  }

  override fun toString(): String = "WebDavCredentials(username=$username, password=***)"
}
