package io.xoboro.server.sources.webdav

import java.io.IOException

/**
 * A WebDAV request did not succeed. The message names only the HTTP method, the request URL, and
 * the status code - never a header value - so a caught-and-logged instance of this can never leak
 * a credential. [WebDavPaths] and [String.toValidatedWebDavUri] additionally reject any URL that
 * embeds user info, so [url] itself is never a place a password could have hidden.
 */
open class WebDavRequestFailedException(
  method: String,
  url: String,
  val statusCode: Int,
  cause: Throwable? = null,
) : IOException(
    // The cause is named in the message, not only chained: the durable task queue records
    // `last_error` from the message, so every one of 13,983 dead tasks read "status -1" and gave
    // an operator nothing to diagnose. A status of -1 means the request never got an answer, and
    // which failure that was is the only useful part.
    buildString {
      append("WebDAV $method $url failed with status $statusCode")
      cause?.let { append(": ${it::class.simpleName}: ${it.message}") }
    },
    cause,
  )

/** The server rejected the credentials (or lack of them) with `401 Unauthorized`. */
class WebDavAuthenticationException(
  method: String,
  url: String,
) : WebDavRequestFailedException(method, url, 401)
