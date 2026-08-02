package io.xoboro.server.sources.webdav

import java.net.URI
import java.net.URISyntaxException

class InvalidWebDavItemException(
  itemId: String,
  cause: Throwable? = null,
) : IllegalArgumentException("Invalid WebDAV source item URL: $itemId", cause)

/**
 * A parsed WebDAV library root: the actual base URL to request against, plus an optional
 * operator-chosen credential id carried in the URL fragment.
 *
 * See [WebDavCredentialsResolver] for how [credentialId] selects an environment-variable
 * override. The fragment is stripped from [baseUrl] and is never sent in a request - URL
 * fragments are a client-side-only construct, which makes this a safe place to stash an id
 * without touching the wire protocol or the source-agnostic `SourceLocation` shape.
 */
data class WebDavRootLocation(
  val baseUrl: String,
  val credentialId: String?,
)

/** Validates that this string is an `http`/`https` URL with no embedded user info, and no bare host-only form. */
fun String.toValidatedWebDavUri(): URI {
  val uri =
    try {
      URI(this)
    } catch (failure: URISyntaxException) {
      throw InvalidWebDavItemException(this, failure)
    }
  if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
    throw InvalidWebDavItemException(this)
  }
  // Credentials travel only through the Authorization header (see WebDavHttpClient); a URL that
  // already embeds them would mean they were put there by something outside this adapter, and
  // reusing that URL verbatim risks echoing the credential into a log line or an error message.
  if (uri.rawUserInfo != null) throw InvalidWebDavItemException(this)
  return uri
}

/** Splits a stored library root into its request base URL and optional credential id. See [WebDavRootLocation]. */
fun String.parseWebDavRoot(): WebDavRootLocation {
  val uri = toValidatedWebDavUri()
  val credentialId = uri.rawFragment?.takeIf(String::isNotBlank)
  val base = URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, null).toString()
  return WebDavRootLocation(baseUrl = base.trimEnd('/').ifEmpty { base }, credentialId = credentialId)
}
