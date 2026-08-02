package io.xoboro.server.sources.webdav

import io.xoboro.core.application.SourceSidecarAccess
import java.net.URI
import java.nio.file.Path

/** Reads a named sidecar file (e.g. `series.json`) from inside a series' WebDAV directory. */
class WebDavSourceSidecarAccess(
  private val httpClient: WebDavHttpClient = WebDavHttpClient(),
  private val environment: Map<String, String> = System.getenv(),
) : SourceSidecarAccess {
  override val sourceId: String = WebDavLibraryRootInspector.SOURCE_ID

  override fun readSeriesSidecar(
    rootItemId: String,
    seriesItemId: String,
    fileName: String,
    maximumBytes: Int,
  ): ByteArray? {
    require(fileName.isNotBlank() && Path.of(fileName).fileName.toString() == fileName) {
      "Sidecar file name must be a simple file name"
    }
    require(maximumBytes > 0) { "Sidecar byte limit must be positive" }
    val root = rootItemId.parseWebDavRoot()
    val series = WebDavPaths.requireWithinRoot(root.baseUrl, seriesItemId, "Series item")
    val credentials = WebDavCredentialsResolver.resolve(root.credentialId, environment)
    // A relative reference resolves against the *directory containing* the base's last path
    // segment, not the base itself - so the base needs a trailing slash or "series.json" would
    // resolve as a sibling of the series directory instead of a child of it.
    val seriesDirectory = if (series.toString().endsWith("/")) series else URI("${series}/")
    val relativeFileName = URI(null, null, null, -1, fileName, null, null)
    val sidecarUrl = seriesDirectory.resolve(relativeFileName).toString()
    return httpClient.fetchBounded(sidecarUrl, credentials, maximumBytes)
  }
}
