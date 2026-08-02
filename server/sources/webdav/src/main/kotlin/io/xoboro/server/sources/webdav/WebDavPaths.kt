package io.xoboro.server.sources.webdav

import java.net.URI

/**
 * Root-containment checks shared by every WebDAV [io.xoboro.core.application] and
 * [io.xoboro.server.media] port implementation, mirroring what `archive.startsWith(root)` does
 * for the local adapter's real filesystem paths.
 *
 * There is no symlink-following equivalent here: a WebDAV href is either inside the requested
 * collection subtree or it is not, decided purely from decoded URL path segments. `java.net.URI`
 * percent-decodes each path segment as UTF-8 when [URI.getPath] is read, so this comparison
 * already treats `%C3%A9` and `é` as the same segment.
 */
object WebDavPaths {
  /** Validates that [itemUrl] shares scheme and authority with [rootBaseUrl] and sits at or below its path. */
  fun requireWithinRoot(
    rootBaseUrl: String,
    itemUrl: String,
    label: String,
  ): URI {
    val root = rootBaseUrl.toValidatedWebDavUri()
    val item = itemUrl.toValidatedWebDavUri()
    val sameOrigin =
      root.scheme.equals(item.scheme, ignoreCase = true) &&
        root.host.equals(item.host, ignoreCase = true) &&
        root.effectivePort() == item.effectivePort()
    require(sameOrigin) { "$label must share scheme and host with its library root" }
    val rootSegments = root.pathSegments()
    val itemSegments = item.pathSegments()
    require(itemSegments.size >= rootSegments.size && itemSegments.subList(0, rootSegments.size) == rootSegments) {
      "$label must remain inside its library root"
    }
    return item
  }

  private fun URI.effectivePort(): Int = if (port == -1) scheme.lowercase().let { if (it == "https") 443 else 80 } else port

  private fun URI.pathSegments(): List<String> = path.orEmpty().split('/').filter(String::isNotEmpty)
}
