package io.xoboro.server.media

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import io.xoboro.core.domain.BookId
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import java.nio.file.Files
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.math.ceil
import org.apache.pdfbox.Loader
import org.apache.pdfbox.multipdf.PageExtractor
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

class BookContentService(
  private val libraries: LibraryRepository,
  private val books: BookRepository,
  private val media: BookMediaRepository,
  accesses: Collection<SourceMediaAccess>,
  randomAccesses: Collection<SourceRandomAccess> = emptyList(),
) : BookContentAccess {
  private val accessesBySourceId = accesses.associateBy(SourceMediaAccess::sourceId)
  private val randomAccessesBySourceId = randomAccesses.associateBy(SourceRandomAccess::sourceId)

  init {
    require(accesses.none { it.sourceId.isBlank() }) { "Media source IDs must not be blank" }
    require(accessesBySourceId.size == accesses.size) { "Media source IDs must be unique" }
    require(randomAccesses.none { it.sourceId.isBlank() }) { "Random access source IDs must not be blank" }
    require(randomAccessesBySourceId.size == randomAccesses.size) { "Random access source IDs must be unique" }
  }

  override fun pages(bookId: BookId): List<BookPage>? {
    val book = books.findByIdOrNull(bookId) ?: return null
    if (book.deletedAtMillis != null) return null
    val analyzed = media.findByBookIdOrNull(book.id) ?: return null
    require(analyzed.status == MediaStatus.READY) {
      "Book media is not ready: ${analyzed.status}"
    }
    return if (book.mediaKind == MediaKind.PDF) {
      analyzed.pages.map { it.copy(mediaType = PageImageFormat.JPEG.mediaType) }
    } else {
      analyzed.pages
    }
  }

  override fun openPage(
    bookId: BookId,
    pageNumber: Int,
    request: PageImageRequest,
  ): OpenBookContent? {
    val book = books.findByIdOrNull(bookId) ?: return null
    if (book.deletedAtMillis != null) return null
    val analyzed = media.findByBookIdOrNull(book.id) ?: return null
    require(analyzed.status == MediaStatus.READY) {
      "Book media is not ready: ${analyzed.status}"
    }
    val page = analyzed.pages.getOrNull(pageNumber - 1) ?: return null
    val library = libraries.findById(book.libraryId)
    openArchivePageByRange(book, library, analyzed, page, request)?.let { return it }
    val access =
      accessesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceMediaAccessException(library.root.sourceId)
    val materialized = access.materialize(library.root.itemId, book.sourceItemId)
    return when (book.mediaKind) {
      MediaKind.COMIC_ARCHIVE ->
        if (analyzed.mediaType == RarMediaAnalyzer.RAR_MEDIA_TYPE) {
          openRarPage(materialized, page, request)
        } else {
          openArchivePage(materialized, page, request)
        }
      MediaKind.EPUB -> openArchivePage(materialized, page, request)
      MediaKind.PDF ->
        openPdfPage(materialized, pageNumber, request)
    }
  }

  override fun openResource(
    bookId: BookId,
    resource: String,
  ): OpenBookContent? {
    val book = books.findByIdOrNull(bookId) ?: return null
    if (book.deletedAtMillis != null || book.mediaKind != MediaKind.EPUB) return null
    val analyzed = media.findByBookIdOrNull(book.id) ?: return null
    require(analyzed.status == MediaStatus.READY) {
      "Book media is not ready: ${analyzed.status}"
    }
    val file = analyzed.files.firstOrNull { it.fileName == resource } ?: return null
    val library = libraries.findById(book.libraryId)
    val access =
      accessesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceMediaAccessException(library.root.sourceId)
    val materialized = access.materialize(library.root.itemId, book.sourceItemId)
    var materializationOwned = true
    try {
      val archive = ZipFile(materialized.path.toFile())
      var archiveOwned = true
      try {
        val entry =
          archive.getEntry(file.fileName)
            ?: throw IllegalStateException("Indexed EPUB resource is missing from the archive")
        require(!entry.isDirectory) { "EPUB resource must not be a directory" }
        val opened =
          OpenBookContent(
            input = archive.getInputStream(entry),
            fileName = file.fileName.substringAfterLast('/'),
            mediaType = file.mediaType ?: "application/octet-stream",
            contentLength = entry.size.takeIf { it >= 0 },
            closeResources = {
              try {
                archive.close()
              } finally {
                materialized.close()
              }
            },
          )
        archiveOwned = false
        materializationOwned = false
        return opened
      } catch (failure: Throwable) {
        if (archiveOwned) {
          runCatching(archive::close).exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
      }
    } catch (failure: Throwable) {
      if (materializationOwned) {
        runCatching(materialized::close).exceptionOrNull()?.let(failure::addSuppressed)
      }
      throw failure
    }
  }

  /**
   * Reads one page out of a ZIP comic archive by byte range, returning `null` when that is not
   * possible so the caller falls back to fetching the whole archive.
   *
   * This is what stops a scan of a remote library from transferring it. Analysis stopped needing the
   * archive once the trailer could be read directly, but cover generation still called through here
   * for page 1, and the WebDAV server's log showed the result plainly: a `206` of 65,557 bytes for
   * every book, immediately followed by a `200` of the whole archive.
   *
   * `null` for RAR, EPUB and PDF - those readers need a real file - and `null` when the archive's
   * trailer or the page's own header does not parse, which is how a `.cbz` that is really a RAR ends
   * up on the materializing path.
   */
  private fun openArchivePageByRange(
    book: io.xoboro.core.domain.Book,
    library: io.xoboro.core.domain.Library,
    analyzed: io.xoboro.core.domain.BookMedia,
    page: BookPage,
    request: PageImageRequest,
  ): OpenBookContent? {
    if (book.mediaKind != MediaKind.COMIC_ARCHIVE) return null
    if (analyzed.mediaType == RarMediaAnalyzer.RAR_MEDIA_TYPE) return null
    val randomAccess = randomAccessesBySourceId[library.root.sourceId] ?: return null
    val bytes =
      try {
        randomAccess.open(library.root.itemId, book.sourceItemId).use { opened ->
          val entry =
            ZipCentralDirectory
              .read(opened)
              .firstOrNull { it.name == page.fileName }
              ?: return null
          ZipRangedEntryReader.read(opened, entry)
        }
      } catch (_: ZipDirectoryUnreadableException) {
        return null
      } catch (_: java.io.IOException) {
        return null
      } catch (_: SecurityException) {
        return null
      }
    if (
      request.format == null &&
        request.maximumDimension == null &&
        request.maximumWidth == null
    ) {
      return OpenBookContent(
        input = ByteArrayInputStream(bytes),
        fileName = page.fileName.substringAfterLast('/'),
        mediaType = page.mediaType,
        contentLength = bytes.size.toLong(),
        closeResources = {},
      )
    }
    val converted = ByteArrayInputStream(bytes).use { input -> convertImage(input, request) }
    return OpenBookContent(
      input = ByteArrayInputStream(converted.bytes),
      fileName = converted.fileName(page.fileName),
      mediaType = converted.format.mediaType,
      contentLength = converted.bytes.size.toLong(),
      closeResources = {},
    )
  }

  private fun openArchivePage(
    materialized: MaterializedMedia,
    page: BookPage,
    request: PageImageRequest,
  ): OpenBookContent {
    var materializationOwned = true
    try {
      val archive = ZipFile(materialized.path.toFile())
      var archiveOwned = true
      try {
        val entry =
          archive.getEntry(page.fileName)
            ?: throw IllegalStateException("Indexed page is missing from the archive")
        require(!entry.isDirectory) { "Indexed page must not be a directory" }
        if (
          request.format != null ||
            request.maximumDimension != null ||
            request.maximumWidth != null
        ) {
          val converted =
            archive.getInputStream(entry).use { input ->
              convertImage(input, request)
            }
          archiveOwned = false
          try {
            archive.close()
          } finally {
            materializationOwned = false
            materialized.close()
          }
          return OpenBookContent(
            input = ByteArrayInputStream(converted.bytes),
            fileName = converted.fileName(page.fileName),
            mediaType = converted.format.mediaType,
            contentLength = converted.bytes.size.toLong(),
            closeResources = {},
          )
        }
        val opened =
          OpenBookContent(
            input = archive.getInputStream(entry),
            fileName = page.fileName.substringAfterLast('/'),
            mediaType = page.mediaType,
            contentLength = entry.size.takeIf { it >= 0 },
            closeResources = {
              try {
                archive.close()
              } finally {
                materialized.close()
              }
            },
          )
        archiveOwned = false
        materializationOwned = false
        return opened
      } catch (failure: Throwable) {
        if (archiveOwned) {
          runCatching(archive::close).exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
      }
    } catch (failure: Throwable) {
      if (materializationOwned) {
        runCatching(materialized::close).exceptionOrNull()?.let(failure::addSuppressed)
      }
      throw failure
    }
  }

  private fun openPdfPage(
    materialized: MaterializedMedia,
    pageNumber: Int,
    request: PageImageRequest,
  ): OpenBookContent {
    try {
      val bytes =
        Loader.loadPDF(materialized.path.toFile()).use { document ->
          require(pageNumber in 1..document.numberOfPages) { "PDF page does not exist" }
          if (request.raw) {
            ByteArrayOutputStream().use { output ->
              PageExtractor(document, pageNumber, pageNumber).extract().use { extracted ->
                extracted.save(output)
              }
              output.toByteArray()
            }
          } else {
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
            val maximumDimension = request.maximumDimension
            val maximumWidth = request.maximumWidth
            val requestedDpi =
              when {
                maximumDimension != null ->
                  maximumDimension *
                    PDF_POINTS_PER_INCH /
                    maxOf(widthPoints, heightPoints)
                maximumWidth != null ->
                  maximumWidth * PDF_POINTS_PER_INCH / widthPoints
                else -> null
              }
            val renderDpi =
              requestedDpi?.coerceIn(MINIMUM_PDF_RENDER_DPI, PDF_RENDER_DPI)
                ?: PDF_RENDER_DPI
            val widthPixels =
              ceil(widthPoints * renderDpi / PDF_POINTS_PER_INCH).toLong()
            val heightPixels =
              ceil(heightPoints * renderDpi / PDF_POINTS_PER_INCH).toLong()
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
                renderDpi,
                ImageType.RGB,
              )
            val png =
              ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output)) {
                  "No PNG image writer is available"
                }
                output.toByteArray()
              }
            convertImage(ByteArrayInputStream(png), request).bytes
          }
        }
      materialized.close()
      val mediaType =
        if (request.raw) PdfMediaAnalyzer.PDF_MEDIA_TYPE
        else (request.format ?: PageImageFormat.JPEG).mediaType
      val extension =
        if (request.raw) {
          ".pdf"
        } else if (request.format == PageImageFormat.PNG) {
          ".png"
        } else {
          ".jpg"
        }
      return OpenBookContent(
        input = ByteArrayInputStream(bytes),
        fileName = "page-$pageNumber$extension",
        mediaType = mediaType,
        contentLength = bytes.size.toLong(),
        closeResources = {},
      )
    } catch (failure: Throwable) {
      runCatching(materialized::close).exceptionOrNull()?.let(failure::addSuppressed)
      throw failure
    }
  }

  private fun openRarPage(
    materialized: MaterializedMedia,
    page: BookPage,
    request: PageImageRequest,
  ): OpenBookContent {
    var materializationOwned = true
    try {
      val archive =
        Archive(
          materialized.path.toFile(),
          ArchiveOptions
            .builder()
            .maxDictionarySize(RarMediaAnalyzer.DEFAULT_MAXIMUM_DICTIONARY_SIZE)
            .build(),
        )
      var archiveOwned = true
      try {
        val header =
          archive.fileHeaders.firstOrNull { it.fileName == page.fileName }
            ?: throw IllegalStateException("Indexed page is missing from the RAR archive")
        require(!header.isDirectory) { "Indexed page must not be a directory" }
        if (
          request.format != null ||
            request.maximumDimension != null ||
            request.maximumWidth != null
        ) {
          val converted =
            archive.getInputStream(header).use { input ->
              convertImage(input, request)
            }
          archiveOwned = false
          try {
            archive.close()
          } finally {
            materializationOwned = false
            materialized.close()
          }
          return OpenBookContent(
            input = ByteArrayInputStream(converted.bytes),
            fileName = converted.fileName(page.fileName),
            mediaType = converted.format.mediaType,
            contentLength = converted.bytes.size.toLong(),
            closeResources = {},
          )
        }
        val opened =
          OpenBookContent(
            input = archive.getInputStream(header),
            fileName = page.fileName.substringAfterLast('/').substringAfterLast('\\'),
            mediaType = page.mediaType,
            contentLength = header.fullUnpackSize.takeUnless { it < 0 },
            closeResources = {
              try {
                archive.close()
              } finally {
                materialized.close()
              }
            },
          )
        archiveOwned = false
        materializationOwned = false
        return opened
      } catch (failure: Throwable) {
        if (archiveOwned) {
          runCatching(archive::close).exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
      }
    } catch (failure: Throwable) {
      if (materializationOwned) {
        runCatching(materialized::close).exceptionOrNull()?.let(failure::addSuppressed)
      }
      throw failure
    }
  }

  override fun openBook(bookId: BookId): OpenBookContent? {
    val book = books.findByIdOrNull(bookId) ?: return null
    if (book.deletedAtMillis != null) return null
    val library = libraries.findById(book.libraryId)
    val access =
      accessesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceMediaAccessException(library.root.sourceId)
    val materialized = access.materialize(library.root.itemId, book.sourceItemId)
    try {
      val size = Files.size(materialized.path)
      val opened =
        OpenBookContent(
        input = Files.newInputStream(materialized.path),
        fileName = book.relativePath.substringAfterLast('/'),
        mediaType =
          media.findByBookIdOrNull(book.id)?.mediaType
            ?: "application/octet-stream",
        contentLength = size,
        closeResources = materialized::close,
      )
      return opened
    } catch (failure: Throwable) {
      runCatching(materialized::close).exceptionOrNull()?.let(failure::addSuppressed)
      throw failure
    }
  }

  private fun convertImage(
    input: InputStream,
    request: PageImageRequest,
  ): ConvertedImage {
    val maximumDimension = request.maximumDimension
    val maximumWidth = request.maximumWidth
    val source =
      requireNotNull(ImageIO.createImageInputStream(input)) {
        "Book page is not a supported image"
      }.use { imageInput ->
        val readers = ImageIO.getImageReaders(imageInput)
        require(readers.hasNext()) { "Book page is not a supported image" }
        val reader = readers.next()
        try {
          reader.setInput(imageInput, true, true)
          val width = reader.getWidth(0)
          val height = reader.getHeight(0)
          require(width.toLong() * height <= MAX_DECODED_PIXELS) {
            "Book page exceeds the decoded image safety limit"
          }
          val readParameters =
            reader.defaultReadParam.apply {
              val boundRatio =
                when {
                  maximumDimension != null -> maxOf(width, height).toDouble() / maximumDimension
                  maximumWidth != null -> width.toDouble() / maximumWidth
                  else -> null
                }
              boundRatio
                ?.let { ceil(it).toInt().coerceAtLeast(1) }
                ?.takeIf { it > 1 }
                ?.let { subsampling ->
                  setSourceSubsampling(subsampling, subsampling, 0, 0)
                }
            }
          reader.read(0, readParameters)
        } finally {
          reader.dispose()
        }
      }
    val resizeScale =
      when {
        maximumDimension != null &&
          (source.width > maximumDimension || source.height > maximumDimension) ->
          maximumDimension.toDouble() / maxOf(source.width, source.height)
        maximumWidth != null && source.width > maximumWidth ->
          maximumWidth.toDouble() / source.width
        else -> null
      }
    val target =
      resizeScale
        ?.let { scale ->
          val width = maxOf(1, (source.width * scale).toInt())
          val height = maxOf(1, (source.height * scale).toInt())
          BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { resized ->
            resized.createGraphics().use { graphics ->
              graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
              )
              graphics.drawImage(source, 0, 0, width, height, null)
            }
          }
        }
        ?: source
    val format = request.format ?: PageImageFormat.JPEG
    val encodable =
      if (format == PageImageFormat.JPEG && target.type != BufferedImage.TYPE_INT_RGB) {
        BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_RGB).also { flattened ->
          flattened.createGraphics().use { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, flattened.width, flattened.height)
            graphics.drawImage(target, 0, 0, null)
          }
        }
      } else {
        target
      }
    val bytes =
      ByteArrayOutputStream().use { output ->
        check(ImageIO.write(encodable, format.name.lowercase(), output)) {
          "No image writer is available for ${format.name}"
        }
        output.toByteArray()
      }
    return ConvertedImage(format, bytes)
  }
}

private data class ConvertedImage(
  val format: PageImageFormat,
  val bytes: ByteArray,
) {
  fun fileName(original: String): String {
    val leafName = original.substringAfterLast('/')
    return leafName.substringBeforeLast('.', leafName) +
      if (format == PageImageFormat.JPEG) ".jpg" else ".png"
  }
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R =
  try {
    block(this)
  } finally {
    dispose()
  }

class OpenBookContent internal constructor(
  val input: InputStream,
  override val fileName: String,
  override val mediaType: String,
  override val contentLength: Long?,
  private val closeResources: () -> Unit,
) : MediaContentStream {
  override fun read(
    buffer: ByteArray,
    offset: Int,
    length: Int,
  ): Int = input.read(buffer, offset, length)

  override fun skip(byteCount: Long): Long {
    input.skipNBytes(byteCount)
    return byteCount
  }

  override fun close() {
    try {
      input.close()
    } finally {
      closeResources()
    }
  }
}

private const val PDF_RENDER_DPI: Float = 150F
private const val MINIMUM_PDF_RENDER_DPI: Float = 12F
private const val PDF_POINTS_PER_INCH: Float = 72F

private const val MAX_DECODED_PIXELS = 100_000_000L
