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

    repeat(seriesCount) { seriesIndex ->
      val seriesDirectory = root.resolve(seriesDirectoryName(seriesIndex)).createDirectories()
      repeat(booksPerSeries) { bookIndex ->
        val bookPath = seriesDirectory.resolve(bookFileName(seriesIndex, bookIndex))
        writeSyntheticCbz(bookPath, random)
      }
    }

    if (oneShotCount > 0) {
      val oneShotDirectory = root.resolve(ONE_SHOTS_DIRECTORY_NAME).createDirectories()
      repeat(oneShotCount) { oneShotIndex ->
        val bookPath = oneShotDirectory.resolve(oneShotFileName(oneShotIndex))
        writeSyntheticCbz(bookPath, random)
      }
    }

    return GeneratedLibrary(root, seriesCount, booksPerSeries, oneShotCount)
  }

  private fun seriesDirectoryName(seriesIndex: Int): String =
    String.format(Locale.ROOT, "Series %04d", seriesIndex)

  private fun bookFileName(
    seriesIndex: Int,
    bookIndex: Int,
  ): String = String.format(Locale.ROOT, "Series %04d #%03d.cbz", seriesIndex, bookIndex)

  private fun oneShotFileName(oneShotIndex: Int): String =
    String.format(Locale.ROOT, "One-Shot %04d.cbz", oneShotIndex)

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
