package io.xoboro.server.media

/**
 * Serves ranges out of memory and counts them.
 *
 * The count is the point: "one range request per book" is the whole reason the directory-reading
 * path exists, and a test that only checks the parsed entries would keep passing if that became four
 * requests.
 */
internal class ByteArrayRandomAccessMedia(
  private val bytes: ByteArray,
) : RandomAccessMedia {
  var reads: Int = 0
    private set

  var closed: Boolean = false
    private set

  override val size: Long = bytes.size.toLong()

  override fun read(
    offset: Long,
    length: Int,
  ): ByteArray {
    require(offset >= 0) { "Read offset must not be negative" }
    require(length >= 0) { "Read length must not be negative" }
    reads++
    if (offset >= size) return ByteArray(0)
    val end = minOf(offset + length, size).toInt()
    return bytes.copyOfRange(offset.toInt(), end)
  }

  override fun close() {
    closed = true
  }
}
