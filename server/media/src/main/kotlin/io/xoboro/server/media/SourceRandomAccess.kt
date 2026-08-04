package io.xoboro.server.media

/**
 * Reads bounded byte ranges of a media item without materializing the whole item.
 *
 * [SourceMediaAccess.materialize] hands an analyzer a real local `Path`, which for a remote source
 * means downloading the entire archive first. Against a real 129 GB WebDAV library that download
 * *is* the scan: 18,211 archives averaging 7.26 MB, every byte crossing the link so that an
 * analyzer can read a page list living in the last few kilobytes of each file.
 *
 * Measured against that library, over the same link, for one 9,449,145-byte archive:
 *
 * | read                                   | time    |
 * |----------------------------------------|---------|
 * | whole file (what `materialize` does)   | 520 ms  |
 * | last 64 KiB, one range request         |  25 ms  |
 *
 * The 64 KiB suffix held the complete central directory for all 79 entries, so one request answered
 * the whole page list.
 *
 * Per-entry ranged reads are deliberately **not** what this enables. 150 sequential 4 KiB range
 * requests against the same server took 6.52 s - 43.5 ms each, essentially all round trip - versus
 * 520 ms to fetch the whole file. Anything needing bytes out of every entry (image dimensions, Tika
 * detection, page hashes, a whole-file hash) is cheaper to [SourceMediaAccess.materialize], and
 * callers are expected to choose that path instead rather than loop over ranges.
 */
interface RandomAccessMedia : AutoCloseable {
  /** The item's total length. A trailer-based format needs this to address bytes from the end. */
  val size: Long

  /**
   * Reads up to [length] bytes starting at [offset].
   *
   * Returns fewer bytes only when [offset] + [length] runs past [size]; a short read anywhere else
   * is a transport failure and throws rather than silently truncating, because a truncated central
   * directory parses as a shorter archive instead of as an error.
   */
  fun read(
    offset: Long,
    length: Int,
  ): ByteArray
}

/**
 * The optional half of the media SPI: a source that can serve ranges declares it here, and one that
 * cannot simply is not registered, leaving every caller on the [SourceMediaAccess] path it uses
 * today.
 */
interface SourceRandomAccess {
  val sourceId: String

  fun open(
    rootItemId: String,
    itemId: String,
  ): RandomAccessMedia
}
