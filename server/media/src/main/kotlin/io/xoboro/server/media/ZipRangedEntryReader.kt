package io.xoboro.server.media

import java.util.zip.Inflater

/**
 * Reads one ZIP entry's bytes through [RandomAccessMedia], without fetching the archive.
 *
 * This is the other half of not transferring a remote library. [ZipDirectoryMediaAnalyzer] removed
 * the download from *analysis*; a scan then still fetched every archive whole, because generating a
 * book's cover reads its first page and the only way to read a page was to materialize the file.
 * Measured against a real library, the WebDAV server's own log showed the pairing exactly: one
 * `206` of 65,557 bytes for the trailer, immediately followed by a `200` of the entire archive.
 *
 * Two range requests per entry: the local header first, because only it says where the entry's data
 * begins - the central directory's extra field may differ in length from the local one, and guessing
 * that is how a reader silently returns bytes from the middle of an image. Then the data itself.
 *
 * Two requests is the right trade for *one* entry and the wrong trade for all of them. Reading 150
 * entries this way measured 6.52 s against 520 ms to fetch the whole 9 MB archive; reading one costs
 * about 50 ms. A caller that wants every entry should materialize.
 */
object ZipRangedEntryReader {
  /**
   * @throws ZipDirectoryUnreadableException when the local header is missing, disagrees with the
   *   central directory, or the entry is stored in a way this cannot decode.
   */
  fun read(
    media: RandomAccessMedia,
    entry: ZipDirectoryEntry,
  ): ByteArray {
    require(!entry.isDirectory) { "A ZIP directory entry has no content to read" }
    if (entry.encrypted) {
      throw ZipDirectoryUnreadableException("ZIP entry is encrypted: ${entry.name}")
    }
    if (!entry.isStored && !entry.isDeflated) {
      throw ZipDirectoryUnreadableException(
        "ZIP entry uses unsupported compression method ${entry.compressionMethod}: ${entry.name}",
      )
    }
    if (entry.uncompressedSize > MAXIMUM_ENTRY_BYTES) {
      throw ZipDirectoryUnreadableException(
        "ZIP entry is larger than $MAXIMUM_ENTRY_BYTES bytes: ${entry.name}",
      )
    }
    val header = media.read(entry.localHeaderOffset, LOCAL_HEADER_BYTES)
    if (header.size != LOCAL_HEADER_BYTES || header.u32(0) != LOCAL_HEADER_SIGNATURE) {
      throw ZipDirectoryUnreadableException(
        "No ZIP local header at ${entry.localHeaderOffset} for ${entry.name}",
      )
    }
    val nameLength = header.u16(LOCAL_NAME_LENGTH_OFFSET)
    val extraLength = header.u16(LOCAL_EXTRA_LENGTH_OFFSET)
    val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_BYTES + nameLength + extraLength
    if (dataOffset + entry.compressedSize > media.size) {
      throw ZipDirectoryUnreadableException(
        "ZIP entry data at $dataOffset+${entry.compressedSize} falls outside a ${media.size} byte archive",
      )
    }
    if (entry.compressedSize == 0L) return ByteArray(0)
    val compressed = media.read(dataOffset, entry.compressedSize.toInt())
    if (compressed.size != entry.compressedSize.toInt()) {
      throw ZipDirectoryUnreadableException(
        "Short read of ZIP entry ${entry.name}: wanted ${entry.compressedSize} bytes, got ${compressed.size}",
      )
    }
    return if (entry.isStored) compressed else compressed.inflate(entry)
  }

  /** Raw inflate (`nowrap`), because a ZIP entry carries deflate data with no zlib header. */
  private fun ByteArray.inflate(entry: ZipDirectoryEntry): ByteArray {
    val inflater = Inflater(true)
    try {
      inflater.setInput(this)
      val output = ByteArray(entry.uncompressedSize.toInt())
      var written = 0
      while (written < output.size) {
        val produced = inflater.inflate(output, written, output.size - written)
        if (produced == 0) {
          if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
        }
        written += produced
      }
      if (written != output.size) {
        throw ZipDirectoryUnreadableException(
          "ZIP entry ${entry.name} inflated to $written bytes, not the declared ${entry.uncompressedSize}",
        )
      }
      return output
    } catch (failure: java.util.zip.DataFormatException) {
      throw ZipDirectoryUnreadableException("ZIP entry ${entry.name} is not valid deflate data: ${failure.message}")
    } finally {
      inflater.end()
    }
  }

  private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

  private fun ByteArray.u32(at: Int): Long =
    (this[at].toLong() and 0xFF) or
      ((this[at + 1].toLong() and 0xFF) shl 8) or
      ((this[at + 2].toLong() and 0xFF) shl 16) or
      ((this[at + 3].toLong() and 0xFF) shl 24)

  /**
   * A page is held whole in memory here, so the bound is what keeps a malformed or hostile archive
   * from turning one request into a large allocation. Comic pages are a few hundred kilobytes.
   */
  private const val MAXIMUM_ENTRY_BYTES = 256L * 1024 * 1024

  private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
  private const val LOCAL_HEADER_BYTES = 30
  private const val LOCAL_NAME_LENGTH_OFFSET = 26
  private const val LOCAL_EXTRA_LENGTH_OFFSET = 28
}
