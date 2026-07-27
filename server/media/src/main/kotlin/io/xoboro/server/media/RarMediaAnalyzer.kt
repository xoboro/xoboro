package io.xoboro.server.media

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.exception.RarException
import com.github.junrar.rarfile.FileHeader
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Dimension
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator
import org.apache.tika.Tika

class RarMediaAnalyzer(
  private val tika: Tika = Tika(),
  private val hasher: Xxh3ContentHasher = Xxh3ContentHasher(),
  private val pageHashing: Int = ZipMediaAnalyzer.DEFAULT_PAGE_HASHING,
  private val maximumDictionarySize: Long = DEFAULT_MAXIMUM_DICTIONARY_SIZE,
) {
  init {
    require(pageHashing >= 0) { "Page hashing count must not be negative" }
    require(maximumDictionarySize > 0) { "RAR dictionary limit must be positive" }
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
      open(path).use { archive ->
        require(!archive.isPasswordProtected) { "Password-protected RAR archives are unsupported" }
        val entries =
          archive.fileHeaders
            .asSequence()
            .filterNot(FileHeader::isDirectory)
            .sortedWith(compareBy(naturalComparator, FileHeader::getFileName))
            .map { header -> analyzeEntry(archive, header, analyzeDimensions) }
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
                      val header =
                        requireNotNull(
                          archive.fileHeaders.firstOrNull { it.fileName == page.fileName },
                        )
                      archive.getInputStream(header).buffered().use { input ->
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
        val unreadableNames = entries.filter(ArchiveEntry::detectionFailed).map(ArchiveEntry::name)
        if (pages.isEmpty()) {
          errorMedia(
            bookId = bookId,
            comment = ZipMediaAnalyzer.ERROR_NO_PAGES,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            files = files,
          )
        } else {
          BookMedia(
            bookId = bookId,
            status = MediaStatus.READY,
            mediaType = RAR_MEDIA_TYPE,
            profile = MediaProfile.DIVINA,
            pages = pages,
            files = files,
            comment =
              unreadableNames
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString(prefix = "${ZipMediaAnalyzer.ERROR_ENTRY} [", postfix = "]"),
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
          )
        }
      }
    } catch (_: IOException) {
      errorMedia(bookId, ZipMediaAnalyzer.ERROR_ARCHIVE, createdAtMillis, updatedAtMillis)
    } catch (_: RarException) {
      errorMedia(bookId, ZipMediaAnalyzer.ERROR_ARCHIVE, createdAtMillis, updatedAtMillis)
    } catch (_: IllegalArgumentException) {
      errorMedia(bookId, ZipMediaAnalyzer.ERROR_ARCHIVE, createdAtMillis, updatedAtMillis)
    }

  private fun analyzeEntry(
    archive: Archive,
    header: FileHeader,
    analyzeDimensions: Boolean,
  ): ArchiveEntry =
    try {
      val mediaType =
        archive.getInputStream(header).buffered().use { input ->
          tika.detect(input, header.fileName)
        }
      ArchiveEntry(
        name = header.fileName,
        mediaType = mediaType,
        fileSize = header.fullUnpackSize.takeUnless { it < 0 },
        dimension =
          runCatching {
            if (analyzeDimensions && mediaType.startsWith(IMAGE_TYPE_PREFIX)) {
              archive.getInputStream(header).buffered().use(::readDimension)
            } else {
              null
            }
          }.getOrNull(),
      )
    } catch (_: Exception) {
      ArchiveEntry(
        name = header.fileName,
        fileSize = header.fullUnpackSize.takeUnless { it < 0 },
        detectionFailed = true,
      )
    }

  private fun readDimension(input: InputStream): Dimension? =
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

  private fun open(path: Path): Archive =
    Archive(
      path.toFile(),
      ArchiveOptions.builder().maxDictionarySize(maximumDictionarySize).build(),
    )

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
      mediaType = RAR_MEDIA_TYPE,
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
    const val RAR_MEDIA_TYPE: String = "application/vnd.rar"
    const val DEFAULT_MAXIMUM_DICTIONARY_SIZE: Long = 512L * 1_024 * 1_024
    private const val IMAGE_TYPE_PREFIX = "image/"
  }
}
