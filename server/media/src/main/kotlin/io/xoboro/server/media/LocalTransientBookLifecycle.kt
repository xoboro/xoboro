package io.xoboro.server.media

import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.TransientBook
import io.xoboro.core.application.TransientBookLifecycle
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.math.ceil
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

class LocalTransientBookLifecycle(
  private val libraries: LibraryRepository,
  private val idFactory: () -> String,
  private val currentTimeMillis: () -> Long,
  private val zipAnalyzer: ZipMediaAnalyzer = ZipMediaAnalyzer(),
  private val epubAnalyzer: EpubMediaAnalyzer = EpubMediaAnalyzer(),
  private val pdfAnalyzer: PdfMediaAnalyzer = PdfMediaAnalyzer(),
) : TransientBookLifecycle {
  private val cacheLock = Any()
  private val books = LinkedHashMap<String, StoredTransientBook>(16, 0.75F, true)

  override fun scan(path: String): List<TransientBook> {
    val root = resolveReadableDirectory(path)
    rejectLibraryPath(root)
    val discovered =
      Files.walk(root, MAX_SCAN_DEPTH).use { paths ->
        paths
          .filter { candidate ->
            Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
              candidate.extension.lowercase() in SUPPORTED_EXTENSIONS
          }
          .sorted()
          .map { candidate -> store(candidate.toRealPath()) }
          .toList()
      }
    synchronized(cacheLock) {
      val now = now()
      prune(now)
      discovered.forEach { books[it.book.id] = it.copy(lastAccessMillis = now) }
      while (books.size > MAX_CACHED_BOOKS) {
        books.remove(books.entries.first().key)
      }
    }
    return discovered.map(StoredTransientBook::book)
  }

  override fun findByIdOrNull(id: String): TransientBook? =
    synchronized(cacheLock) {
      val now = now()
      prune(now)
      books[id]?.let { stored ->
        stored.copy(lastAccessMillis = now).also { books[id] = it }.book
      }
    }

  override fun analyze(id: String): TransientBook? {
    val stored =
      synchronized(cacheLock) {
        val now = now()
        prune(now)
        books[id]?.copy(lastAccessMillis = now)?.also { books[id] = it }
      } ?: return null
    val now = now()
    val bookId = BookId(stored.book.id)
    val media =
      when (stored.kind) {
        MediaKind.COMIC_ARCHIVE ->
          zipAnalyzer.analyze(
            bookId = bookId,
            path = stored.path,
            analyzeDimensions = true,
            createdAtMillis = now,
          )
        MediaKind.EPUB ->
          epubAnalyzer.analyze(
            bookId = bookId,
            path = stored.path,
            analyzeDimensions = true,
            createdAtMillis = now,
          )
        MediaKind.PDF ->
          pdfAnalyzer.analyze(
            bookId = bookId,
            path = stored.path,
            analyzeDimensions = true,
            createdAtMillis = now,
          )
      }
    val analyzed =
      stored.copy(
        book = stored.book.copy(media = media),
        lastAccessMillis = now,
      )
    synchronized(cacheLock) {
      books[id] = analyzed
    }
    return analyzed.book
  }

  override fun openPage(
    id: String,
    pageNumber: Int,
  ): MediaContentStream? {
    val stored =
      synchronized(cacheLock) {
        val now = now()
        prune(now)
        books[id]?.copy(lastAccessMillis = now)?.also { books[id] = it }
      } ?: return null
    val media = stored.book.media ?: return null
    val page = media.pages.getOrNull(pageNumber - 1) ?: return null
    return when (stored.kind) {
      MediaKind.COMIC_ARCHIVE, MediaKind.EPUB -> {
        val archive = ZipFile(stored.path.toFile())
        try {
          val entry = archive.getEntry(page.fileName)
          if (entry == null || entry.isDirectory) {
            archive.close()
            null
          } else {
            OpenBookContent(
              input = archive.getInputStream(entry),
              fileName = page.fileName.substringAfterLast('/'),
              mediaType = page.mediaType,
              contentLength = entry.size.takeIf { it >= 0 },
              closeResources = archive::close,
            )
          }
        } catch (failure: Throwable) {
          runCatching(archive::close).exceptionOrNull()?.let(failure::addSuppressed)
          throw failure
        }
      }
      MediaKind.PDF -> openPdfPage(stored.path, pageNumber)
    }
  }

  private fun store(path: Path): StoredTransientBook {
    val kind =
      when (path.extension.lowercase()) {
        "pdf" -> MediaKind.PDF
        "epub" -> MediaKind.EPUB
        else -> MediaKind.COMIC_ARCHIVE
      }
    val book =
      TransientBook(
        id = idFactory(),
        path = path.toString(),
        name = path.name,
        sizeBytes = Files.size(path),
        fileLastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
      )
    return StoredTransientBook(book, path, kind, 0)
  }

  private fun resolveReadableDirectory(rawPath: String): Path {
    val candidate =
      try {
        Path.of(rawPath).toAbsolutePath().normalize()
      } catch (_: RuntimeException) {
        throw IllegalArgumentException(ERROR_UNREADABLE_PATH)
      }
    if (
      !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isReadable(candidate)
    ) {
      throw IllegalArgumentException(ERROR_UNREADABLE_PATH)
    }
    return try {
      candidate.toRealPath()
    } catch (_: IOException) {
      throw IllegalArgumentException(ERROR_UNREADABLE_PATH)
    }
  }

  private fun rejectLibraryPath(candidate: Path) {
    val overlaps =
      libraries.findAll().any { library ->
        if (library.root.sourceId != LOCAL_SOURCE_ID) {
          false
        } else {
          runCatching {
            val root = Path.of(java.net.URI(library.root.itemId)).toRealPath()
            candidate.startsWith(root)
          }.getOrDefault(false)
        }
      }
    require(!overlaps) { ERROR_LIBRARY_PATH }
  }

  private fun openPdfPage(
    path: Path,
    pageNumber: Int,
  ): MediaContentStream {
    val bytes =
      Loader.loadPDF(path.toFile()).use { document ->
        require(pageNumber in 1..document.numberOfPages) { "PDF page does not exist" }
        val page = document.getPage(pageNumber - 1)
        val widthPoints = page.cropBox.width
        val heightPoints = page.cropBox.height
        require(
          widthPoints.isFinite() &&
            heightPoints.isFinite() &&
            widthPoints > 0 &&
            heightPoints > 0
        ) {
          "PDF page dimensions are invalid"
        }
        val widthPixels = ceil(widthPoints * PDF_RENDER_DPI / PDF_POINTS_PER_INCH).toLong()
        val heightPixels = ceil(heightPoints * PDF_RENDER_DPI / PDF_POINTS_PER_INCH).toLong()
        require(
          widthPixels > 0 &&
            heightPixels > 0 &&
            widthPixels <= MAX_DECODED_PIXELS / heightPixels
        ) {
          "PDF page exceeds the decoded image safety limit"
        }
        val image =
          PDFRenderer(document).renderImageWithDPI(
            pageNumber - 1,
            PDF_RENDER_DPI,
            ImageType.RGB,
          )
        ByteArrayOutputStream().use { output ->
          check(ImageIO.write(image, "jpeg", output)) { "No JPEG image writer is available" }
          output.toByteArray()
        }
      }
    return OpenBookContent(
      input = ByteArrayInputStream(bytes),
      fileName = "page-$pageNumber.jpg",
      mediaType = "image/jpeg",
      contentLength = bytes.size.toLong(),
      closeResources = {},
    )
  }

  private data class StoredTransientBook(
    val book: TransientBook,
    val path: Path,
    val kind: MediaKind,
    val lastAccessMillis: Long,
  )

  private fun now(): Long =
    currentTimeMillis().also {
      require(it >= 0) { "Transient cache timestamp must not be negative" }
    }

  private fun prune(nowMillis: Long) {
    val oldestAllowed = (nowMillis - CACHE_EXPIRY_MILLIS).coerceAtLeast(0)
    books.entries.removeIf { it.value.lastAccessMillis < oldestAllowed }
  }

  companion object {
    const val ERROR_UNREADABLE_PATH: String = "ERR_1016"
    const val ERROR_LIBRARY_PATH: String = "ERR_1017"
    private const val LOCAL_SOURCE_ID = "local"
    private const val MAX_SCAN_DEPTH = 32
    private const val MAX_CACHED_BOOKS = 1_000
    private const val CACHE_EXPIRY_MILLIS = 60 * 60 * 1_000L
    private const val PDF_RENDER_DPI = 150f
    private const val PDF_POINTS_PER_INCH = 72f
    private const val MAX_DECODED_PIXELS = 100_000_000L
    private val SUPPORTED_EXTENSIONS = setOf("cbz", "zip", "pdf", "epub")
  }
}
