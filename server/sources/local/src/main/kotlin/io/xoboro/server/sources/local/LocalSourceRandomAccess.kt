package io.xoboro.server.sources.local

import io.xoboro.server.media.RandomAccessMedia
import io.xoboro.server.media.SourceRandomAccess
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Serves ranges of a local file, which a local source could always do - the SPI simply had no way to
 * ask for one.
 *
 * Registering it here is not about speed: a local read of a whole archive is already fast. It is so
 * that the ranged analysis path is exercised by the same code on both sources, instead of existing
 * only on the remote one where nothing but a live WebDAV server could test it.
 */
class LocalSourceRandomAccess : SourceRandomAccess {
  override val sourceId: String = "local"

  override fun open(
    rootItemId: String,
    itemId: String,
  ): RandomAccessMedia {
    val path = LocalMediaItemPath.resolve(rootItemId, itemId)
    return LocalRandomAccessMedia(Files.newByteChannel(path, StandardOpenOption.READ))
  }

  private class LocalRandomAccessMedia(
    private val channel: SeekableByteChannel,
  ) : RandomAccessMedia {
    override val size: Long = channel.size()

    override fun read(
      offset: Long,
      length: Int,
    ): ByteArray {
      require(offset >= 0) { "Read offset must not be negative" }
      require(length >= 0) { "Read length must not be negative" }
      if (offset >= size) return ByteArray(0)
      val expected = minOf(length.toLong(), size - offset).toInt()
      val buffer = ByteBuffer.allocate(expected)
      channel.position(offset)
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) break
      }
      return if (buffer.position() == expected) buffer.array() else buffer.array().copyOf(buffer.position())
    }

    override fun close() = channel.close()
  }
}
