package io.xoboro.server.media

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaKind
import java.io.IOException

class AnalyzeBook(
  private val books: BookRepository,
  private val libraries: LibraryRepository,
  accesses: Collection<SourceMediaAccess>,
  private val media: BookMediaRepository,
  private val zipAnalyzer: ZipMediaAnalyzer,
  private val rarAnalyzer: RarMediaAnalyzer = RarMediaAnalyzer(),
  private val archiveFormatDetector: ArchiveFormatDetector = ArchiveFormatDetector(),
  private val epubAnalyzer: EpubMediaAnalyzer = EpubMediaAnalyzer(),
  private val pdfAnalyzer: PdfMediaAnalyzer = PdfMediaAnalyzer(),
  private val contentHasher: Xxh3ContentHasher = Xxh3ContentHasher(),
  private val koreaderHasher: KoreaderPartialMd5Hasher = KoreaderPartialMd5Hasher(),
  randomAccesses: Collection<SourceRandomAccess> = emptyList(),
  private val zipDirectoryAnalyzer: ZipDirectoryMediaAnalyzer = ZipDirectoryMediaAnalyzer(),
  private val currentTimeMillis: () -> Long,
) {
  private val accessesBySourceId = accesses.associateBy(SourceMediaAccess::sourceId)
  private val randomAccessesBySourceId = randomAccesses.associateBy(SourceRandomAccess::sourceId)

  init {
    require(accesses.none { it.sourceId.isBlank() }) { "Media source IDs must not be blank" }
    require(accessesBySourceId.size == accesses.size) { "Media source IDs must be unique" }
    require(randomAccesses.none { it.sourceId.isBlank() }) { "Random access source IDs must not be blank" }
    require(randomAccessesBySourceId.size == randomAccesses.size) { "Random access source IDs must be unique" }
  }

  fun execute(bookId: BookId): BookMedia {
    val book = books.findByIdOrNull(bookId) ?: throw NoSuchElementException("Book not found: ${bookId.value}")
    val library = libraries.findById(book.libraryId)
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Analysis timestamp must not be negative" }
    val previous = media.findByBookIdOrNull(bookId)
    val createdAtMillis = previous?.createdAtMillis ?: nowMillis
    val result =
      analyzeFromDirectory(book, library, createdAtMillis, nowMillis)
        ?: analyzeByMaterializing(book, library, createdAtMillis, nowMillis)
    val restored = result.restorePageHashesFrom(previous)
    media.upsert(restored)
    return restored
  }

  /**
   * Analyzes a comic archive from its ZIP central directory, reading kilobytes instead of the file.
   *
   * Returns `null` when this cannot answer, and every reason is a reason the whole file has to be
   * read anyway:
   *
   * - the source cannot serve ranges (nothing registered for it),
   * - the book is not a comic archive,
   * - dimensions or page hashes were asked for, which need entry bytes,
   * - a whole-file hash is still missing, which needs every byte by definition,
   * - the trailer did not parse as a ZIP - a `.cbz` that is really RAR reaches this, and so does a
   *   truncated archive, and both deserve the full read's diagnosis rather than this one's.
   *
   * The gate is deliberately narrow. On a local library it changes nothing worth having; on a
   * remote one it is the difference between a scan that transfers the whole library and a scan that
   * transfers its trailers.
   */
  private fun analyzeFromDirectory(
    book: Book,
    library: Library,
    createdAtMillis: Long,
    nowMillis: Long,
  ): BookMedia? {
    if (book.mediaKind != MediaKind.COMIC_ARCHIVE) return null
    val settings = library.settings
    if (settings.analyzeDimensions || settings.hashPages) return null
    if (settings.hashFiles && book.fileHash.isBlank()) return null
    if (settings.hashKoreader && book.fileHashKoreader.isBlank()) return null
    val randomAccess = randomAccessesBySourceId[library.root.sourceId] ?: return null
    return try {
      randomAccess.open(library.root.itemId, book.sourceItemId).use { opened ->
        zipDirectoryAnalyzer.analyze(
          bookId = book.id,
          media = opened,
          createdAtMillis = createdAtMillis,
          updatedAtMillis = nowMillis,
        )
      }
    } catch (_: ZipDirectoryUnreadableException) {
      null
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

  private fun analyzeByMaterializing(
    book: Book,
    library: Library,
    createdAtMillis: Long,
    nowMillis: Long,
  ): BookMedia {
    val bookId = book.id
    val access =
      accessesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceMediaAccessException(library.root.sourceId)
    return access.materialize(library.root.itemId, book.sourceItemId).use { materialized ->
      val fileHash =
        if (library.settings.hashFiles && book.fileHash.isBlank()) {
          contentHasher.hash(materialized.path)
        } else {
          book.fileHash
        }
      val fileHashKoreader =
        if (library.settings.hashKoreader && book.fileHashKoreader.isBlank()) {
          koreaderHasher.hash(materialized.path)
        } else {
          book.fileHashKoreader
        }
      if (fileHash != book.fileHash || fileHashKoreader != book.fileHashKoreader) {
        books.update(
          book.copy(
            fileHash = fileHash,
            fileHashKoreader = fileHashKoreader,
          ),
        )
      }
      when (book.mediaKind) {
        MediaKind.COMIC_ARCHIVE ->
          when (archiveFormatDetector.detect(materialized.path)) {
            ArchiveFormat.RAR4, ArchiveFormat.RAR5 ->
              rarAnalyzer.analyze(
                bookId = bookId,
                path = materialized.path,
                analyzeDimensions = library.settings.analyzeDimensions,
                hashPages = library.settings.hashPages,
                createdAtMillis = createdAtMillis,
                updatedAtMillis = nowMillis,
              )
            ArchiveFormat.ZIP, null ->
              zipAnalyzer.analyze(
                bookId = bookId,
                path = materialized.path,
                analyzeDimensions = library.settings.analyzeDimensions,
                hashPages = library.settings.hashPages,
                createdAtMillis = createdAtMillis,
                updatedAtMillis = nowMillis,
              )
          }
        MediaKind.EPUB ->
          epubAnalyzer.analyze(
            bookId = bookId,
            path = materialized.path,
            analyzeDimensions = library.settings.analyzeDimensions,
            hashPages = library.settings.hashPages,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = nowMillis,
          )
        MediaKind.PDF ->
          pdfAnalyzer.analyze(
            bookId = bookId,
            path = materialized.path,
            analyzeDimensions = library.settings.analyzeDimensions,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = nowMillis,
          )
      }
    }
  }
}

private fun BookMedia.restorePageHashesFrom(previous: BookMedia?): BookMedia {
  if (previous == null || pages.none { it.fileHash.isBlank() }) return this
  val previousByIdentity =
    previous.pages
      .filter { it.fileHash.isNotBlank() }
      .associateBy { Triple(it.fileName, it.mediaType, it.fileSize) }
  return copy(
    pages =
      pages.map { page ->
        previousByIdentity[Triple(page.fileName, page.mediaType, page.fileSize)]
          ?.let { page.copy(fileHash = it.fileHash) }
          ?: page
      },
  )
}
