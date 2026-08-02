package io.xoboro.server.sources.webdav

import io.xoboro.core.application.SourceArtwork
import io.xoboro.core.application.SourceArtworkAccess
import java.net.URI

/**
 * Finds sidecar cover images next to a book or inside a series directory, mirroring
 * [io.xoboro.server.sources.local.LocalSourceArtworkAccess]'s naming rules exactly (same
 * extensions, same series stem names, same "oversized candidate fails the whole lookup" choice)
 * so a WebDAV library and a local library agree on what counts as artwork.
 */
class WebDavSourceArtworkAccess(
  private val httpClient: WebDavHttpClient = WebDavHttpClient(),
  private val environment: Map<String, String> = System.getenv(),
) : SourceArtworkAccess {
  override val sourceId: String = WebDavLibraryRootInspector.SOURCE_ID

  override fun findBookArtwork(
    rootItemId: String,
    bookItemId: String,
    maximumBytes: Int,
  ): List<SourceArtwork> {
    val root = rootItemId.parseWebDavRoot()
    val book = WebDavPaths.requireWithinRoot(root.baseUrl, bookItemId, "Book artwork target")
    val leaf = book.decodedLastSegment()
    val baseName = leaf.substringBeforeLast('.', missingDelimiterValue = leaf)
    val pattern =
      Regex("""^${Regex.escape(baseName)}(?:-\d+)?\.(?:${EXTENSIONS.joinToString("|")})$""", RegexOption.IGNORE_CASE)
    val parentUrl = book.resolve(".").toString()
    return findArtwork(root, parentUrl, maximumBytes) { name -> pattern.matches(name) }
  }

  override fun findSeriesArtwork(
    rootItemId: String,
    seriesItemId: String,
    maximumBytes: Int,
  ): List<SourceArtwork> {
    val root = rootItemId.parseWebDavRoot()
    WebDavPaths.requireWithinRoot(root.baseUrl, seriesItemId, "Series artwork target")
    return findArtwork(root, seriesItemId, maximumBytes) { name ->
      val stem = name.substringBeforeLast('.', missingDelimiterValue = name).lowercase()
      val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
      stem in SERIES_STEMS && extension in EXTENSIONS
    }
  }

  private fun findArtwork(
    root: WebDavRootLocation,
    directoryUrl: String,
    maximumBytes: Int,
    matches: (String) -> Boolean,
  ): List<SourceArtwork> {
    require(maximumBytes > 0) { "Artwork byte limit must be positive" }
    val credentials = WebDavCredentialsResolver.resolve(root.credentialId, environment)
    val entries =
      httpClient
        .propfind(directoryUrl, credentials, depth = 1)
        .filterNot { it.url.normalizedForComparison() == directoryUrl.normalizedForComparison() }
        .filterNot { it.isCollection }
        .filter { matches(URI(it.url).decodedLastSegment()) }
        .sortedWith(
          compareBy<WebDavResource> { URI(it.url).decodedLastSegment().lowercase() }
            .thenBy { URI(it.url).decodedLastSegment() },
        )
    return entries.map { entry ->
      require(entry.contentLength == null || entry.contentLength <= maximumBytes) {
        "WebDAV artwork exceeds the byte limit"
      }
      val bytes =
        httpClient.fetchBounded(entry.url, credentials, maximumBytes)
          ?: error("WebDAV artwork disappeared between listing and download: ${entry.url}")
      SourceArtwork(URI(entry.url).decodedLastSegment(), bytes)
    }
  }

  private fun URI.decodedLastSegment(): String = path.orEmpty().trimEnd('/').substringAfterLast('/')

  private fun String.normalizedForComparison(): String = URI(this).normalize().toString().trimEnd('/')

  private companion object {
    val EXTENSIONS = setOf("png", "jpeg", "jpg", "tbn", "webp", "gif")
    val SERIES_STEMS = setOf("cover", "default", "folder", "poster", "series")
  }
}
