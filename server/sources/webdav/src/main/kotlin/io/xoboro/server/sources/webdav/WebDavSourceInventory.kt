package io.xoboro.server.sources.webdav

import io.xoboro.core.application.SourceFile
import io.xoboro.core.application.SourceInventory
import io.xoboro.core.application.SourceInventoryFailure
import io.xoboro.core.application.SourceInventorySummary
import io.xoboro.core.application.SourceInventoryUnavailableException
import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.Instant

class WebDavInventoryUnavailableException(
  rootItemId: String,
  cause: Throwable? = null,
) : SourceInventoryUnavailableException(
    "WebDAV inventory root is not a readable collection: $rootItemId",
    cause,
  )

/**
 * Walks a WebDAV tree breadth-first using one `PROPFIND Depth: 1` request per directory, since the
 * real target this was built against answers `Depth: 1` reliably but is not asserted to support
 * `Depth: infinity`. An explicit queue is used rather than recursion so a library with thousands
 * of series does not grow the call stack with it.
 */
class WebDavSourceInventory(
  override val sourceId: String = WebDavLibraryRootInspector.SOURCE_ID,
  private val httpClient: WebDavHttpClient = WebDavHttpClient(),
  private val environment: Map<String, String> = System.getenv(),
) : SourceInventory {
  init {
    require(sourceId.isNotBlank()) { "WebDAV source ID must not be blank" }
  }

  override fun inventory(
    rootItemId: String,
    directoryExclusions: Set<String>,
    onFile: (SourceFile) -> Unit,
    onFailure: (SourceInventoryFailure) -> Unit,
  ): SourceInventorySummary {
    require(directoryExclusions.none(String::isBlank)) {
      "Directory exclusions must not contain blank entries"
    }
    val root = rootItemId.parseWebDavRoot()
    val credentials = WebDavCredentialsResolver.resolve(root.credentialId, environment)

    var visitedDirectories = 0L
    var emittedFiles = 0L
    var skippedDirectories = 0L
    var failedEntries = 0L

    val rootListing =
      try {
        httpClient.propfind(root.baseUrl, credentials, depth = 1)
      } catch (failure: WebDavRequestFailedException) {
        throw WebDavInventoryUnavailableException(rootItemId, failure)
      }
    if (rootListing.firstOrNull { it.isSelf(root.baseUrl) }?.isCollection != true) {
      throw WebDavInventoryUnavailableException(rootItemId)
    }

    val queue = ArrayDeque<Pair<String, String>>()
    queue.addLast(root.baseUrl to "")
    var listing: List<WebDavResource>? = rootListing
    while (queue.isNotEmpty()) {
      val (directoryUrl, relativePrefix) = queue.removeFirst()
      val entries =
        listing ?: try {
          httpClient.propfind(directoryUrl, credentials, depth = 1)
        } catch (failure: WebDavRequestFailedException) {
          failedEntries += 1
          onFailure(SourceInventoryFailure(directoryUrl, failure.message ?: "WebDAV request failed"))
          continue
        }
      listing = null
      visitedDirectories += 1

      entries.filterNot { it.isSelf(directoryUrl) }.forEach { entry ->
        val decodedName = URI(entry.url).decodedFileName()
        if (entry.isCollection) {
          val childRelative = relativePrefix.appendSegment(decodedName)
          val shouldSkip =
            decodedName.startsWith(".") ||
              directoryExclusions.any { exclusion -> childRelative.contains(exclusion, ignoreCase = true) }
          if (shouldSkip) {
            skippedDirectories += 1
          } else {
            queue.addLast(entry.url to childRelative)
          }
        } else if (!decodedName.startsWith(".")) {
          onFile(entry.toSourceFile(directoryUrl, relativePrefix.appendSegment(decodedName), decodedName))
          emittedFiles += 1
        }
      }
    }

    return SourceInventorySummary(visitedDirectories, emittedFiles, skippedDirectories, failedEntries)
  }

  private fun WebDavResource.isSelf(queriedUrl: String): Boolean = url.normalizedForComparison() == queriedUrl.normalizedForComparison()

  private fun String.normalizedForComparison(): String = URI(this).normalize().toString().trimEnd('/')

  private fun String.appendSegment(segment: String): String = if (isEmpty()) segment else "$this/$segment"

  private fun URI.decodedFileName(): String = path.orEmpty().trimEnd('/').substringAfterLast('/')

  private fun WebDavResource.toSourceFile(
    parentUrl: String,
    relativePath: String,
    name: String,
  ): SourceFile =
    SourceFile(
      itemId = url,
      parentItemId = parentUrl,
      identity = etag,
      relativePath = relativePath,
      name = name,
      extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(),
      size = contentLength ?: 0L,
      modifiedAtMillis = lastModifiedHttpDate?.toEpochMillisOrNull() ?: 0L,
    )

  private fun String.toEpochMillisOrNull(): Long? =
    runCatching { DateTimeFormatter.RFC_1123_DATE_TIME.parse(this, Instant::from).toEpochMilli() }.getOrNull()
}
