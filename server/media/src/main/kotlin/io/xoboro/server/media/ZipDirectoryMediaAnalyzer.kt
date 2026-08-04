package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator

/**
 * Analyzes a comic archive from its ZIP central directory alone, never reading entry data.
 *
 * This is [ZipMediaAnalyzer]'s output shape produced from ~4 KB of trailer instead of the whole
 * archive: same natural ordering, same page numbering, same media type, same `NO_PAGES` and
 * `ENCRYPTED` diagnoses. What it cannot produce is anything derived from entry *bytes* - page
 * dimensions, content-sniffed media types, page hashes - because fetching those costs more than
 * fetching the archive (see [SourceRandomAccess]).
 *
 * Encryption is read straight off the central directory's general-purpose flag rather than inferred
 * from a failure to open, which is what [ZipMediaAnalyzer] has to do: the JDK refuses an encrypted
 * archive at open time and leaves nothing to inspect.
 */
class ZipDirectoryMediaAnalyzer {
  private val naturalComparator: Comparator<String> =
    CaseInsensitiveSimpleNaturalComparator.getInstance()

  fun analyze(
    bookId: BookId,
    media: RandomAccessMedia,
    createdAtMillis: Long,
    updatedAtMillis: Long = createdAtMillis,
  ): BookMedia {
    val entries =
      ZipCentralDirectory
        .read(media)
        .filterNot(ZipDirectoryEntry::isDirectory)
        .sortedWith(compareBy(naturalComparator, ZipDirectoryEntry::name))
    if (entries.any(ZipDirectoryEntry::encrypted)) {
      return BookMedia(
        bookId = bookId,
        status = MediaStatus.UNSUPPORTED,
        mediaType = ZipMediaAnalyzer.ZIP_MEDIA_TYPE,
        profile = MediaProfile.DIVINA,
        comment = MediaAnalysisComment.ENCRYPTED,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
      )
    }
    val typed = entries.map { it to MediaTypeByFileName.detect(it.name) }
    val pages =
      typed
        .filter { (_, mediaType) -> mediaType?.startsWith(IMAGE_TYPE_PREFIX) == true }
        .mapIndexed { index, (entry, mediaType) ->
          BookPage(
            number = index + 1,
            fileName = entry.name,
            mediaType = requireNotNull(mediaType),
            fileSize = entry.uncompressedSize,
          )
        }
    val files =
      typed
        .filterNot { (_, mediaType) -> mediaType?.startsWith(IMAGE_TYPE_PREFIX) == true }
        .map { (entry, mediaType) ->
          MediaFile(
            fileName = entry.name,
            mediaType = mediaType,
            fileSize = entry.uncompressedSize,
          )
        }
    if (pages.isEmpty()) {
      return BookMedia(
        bookId = bookId,
        status = MediaStatus.ERROR,
        mediaType = ZipMediaAnalyzer.ZIP_MEDIA_TYPE,
        profile = MediaProfile.DIVINA,
        files = files,
        comment = MediaAnalysisComment.NO_PAGES,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
      )
    }
    return BookMedia(
      bookId = bookId,
      status = MediaStatus.READY,
      mediaType = ZipMediaAnalyzer.ZIP_MEDIA_TYPE,
      profile = MediaProfile.DIVINA,
      pages = pages,
      files = files,
      createdAtMillis = createdAtMillis,
      updatedAtMillis = updatedAtMillis,
    )
  }

  private companion object {
    const val IMAGE_TYPE_PREFIX = "image/"
  }
}
