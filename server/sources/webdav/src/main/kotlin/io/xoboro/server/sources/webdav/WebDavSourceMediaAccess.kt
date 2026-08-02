package io.xoboro.server.sources.webdav

import io.xoboro.server.media.MaterializedMedia
import io.xoboro.server.media.SourceMediaAccess
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Materializes a WebDAV media item onto local disk through [WebDavMediaCache], since analyzers
 * need a real [Path] and there is no such thing as a WebDAV file descriptor.
 *
 * A per-key lock ([keyLocks]) serializes concurrent `materialize` calls for the same item, so two
 * readers opening the same book at once trigger one `GET` and one cache write rather than two
 * racing downloads.
 */
class WebDavSourceMediaAccess(
  private val cacheDirectory: Path,
  maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
  private val httpClient: WebDavHttpClient = WebDavHttpClient(),
  private val environment: Map<String, String> = System.getenv(),
) : SourceMediaAccess {
  override val sourceId: String = SOURCE_ID

  private val cache = WebDavMediaCache(cacheDirectory, maxCacheBytes)
  private val keyLocks = ConcurrentHashMap<String, Any>()

  override fun materialize(
    rootItemId: String,
    itemId: String,
  ): MaterializedMedia {
    val root = rootItemId.parseWebDavRoot()
    WebDavPaths.requireWithinRoot(root.baseUrl, itemId, "WebDAV media item")
    val credentials = WebDavCredentialsResolver.resolve(root.credentialId, environment)
    val lock = keyLocks.computeIfAbsent(itemId) { Any() }
    synchronized(lock) {
      val cached = cache.peek(itemId)
      val tempFile = Files.createTempFile(cacheDirectory, ".xoboro-webdav-", ".tmp")
      try {
        val outcome =
          httpClient.fetchToFile(itemId, credentials, tempFile, cached?.etag, cached?.lastModifiedHttpDate)
            ?: throw WebDavRequestFailedException("GET", itemId, 404)
        val path =
          when (outcome) {
            is WebDavGetOutcome.NotModified ->
              cache.acquire(itemId)
                ?: error("WebDAV cache entry missing for a 304 response: $itemId")
            is WebDavGetOutcome.Fresh ->
              cache.store(
                key = itemId,
                tempFile = tempFile,
                etag = outcome.etag,
                lastModifiedHttpDate = outcome.lastModifiedHttpDate,
                size = outcome.contentLength ?: Files.size(tempFile),
              )
          }
        return WebDavMaterializedMedia(path, cache, itemId)
      } finally {
        Files.deleteIfExists(tempFile)
      }
    }
  }

  private class WebDavMaterializedMedia(
    override val path: Path,
    private val cache: WebDavMediaCache,
    private val key: String,
  ) : MaterializedMedia {
    override fun close() = cache.release(key)
  }

  companion object {
    const val SOURCE_ID: String = "webdav"
    const val DEFAULT_MAX_CACHE_BYTES: Long = 2L * 1024 * 1024 * 1024
  }
}
