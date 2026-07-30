package io.xoboro.server.media

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Answers whether a ZIP container declares encrypted entries, by reading its central directory.
 *
 * `java.util.zip.ZipFile` rejects an encrypted archive when it is opened, with a `ZipException`
 * whose only description of the cause is its message text. Branching on that text would work today
 * and break silently on a JDK that rewords it - and the failure would be invisible, because the
 * fallback is the very generic code this probe exists to replace. So the flag is read from the file
 * format instead, where bit 0 of each entry's general-purpose flags means "encrypted".
 *
 * The probe runs only after opening has already failed, so its cost lands on a path that is
 * returning an error anyway.
 *
 * A `false` result means "not proven encrypted", not "certainly plaintext": a truncated or corrupt
 * container cannot be walked, and reporting it as unreadable is the right answer for it regardless.
 */
class ZipEncryptionProbe(
  private val maximumDirectorySize: Int = DEFAULT_MAXIMUM_DIRECTORY_SIZE,
) {
  init {
    require(maximumDirectorySize > 0) { "Central directory limit must be positive" }
  }

  fun declaresEncryptedEntries(path: Path): Boolean =
    try {
      Files.newByteChannel(path).use(::readsEncryptedEntry)
    } catch (_: IOException) {
      false
    } catch (_: IllegalArgumentException) {
      false
    }

  private fun readsEncryptedEntry(channel: SeekableByteChannel): Boolean {
    val directory = readCentralDirectory(channel) ?: return false
    var position = 0
    while (position + CENTRAL_ENTRY_SIZE <= directory.limit()) {
      if (directory.getInt(position) != CENTRAL_ENTRY_SIGNATURE) return false
      if (directory.unsigned16(position + CENTRAL_FLAGS_OFFSET) and ENCRYPTED_FLAG != 0) return true
      position +=
        CENTRAL_ENTRY_SIZE +
        directory.unsigned16(position + CENTRAL_NAME_LENGTH_OFFSET) +
        directory.unsigned16(position + CENTRAL_EXTRA_LENGTH_OFFSET) +
        directory.unsigned16(position + CENTRAL_COMMENT_LENGTH_OFFSET)
    }
    return false
  }

  private fun readCentralDirectory(channel: SeekableByteChannel): ByteBuffer? {
    val size = channel.size()
    if (size < END_RECORD_SIZE) return null
    val tailSize = minOf(size, (END_RECORD_SIZE + MAXIMUM_ARCHIVE_COMMENT).toLong()).toInt()
    val tail = channel.read(size - tailSize, tailSize) ?: return null
    val endRecord = tail.lastIndexOfSignature(END_RECORD_SIGNATURE, END_RECORD_SIZE) ?: return null
    val declaredOffset = tail.unsigned32(endRecord + END_DIRECTORY_OFFSET)
    val declaredSize = tail.unsigned32(endRecord + END_DIRECTORY_SIZE_OFFSET)
    val (offset, directorySize) =
      if (declaredOffset == UNSET_32 || declaredSize == UNSET_32) {
        readZip64Directory(channel, tail, endRecord) ?: return null
      } else {
        declaredOffset to declaredSize
      }
    if (directorySize <= 0 || directorySize > maximumDirectorySize) return null
    if (offset < 0 || offset + directorySize > size) return null
    return channel.read(offset, directorySize.toInt())
  }

  /**
   * Follows the ZIP64 locator that sits immediately before the end record. A ZIP64 archive stores
   * `0xffffffff` in the 32-bit fields, so the real offsets are only reachable this way.
   */
  private fun readZip64Directory(
    channel: SeekableByteChannel,
    tail: ByteBuffer,
    endRecord: Int,
  ): Pair<Long, Long>? {
    val locator = endRecord - ZIP64_LOCATOR_SIZE
    if (locator < 0 || tail.getInt(locator) != ZIP64_LOCATOR_SIGNATURE) return null
    val recordOffset = tail.getLong(locator + ZIP64_LOCATOR_RECORD_OFFSET)
    if (recordOffset < 0 || recordOffset + ZIP64_END_RECORD_SIZE > channel.size()) return null
    val record = channel.read(recordOffset, ZIP64_END_RECORD_SIZE) ?: return null
    if (record.getInt(0) != ZIP64_END_RECORD_SIGNATURE) return null
    return record.getLong(ZIP64_DIRECTORY_OFFSET) to record.getLong(ZIP64_DIRECTORY_SIZE_OFFSET)
  }

  private fun SeekableByteChannel.read(
    offset: Long,
    length: Int,
  ): ByteBuffer? {
    val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
    position(offset)
    while (buffer.hasRemaining()) {
      if (read(buffer) < 0) return null
    }
    buffer.flip()
    return buffer
  }

  private fun ByteBuffer.lastIndexOfSignature(
    signature: Int,
    trailingBytes: Int,
  ): Int? {
    for (index in limit() - trailingBytes downTo 0) {
      if (getInt(index) == signature) return index
    }
    return null
  }

  private fun ByteBuffer.unsigned16(index: Int): Int = getShort(index).toInt() and 0xffff

  private fun ByteBuffer.unsigned32(index: Int): Long = getInt(index).toLong() and 0xffffffffL

  private companion object {
    const val DEFAULT_MAXIMUM_DIRECTORY_SIZE = 64 * 1_024 * 1_024
    const val MAXIMUM_ARCHIVE_COMMENT = 0xffff
    const val UNSET_32 = 0xffffffffL
    const val ENCRYPTED_FLAG = 0x0001

    const val END_RECORD_SIGNATURE = 0x06054b50
    const val END_RECORD_SIZE = 22
    const val END_DIRECTORY_SIZE_OFFSET = 12
    const val END_DIRECTORY_OFFSET = 16

    const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
    const val ZIP64_LOCATOR_SIZE = 20
    const val ZIP64_LOCATOR_RECORD_OFFSET = 8
    const val ZIP64_END_RECORD_SIGNATURE = 0x06064b50
    const val ZIP64_END_RECORD_SIZE = 56
    const val ZIP64_DIRECTORY_SIZE_OFFSET = 40
    const val ZIP64_DIRECTORY_OFFSET = 48

    const val CENTRAL_ENTRY_SIGNATURE = 0x02014b50
    const val CENTRAL_ENTRY_SIZE = 46
    const val CENTRAL_FLAGS_OFFSET = 8
    const val CENTRAL_NAME_LENGTH_OFFSET = 28
    const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
    const val CENTRAL_COMMENT_LENGTH_OFFSET = 32
  }
}
