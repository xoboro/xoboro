package io.xoboro.server.perf

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Locale
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

/**
 * Generates a synthetic on-disk comic library made of minimal, valid CBZ archives, so the
 * performance harness exercises the real scanner and analyzer against genuine file I/O, zip
 * parsing, and image-dimension reading instead of hand-built fixtures.
 *
 * Synthetic only, per the project's test data policy: every series/book name and page image is
 * derived from a seeded counter. No real titles, media, covers, scraped metadata, private paths,
 * or credentials, and no Hangul anywhere in the generated tree.
 */
object SyntheticLibraryGenerator {
  /** Directory name whose presence marks its contents as one-shots, matching [oneshotsDirectory]. */
  const val ONE_SHOTS_DIRECTORY_NAME: String = "One-Shots"

  private const val MIN_PAGES_PER_BOOK = 2
  private const val MAX_PAGES_PER_BOOK = 6
  private const val PAGE_WIDTH_PX = 12
  private const val PAGE_HEIGHT_PX = 16

  /** Summary of a generated tree, returned so the caller can report exact item counts. */
  data class GeneratedLibrary(
    val root: Path,
    val seriesCount: Int,
    val booksPerSeries: Int,
    val oneShotCount: Int,
    /**
     * Every generated book's catalogue name - its file name without the extension, which is what
     * the scanner stores as the book name and what a media item reports as its title.
     *
     * Returned rather than left to the caller to re-derive. A test asserting that a catalogue holds
     * exactly the right items needs a list of what was written, and the writer is the only honest
     * source for it; a second copy of `"Series %04d #%03d"` inside a test would be one more place
     * for the two to drift apart while still agreeing with each other.
     */
    val bookNames: List<String>,
  ) {
    val totalBookCount: Int = seriesCount * booksPerSeries + oneShotCount
  }

  /**
   * Writes [seriesCount] series directories of [booksPerSeries] CBZ files each, plus
   * [oneShotCount] standalone one-shot CBZ files, under [root]. Deterministic for a fixed [seed]
   * so repeated runs are comparable.
   */
  fun generate(
    root: Path,
    seriesCount: Int,
    booksPerSeries: Int,
    oneShotCount: Int,
    seed: Long = DEFAULT_SEED,
  ): GeneratedLibrary {
    require(seriesCount >= 0) { "seriesCount must not be negative" }
    require(booksPerSeries >= 0) { "booksPerSeries must not be negative" }
    require(oneShotCount >= 0) { "oneShotCount must not be negative" }
    root.createDirectories()
    val random = Random(seed)
    val bookNames = ArrayList<String>(seriesCount * booksPerSeries + oneShotCount)

    repeat(seriesCount) { seriesIndex ->
      val seriesDirectory = root.resolve(seriesDirectoryName(seriesIndex)).createDirectories()
      repeat(booksPerSeries) { bookIndex ->
        val fileName = bookFileName(seriesIndex, bookIndex)
        writeSyntheticCbz(seriesDirectory.resolve(fileName), random)
        bookNames += fileName.withoutExtension()
      }
    }

    if (oneShotCount > 0) {
      val oneShotDirectory = root.resolve(ONE_SHOTS_DIRECTORY_NAME).createDirectories()
      repeat(oneShotCount) { oneShotIndex ->
        val fileName = oneShotFileName(oneShotIndex)
        writeSyntheticCbz(oneShotDirectory.resolve(fileName), random)
        bookNames += fileName.withoutExtension()
      }
    }

    return GeneratedLibrary(root, seriesCount, booksPerSeries, oneShotCount, bookNames)
  }

  /**
   * Writes one series directory named [seriesName] holding one CBZ per entry of [volumeNumbers],
   * numbered **without zero padding**, and returns the book names in the order [volumeNumbers] gives
   * them. Deterministic for a fixed [seed].
   *
   * The missing padding is the entire point, and it is why [generate]'s series cannot replace this.
   * `Series 0007 #003` sorts identically under a plain string comparison and under a natural one, so
   * a catalogue built from padded names cannot distinguish a scanner that orders volumes naturally
   * from one that orders them lexicographically. Unpadded names separate the two: `v9` precedes
   * `v10` naturally and follows it lexicographically. A caller should pass numbers that cross at
   * least two digit boundaries - 9/10 and 99/100 - because a set that crosses only one can be
   * satisfied by a comparator that merely pads to a fixed width.
   */
  fun generateUnpaddedVolumeSeries(
    root: Path,
    seriesName: String,
    volumeNumbers: List<Int>,
    seed: Long = DEFAULT_SEED,
  ): List<String> {
    require(seriesName.isNotBlank()) { "seriesName must not be blank" }
    require(volumeNumbers.isNotEmpty()) { "volumeNumbers must not be empty" }
    require(volumeNumbers.all { it >= 0 }) { "volumeNumbers must not be negative" }
    require(volumeNumbers.distinct().size == volumeNumbers.size) {
      "volumeNumbers must not repeat, or two books would share a file name"
    }
    val seriesDirectory = root.resolve(seriesName).createDirectories()
    val random = Random(seed)
    return volumeNumbers.map { number ->
      val fileName = unpaddedVolumeFileName(seriesName, number)
      writeSyntheticCbz(seriesDirectory.resolve(fileName), random)
      fileName.withoutExtension()
    }
  }

  private fun seriesDirectoryName(seriesIndex: Int): String =
    String.format(Locale.ROOT, "Series %04d", seriesIndex)

  private fun bookFileName(
    seriesIndex: Int,
    bookIndex: Int,
  ): String = String.format(Locale.ROOT, "Series %04d #%03d.cbz", seriesIndex, bookIndex)

  private fun oneShotFileName(oneShotIndex: Int): String =
    String.format(Locale.ROOT, "One-Shot %04d.cbz", oneShotIndex)

  private fun unpaddedVolumeFileName(
    seriesName: String,
    volumeNumber: Int,
  ): String = "$seriesName v$volumeNumber.cbz"

  private fun String.withoutExtension(): String = substringBeforeLast('.', missingDelimiterValue = this)

  private fun writeSyntheticCbz(
    path: Path,
    random: Random,
  ) {
    val pageCount = MIN_PAGES_PER_BOOK + random.nextInt(MAX_PAGES_PER_BOOK - MIN_PAGES_PER_BOOK + 1)
    ZipOutputStream(path.outputStream()).use { zip ->
      repeat(pageCount) { pageIndex ->
        zip.putNextEntry(ZipEntry(String.format(Locale.ROOT, "%03d.png", pageIndex + 1)))
        zip.write(syntheticPngBytes())
        zip.closeEntry()
      }
    }
  }

  private fun syntheticPngBytes(): ByteArray =
    ByteArrayOutputStream().use { output ->
      ImageIO.write(BufferedImage(PAGE_WIDTH_PX, PAGE_HEIGHT_PX, BufferedImage.TYPE_INT_RGB), "png", output)
      output.toByteArray()
    }

  private const val DEFAULT_SEED = 42L
}
