package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Dimension
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator
import org.apache.tika.Tika

class ZipMediaAnalyzer(
  private val tika: Tika = Tika(),
  private val hasher: Xxh3ContentHasher = Xxh3ContentHasher(),
  private val pageHashing: Int = DEFAULT_PAGE_HASHING,
) {
  init {
    require(pageHashing >= 0) { "Page hashing count must not be negative" }
  }

  private val naturalComparator: Comparator<String> =
    CaseInsensitiveSimpleNaturalComparator.getInstance()

  fun analyze(
    bookId: BookId,
    path: Path,
    analyzeDimensions: Boolean,
    hashPages: Boolean = false,
    createdAtMillis: Long,
    updatedAtMillis: Long = createdAtMillis,
  ): BookMedia =
    try {
      ZipFile(path.toFile()).use { archive ->
        val entries =
          archive.entries()
            .asSequence()
            .filterNot(ZipEntry::isDirectory)
            .sortedWith(compareBy(naturalComparator, ZipEntry::getName))
            .map { entry -> analyzeEntry(archive, entry, analyzeDimensions) }
            .toList()
        val indexedPages =
          entries
            .filter { it.mediaType?.startsWith(IMAGE_TYPE_PREFIX) == true }
            .mapIndexed { index, entry ->
              BookPage(
                number = index + 1,
                fileName = entry.name,
                mediaType = requireNotNull(entry.mediaType),
                fileSize = entry.fileSize,
                dimension = entry.dimension,
              )
            }
        val pages =
          if (hashPages && pageHashing > 0) {
            indexedPages.mapIndexed { index, page ->
              if (index < pageHashing || index >= indexedPages.size - pageHashing) {
                page.copy(
                  fileHash =
                    runCatching {
                      val entry = requireNotNull(archive.getEntry(page.fileName))
                      archive.getInputStream(entry).buffered().use { input ->
                        hasher.hashPage(input, page.mediaType)
                      }
                    }.getOrDefault(""),
                )
              } else {
                page
              }
            }
          } else {
            indexedPages
          }
        val files =
          entries
            .filterNot { it.mediaType?.startsWith(IMAGE_TYPE_PREFIX) == true }
            .map { entry ->
              MediaFile(
                fileName = entry.name,
                mediaType = entry.mediaType,
                fileSize = entry.fileSize,
              )
            }
        val unreadableNames = entries.filter { it.detectionFailed }.map(ArchiveEntry::name)
        when {
          pages.isEmpty() ->
            errorMedia(
              bookId = bookId,
              comment = ERROR_NO_PAGES,
              createdAtMillis = createdAtMillis,
              updatedAtMillis = updatedAtMillis,
              files = files,
            )
          else ->
            BookMedia(
              bookId = bookId,
              status = MediaStatus.READY,
              mediaType = ZIP_MEDIA_TYPE,
              profile = MediaProfile.DIVINA,
              pages = pages,
              files = files,
              comment =
                unreadableNames
                  .takeIf { it.isNotEmpty() }
                  ?.joinToString(prefix = "$ERROR_ENTRY [", postfix = "]"),
              createdAtMillis = createdAtMillis,
              updatedAtMillis = updatedAtMillis,
            )
        }
      }
    } catch (_: IOException) {
      errorMedia(bookId, ERROR_ARCHIVE, createdAtMillis, updatedAtMillis)
    } catch (_: SecurityException) {
      errorMedia(bookId, ERROR_ARCHIVE, createdAtMillis, updatedAtMillis)
    }

  private fun analyzeEntry(
    archive: ZipFile,
    entry: ZipEntry,
    analyzeDimensions: Boolean,
  ): ArchiveEntry =
    try {
      val mediaType =
        archive.getInputStream(entry).buffered().use { input ->
          tika.detect(input, entry.name)
        }
      ArchiveEntry(
        name = entry.name,
        mediaType = mediaType,
        fileSize = entry.size.takeUnless { it < 0 },
        dimension =
          runCatching {
            if (analyzeDimensions && mediaType.startsWith(IMAGE_TYPE_PREFIX)) {
              archive.getInputStream(entry).buffered().use(::readDimension)
            } else {
              null
            }
          }.getOrNull(),
      )
    } catch (_: Exception) {
      ArchiveEntry(
        name = entry.name,
        fileSize = entry.size.takeUnless { it < 0 },
        detectionFailed = true,
      )
    }

  private fun readDimension(input: java.io.InputStream): Dimension? =
    ImageIO.createImageInputStream(input)?.use { imageInput ->
      val readers = ImageIO.getImageReaders(imageInput)
      if (!readers.hasNext()) return@use null
      val reader = readers.next()
      try {
        reader.setInput(imageInput, true, true)
        Dimension(reader.getWidth(0), reader.getHeight(0))
      } finally {
        reader.dispose()
      }
    }

  private fun errorMedia(
    bookId: BookId,
    comment: String,
    createdAtMillis: Long,
    updatedAtMillis: Long,
    files: List<MediaFile> = emptyList(),
  ): BookMedia =
    BookMedia(
      bookId = bookId,
      status = MediaStatus.ERROR,
      mediaType = ZIP_MEDIA_TYPE,
      profile = MediaProfile.DIVINA,
      files = files,
      comment = comment,
      createdAtMillis = createdAtMillis,
      updatedAtMillis = updatedAtMillis,
    )

  private data class ArchiveEntry(
    val name: String,
    val mediaType: String? = null,
    val fileSize: Long? = null,
    val dimension: Dimension? = null,
    val detectionFailed: Boolean = false,
  )

  companion object {
    const val ZIP_MEDIA_TYPE: String = "application/zip"
    const val ERROR_ARCHIVE: String = "ERR_1008"
    const val ERROR_NO_PAGES: String = "ERR_1006"
    const val ERROR_ENTRY: String = "ERR_1007"
    private const val IMAGE_TYPE_PREFIX = "image/"
    const val DEFAULT_PAGE_HASHING: Int = 3
  }
}
