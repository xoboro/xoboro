package io.xoboro.server.sources.webdav

import io.xoboro.server.media.RandomAccessMedia
import io.xoboro.server.media.SourceRandomAccess
import io.xoboro.server.media.ZipCentralDirectory

/**
 * Reads a WebDAV item by `Range` request, so analysis costs the bytes it looks at rather than the
 * whole file.
 *
 * [open] issues exactly one request: a suffix range for [ZipCentralDirectory.TRAILER_SEARCH_BYTES],
 * which answers both the item's length (from `Content-Range`) and the trailing bytes a
 * trailer-based format needs. For an ordinary comic archive the entire central directory arrives in
 * that one response, so analyzing a book costs one round trip and nothing more.
 *
 * Measured against a real library over the same link, for a 9,449,145-byte archive with 79 entries:
 * the suffix response was 65,536 bytes in 25 ms and held the complete central directory, against
 * 520 ms to `GET` the whole archive.
 */
class WebDavSourceRandomAccess(
  private val httpClient: WebDavHttpClient = WebDavHttpClient(),
  private val environment: Map<String, String> = System.getenv(),
) : SourceRandomAccess {
  override val sourceId: String = SOURCE_ID

  override fun open(
    rootItemId: String,
    itemId: String,
  ): RandomAccessMedia {
    val root = rootItemId.parseWebDavRoot()
    WebDavPaths.requireWithinRoot(root.baseUrl, itemId, "WebDAV media item")
    val credentials = WebDavCredentialsResolver.resolve(root.credentialId, environment)
    val trailer =
      httpClient.fetchSuffix(itemId, credentials, ZipCentralDirectory.TRAILER_SEARCH_BYTES)
        ?: throw WebDavRequestFailedException("GET", itemId, 404)
    return WebDavRandomAccessMedia(itemId, credentials, trailer, httpClient)
  }

  private class WebDavRandomAccessMedia(
    private val url: String,
    private val credentials: WebDavCredentials?,
    trailer: WebDavRange,
    private val httpClient: WebDavHttpClient,
  ) : RandomAccessMedia {
    override val size: Long = trailer.totalLength

    private val trailerBytes: ByteArray = trailer.bytes
    private val trailerOffset: Long = size - trailerBytes.size

    init {
      require(trailerOffset >= 0) {
        "WebDAV server returned ${trailerBytes.size} trailing bytes for a $size byte item"
      }
    }

    override fun read(
      offset: Long,
      length: Int,
    ): ByteArray {
      require(offset >= 0) { "Read offset must not be negative" }
      require(length >= 0) { "Read length must not be negative" }
      if (offset >= size || length == 0) return ByteArray(0)
      val expected = minOf(length.toLong(), size - offset).toInt()
      if (offset >= trailerOffset) {
        val start = (offset - trailerOffset).toInt()
        return trailerBytes.copyOfRange(start, minOf(start + expected, trailerBytes.size))
      }
      val range =
        httpClient.fetchRange(url, credentials, offset, expected)
          ?: throw WebDavRequestFailedException("GET", url, 404)
      return range.bytes
    }

    /** Nothing is held open: each range is its own request, and the trailer is already in memory. */
    override fun close() = Unit
  }

  companion object {
    const val SOURCE_ID: String = "webdav"
  }
}
