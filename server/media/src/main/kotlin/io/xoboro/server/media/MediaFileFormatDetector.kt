package io.xoboro.server.media

import io.xoboro.core.domain.MediaKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * A media file's real format, as opposed to whatever its name claims.
 *
 * [canonicalExtension] is the extension the file should carry, and [mediaKind] is how the catalog
 * would classify it. The two travel together because renaming a file across formats also changes its
 * kind, and the scan derives kind from extension alone - a repair that changed one without the other
 * would leave the book indexed as something it is not.
 */
enum class MediaFileFormat(
  val canonicalExtension: String,
  val mediaKind: MediaKind,
) {
  COMIC_ZIP("cbz", MediaKind.COMIC_ARCHIVE),
  COMIC_RAR("cbr", MediaKind.COMIC_ARCHIVE),
  EPUB("epub", MediaKind.EPUB),
  PDF("pdf", MediaKind.PDF),
}

/**
 * Identifies a media file from its content, across every format the catalog indexes.
 *
 * [ArchiveFormatDetector] answers a narrower question - which archive reader can open this - and
 * media analysis needs only that. Extension repair needs more: a PDF named `.cbz` and an EPUB named
 * `.cbz` both look like "not an archive I know" to the narrower detector, which is why repair used to
 * skip them.
 *
 * EPUB is the reason this cannot be a signature table. An EPUB *is* a ZIP, so telling one from a
 * comic archive means reading the `mimetype` entry inside. When the container cannot be opened at all
 * the answer is `null` rather than a guess: renaming a file whose format was not established is how a
 * repair damages a library.
 */
class MediaFileFormatDetector(
  private val archives: ArchiveFormatDetector = ArchiveFormatDetector(),
) {
  fun detect(path: Path): MediaFileFormat? {
    if (path.hasPdfSignature()) return MediaFileFormat.PDF
    return when (archives.detect(path)) {
      ArchiveFormat.RAR4, ArchiveFormat.RAR5 -> MediaFileFormat.COMIC_RAR
      ArchiveFormat.ZIP ->
        when (path.declaresEpubMimetype()) {
          true -> MediaFileFormat.EPUB
          false -> MediaFileFormat.COMIC_ZIP
          null -> null
        }
      null -> null
    }
  }

  private fun Path.hasPdfSignature(): Boolean =
    try {
      Files.newInputStream(this).use { input ->
        val signature = ByteArray(PDF_SIGNATURE.size)
        val read = input.readNBytes(signature, 0, signature.size)
        read == signature.size && signature.contentEquals(PDF_SIGNATURE)
      }
    } catch (_: IOException) {
      false
    }

  /** `null` when the container could not be read, so the caller can decline to act on a guess. */
  private fun Path.declaresEpubMimetype(): Boolean? =
    try {
      ZipFile(toFile()).use { archive ->
        val entry = archive.getEntry(MIMETYPE_PATH) ?: return false
        val declared =
          archive.getInputStream(entry).use { input ->
            input.readNBytes(MAXIMUM_MIMETYPE_BYTES).decodeToString().trim()
          }
        declared == EPUB_MEDIA_TYPE
      }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }

  private companion object {
    const val MIMETYPE_PATH = "mimetype"
    const val EPUB_MEDIA_TYPE = "application/epub+zip"
    const val MAXIMUM_MIMETYPE_BYTES = 128
    val PDF_SIGNATURE = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
  }
}
