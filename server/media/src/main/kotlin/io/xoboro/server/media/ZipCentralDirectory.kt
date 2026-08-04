package io.xoboro.server.media

/**
 * One central-directory record: what a page list needs, plus what locating a single entry needs.
 *
 * [localHeaderOffset] is here because *one* entry by range is a clear win and *every* entry by range
 * is a clear loss, and the difference is only in how many. Reading one page costs two range requests
 * (~50 ms measured) against 520 ms to fetch a 9 MB archive; reading all 150 entries that way cost
 * 6.52 s against the same 520 ms. So [ZipRangedEntryReader] uses this for a cover or a page, and
 * [ZipDirectoryMediaAnalyzer] deliberately does not touch entry data at all.
 */
data class ZipDirectoryEntry(
  val name: String,
  val compressedSize: Long,
  val uncompressedSize: Long,
  val encrypted: Boolean,
  val localHeaderOffset: Long,
  val compressionMethod: Int,
) {
  init {
    require(name.isNotEmpty()) { "ZIP entry name must not be empty" }
    require(compressedSize >= 0) { "ZIP entry compressed size must not be negative" }
    require(uncompressedSize >= 0) { "ZIP entry uncompressed size must not be negative" }
    require(localHeaderOffset >= 0) { "ZIP entry local header offset must not be negative" }
  }

  val isDirectory: Boolean get() = name.endsWith("/")

  val isStored: Boolean get() = compressionMethod == STORED_METHOD

  val isDeflated: Boolean get() = compressionMethod == DEFLATED_METHOD

  companion object {
    const val STORED_METHOD: Int = 0
    const val DEFLATED_METHOD: Int = 8
  }
}

/**
 * The archive's trailer could not be read as a ZIP central directory.
 *
 * Distinct from a read failure: the bytes arrived and are not a ZIP. Callers turn this into the same
 * `UNREADABLE_CONTAINER` diagnosis `ZipFile` produces, or fall back to materializing the file.
 */
class ZipDirectoryUnreadableException(
  message: String,
) : IllegalArgumentException(message)

/**
 * Reads a ZIP's central directory through [RandomAccessMedia], without opening the archive.
 *
 * `java.util.zip.ZipFile` is the obvious tool and cannot be used: it takes a `File`, so reaching it
 * at all means the remote archive is already fully on local disk, which is the cost this exists to
 * avoid.
 *
 * Only the trailer is parsed. Entry *data* is never touched, which is what makes a book cost one
 * range request instead of one per page - and also what this cannot answer: no image dimensions, no
 * content-sniffed media types, no page hashes.
 */
object ZipCentralDirectory {
  fun read(media: RandomAccessMedia): List<ZipDirectoryEntry> {
    val size = media.size
    if (size < END_RECORD_BYTES) {
      throw ZipDirectoryUnreadableException("Archive is shorter than a ZIP end record: $size bytes")
    }
    val tailLength = minOf(size, TRAILER_SEARCH_BYTES.toLong()).toInt()
    val tailOffset = size - tailLength
    val tail = media.read(tailOffset, tailLength)
    if (tail.size != tailLength) {
      throw ZipDirectoryUnreadableException(
        "Short read of the ZIP trailer: wanted $tailLength bytes at $tailOffset, got ${tail.size}",
      )
    }
    val endRecord =
      tail.findEndRecord()
        ?: throw ZipDirectoryUnreadableException("No ZIP end-of-central-directory record in the last $tailLength bytes")
    val locator = tail.zip64Locator(endRecord, tailOffset, media)
    val entryCount = locator?.entryCount ?: tail.u16(endRecord + END_ENTRY_COUNT_OFFSET).toLong()
    val directorySize = locator?.directorySize ?: tail.u32(endRecord + END_DIRECTORY_SIZE_OFFSET)
    val directoryOffset = locator?.directoryOffset ?: tail.u32(endRecord + END_DIRECTORY_OFFSET_OFFSET)
    if (directorySize > MAXIMUM_DIRECTORY_BYTES) {
      throw ZipDirectoryUnreadableException("ZIP central directory is larger than $MAXIMUM_DIRECTORY_BYTES bytes: $directorySize")
    }
    if (directoryOffset < 0 || directoryOffset + directorySize > size) {
      throw ZipDirectoryUnreadableException("ZIP central directory at $directoryOffset+$directorySize falls outside a $size byte archive")
    }
    // Reuse the trailer already in hand whenever it covers the directory. A comic archive's
    // directory is a few kilobytes, so this is the common case and it is what keeps a book at one
    // request rather than two.
    val directory =
      if (directoryOffset >= tailOffset) {
        val start = (directoryOffset - tailOffset).toInt()
        tail.copyOfRange(start, start + directorySize.toInt())
      } else {
        media.read(directoryOffset, directorySize.toInt()).also {
          if (it.size != directorySize.toInt()) {
            throw ZipDirectoryUnreadableException(
              "Short read of the ZIP central directory: wanted $directorySize bytes at $directoryOffset, got ${it.size}",
            )
          }
        }
      }
    return directory.parseEntries(entryCount)
  }

  private fun ByteArray.parseEntries(entryCount: Long): List<ZipDirectoryEntry> {
    val entries = ArrayList<ZipDirectoryEntry>(entryCount.coerceAtMost(MAXIMUM_PREALLOCATED_ENTRIES).toInt())
    var position = 0
    while (position + DIRECTORY_RECORD_BYTES <= size) {
      if (u32(position) != DIRECTORY_RECORD_SIGNATURE) break
      val flags = u16(position + DIRECTORY_FLAGS_OFFSET)
      val nameLength = u16(position + DIRECTORY_NAME_LENGTH_OFFSET)
      val extraLength = u16(position + DIRECTORY_EXTRA_LENGTH_OFFSET)
      val commentLength = u16(position + DIRECTORY_COMMENT_LENGTH_OFFSET)
      val nameStart = position + DIRECTORY_RECORD_BYTES
      val extraStart = nameStart + nameLength
      val recordEnd = extraStart + extraLength + commentLength
      if (nameLength == 0 || recordEnd > size) break
      var compressedSize = u32(position + DIRECTORY_COMPRESSED_SIZE_OFFSET)
      var uncompressedSize = u32(position + DIRECTORY_UNCOMPRESSED_SIZE_OFFSET)
      var localHeaderOffset = u32(position + DIRECTORY_LOCAL_OFFSET_OFFSET)
      val uncompressedOverflowed = uncompressedSize == SIZE_SENTINEL
      val compressedOverflowed = compressedSize == SIZE_SENTINEL
      val offsetOverflowed = localHeaderOffset == SIZE_SENTINEL
      if (uncompressedOverflowed || compressedOverflowed || offsetOverflowed) {
        // The ZIP64 extra field carries only the fields that overflowed, in a fixed order, so which
        // ones are read has to mirror which ones were sentinels - reading it positionally instead
        // would attribute a size to an offset the moment only one of them overflowed.
        zip64Fields(
          extraStart = extraStart,
          extraLength = extraLength,
          uncompressedOverflowed = uncompressedOverflowed,
          compressedOverflowed = compressedOverflowed,
          offsetOverflowed = offsetOverflowed,
        )?.let { fields ->
          fields.uncompressedSize?.let { uncompressedSize = it }
          fields.compressedSize?.let { compressedSize = it }
          fields.localHeaderOffset?.let { localHeaderOffset = it }
        }
      }
      entries.add(
        ZipDirectoryEntry(
          // Decoded as UTF-8 whether or not the entry declares it, matching what `ZipFile` does by
          // default - so a library that switches between this path and the materializing one keeps
          // the same page file names.
          name = decodeToString(nameStart, extraStart, throwOnInvalidSequence = false),
          compressedSize = compressedSize,
          uncompressedSize = uncompressedSize,
          encrypted = flags and ENCRYPTED_FLAG != 0,
          localHeaderOffset = localHeaderOffset,
          compressionMethod = u16(position + DIRECTORY_METHOD_OFFSET),
        ),
      )
      position = recordEnd
    }
    if (entries.isEmpty()) throw ZipDirectoryUnreadableException("ZIP central directory held no parseable record")
    return entries
  }

  /**
   * Reads the ZIP64 extra field, consuming one 8-byte value per field the 32-bit record declared as
   * a sentinel, in the format's fixed order: uncompressed size, compressed size, local header
   * offset. Any of them may come back `null` when the field is shorter than declared.
   */
  private fun ByteArray.zip64Fields(
    extraStart: Int,
    extraLength: Int,
    uncompressedOverflowed: Boolean,
    compressedOverflowed: Boolean,
    offsetOverflowed: Boolean,
  ): Zip64Fields? {
    var position = extraStart
    val end = extraStart + extraLength
    while (position + EXTRA_HEADER_BYTES <= end) {
      val id = u16(position)
      val length = u16(position + 2)
      val dataStart = position + EXTRA_HEADER_BYTES
      if (dataStart + length > end) return null
      if (id == ZIP64_EXTRA_ID) {
        var cursor = dataStart
        val dataEnd = dataStart + length
        fun nextValue(): Long? {
          if (cursor + 8 > dataEnd) return null
          return u64(cursor).also { cursor += 8 }
        }
        return Zip64Fields(
          uncompressedSize = if (uncompressedOverflowed) nextValue() else null,
          compressedSize = if (compressedOverflowed) nextValue() else null,
          localHeaderOffset = if (offsetOverflowed) nextValue() else null,
        )
      }
      position = dataStart + length
    }
    return null
  }

  private data class Zip64Fields(
    val uncompressedSize: Long?,
    val compressedSize: Long?,
    val localHeaderOffset: Long?,
  )

  /**
   * Locates the ZIP64 trailer when the 32-bit end record says its own fields overflowed.
   *
   * Returns `null` for the ordinary case where they did not, so the caller reads the 32-bit fields.
   */
  private fun ByteArray.zip64Locator(
    endRecord: Int,
    tailOffset: Long,
    media: RandomAccessMedia,
  ): Zip64Trailer? {
    val overflowed =
      u16(endRecord + END_ENTRY_COUNT_OFFSET) == COUNT_SENTINEL ||
        u32(endRecord + END_DIRECTORY_SIZE_OFFSET) == SIZE_SENTINEL ||
        u32(endRecord + END_DIRECTORY_OFFSET_OFFSET) == SIZE_SENTINEL
    if (!overflowed) return null
    val locator = endRecord - ZIP64_LOCATOR_BYTES
    if (locator < 0 || u32(locator) != ZIP64_LOCATOR_SIGNATURE) {
      throw ZipDirectoryUnreadableException("ZIP end record declares ZIP64 fields but carries no ZIP64 locator")
    }
    val recordOffset = u64(locator + ZIP64_LOCATOR_RECORD_OFFSET)
    if (recordOffset < 0 || recordOffset + ZIP64_END_RECORD_BYTES > media.size) {
      throw ZipDirectoryUnreadableException("ZIP64 end record offset $recordOffset falls outside the archive")
    }
    val record =
      if (recordOffset >= tailOffset) {
        val start = (recordOffset - tailOffset).toInt()
        copyOfRange(start, start + ZIP64_END_RECORD_BYTES)
      } else {
        media.read(recordOffset, ZIP64_END_RECORD_BYTES)
      }
    if (record.size != ZIP64_END_RECORD_BYTES || record.u32(0) != ZIP64_END_RECORD_SIGNATURE) {
      throw ZipDirectoryUnreadableException("No ZIP64 end-of-central-directory record at $recordOffset")
    }
    return Zip64Trailer(
      entryCount = record.u64(ZIP64_ENTRY_COUNT_OFFSET),
      directorySize = record.u64(ZIP64_DIRECTORY_SIZE_OFFSET),
      directoryOffset = record.u64(ZIP64_DIRECTORY_OFFSET_OFFSET),
    )
  }

  /**
   * Scans backwards for the end record, accepting one only when its declared comment length reaches
   * exactly to the end of the archive.
   *
   * The signature is four ordinary bytes and can occur inside compressed data or inside the archive
   * comment, so position alone proves nothing; the comment-length agreement is the check that makes
   * a match the real trailer.
   */
  private fun ByteArray.findEndRecord(): Int? {
    for (candidate in size - END_RECORD_BYTES downTo 0) {
      if (u32(candidate) != END_RECORD_SIGNATURE) continue
      val commentLength = u16(candidate + END_COMMENT_LENGTH_OFFSET)
      if (candidate + END_RECORD_BYTES + commentLength == size) return candidate
    }
    return null
  }

  private data class Zip64Trailer(
    val entryCount: Long,
    val directorySize: Long,
    val directoryOffset: Long,
  )

  private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

  private fun ByteArray.u32(at: Int): Long =
    (this[at].toLong() and 0xFF) or
      ((this[at + 1].toLong() and 0xFF) shl 8) or
      ((this[at + 2].toLong() and 0xFF) shl 16) or
      ((this[at + 3].toLong() and 0xFF) shl 24)

  private fun ByteArray.u64(at: Int): Long {
    var value = 0L
    for (index in 7 downTo 0) {
      value = (value shl 8) or (this[at + index].toLong() and 0xFF)
    }
    return value
  }

  /**
   * How many trailing bytes [read] will ask for first: 65,535 bytes of archive comment plus the
   * 22-byte record, the furthest from the end a ZIP trailer can legally begin.
   *
   * Public because a [RandomAccessMedia] over a network transport wants to prefetch exactly this
   * much, so that reading the trailer and learning the item's length are one round trip and the
   * central directory of an ordinary comic archive is already in hand. Duplicating the number in the
   * transport instead would let the two drift into an extra request per book.
   */
  const val TRAILER_SEARCH_BYTES: Int = 65_557

  /** A directory past this is pathological; refusing lets the caller fall back to materializing. */
  private const val MAXIMUM_DIRECTORY_BYTES = 64L * 1024 * 1024

  /** Caps the allocation a declared entry count can request before any record is parsed. */
  private const val MAXIMUM_PREALLOCATED_ENTRIES = 10_000L

  private const val END_RECORD_SIGNATURE = 0x06054b50L
  private const val END_RECORD_BYTES = 22
  private const val END_ENTRY_COUNT_OFFSET = 10
  private const val END_DIRECTORY_SIZE_OFFSET = 12
  private const val END_DIRECTORY_OFFSET_OFFSET = 16
  private const val END_COMMENT_LENGTH_OFFSET = 20

  private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
  private const val ZIP64_LOCATOR_BYTES = 20
  private const val ZIP64_LOCATOR_RECORD_OFFSET = 8

  private const val ZIP64_END_RECORD_SIGNATURE = 0x06064b50L
  private const val ZIP64_END_RECORD_BYTES = 56
  private const val ZIP64_ENTRY_COUNT_OFFSET = 32
  private const val ZIP64_DIRECTORY_SIZE_OFFSET = 40
  private const val ZIP64_DIRECTORY_OFFSET_OFFSET = 48

  private const val DIRECTORY_RECORD_SIGNATURE = 0x02014b50L
  private const val DIRECTORY_RECORD_BYTES = 46
  private const val DIRECTORY_FLAGS_OFFSET = 8
  private const val DIRECTORY_METHOD_OFFSET = 10
  private const val DIRECTORY_LOCAL_OFFSET_OFFSET = 42
  private const val DIRECTORY_COMPRESSED_SIZE_OFFSET = 20
  private const val DIRECTORY_UNCOMPRESSED_SIZE_OFFSET = 24
  private const val DIRECTORY_NAME_LENGTH_OFFSET = 28
  private const val DIRECTORY_EXTRA_LENGTH_OFFSET = 30
  private const val DIRECTORY_COMMENT_LENGTH_OFFSET = 32

  private const val EXTRA_HEADER_BYTES = 4
  private const val ZIP64_EXTRA_ID = 0x0001

  private const val ENCRYPTED_FLAG = 0x0001
  private const val COUNT_SENTINEL = 0xFFFF
  private const val SIZE_SENTINEL = 0xFFFFFFFFL
}
