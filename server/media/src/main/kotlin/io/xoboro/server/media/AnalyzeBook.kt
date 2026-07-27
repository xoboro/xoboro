package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus

class AnalyzeBook(
  private val books: BookRepository,
  private val libraries: LibraryRepository,
  accesses: Collection<SourceMediaAccess>,
  private val media: BookMediaRepository,
  private val zipAnalyzer: ZipMediaAnalyzer,
  private val epubAnalyzer: EpubMediaAnalyzer = EpubMediaAnalyzer(),
  private val pdfAnalyzer: PdfMediaAnalyzer = PdfMediaAnalyzer(),
  private val contentHasher: Xxh3ContentHasher = Xxh3ContentHasher(),
  private val koreaderHasher: KoreaderPartialMd5Hasher = KoreaderPartialMd5Hasher(),
  private val currentTimeMillis: () -> Long,
) {
  private val accessesBySourceId = accesses.associateBy(SourceMediaAccess::sourceId)

  init {
    require(accesses.none { it.sourceId.isBlank() }) { "Media source IDs must not be blank" }
    require(accessesBySourceId.size == accesses.size) { "Media source IDs must be unique" }
  }

  fun execute(bookId: BookId): BookMedia {
    val book = books.findByIdOrNull(bookId) ?: throw NoSuchElementException("Book not found: ${bookId.value}")
    val library = libraries.findById(book.libraryId)
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Analysis timestamp must not be negative" }
    val previous = media.findByBookIdOrNull(bookId)
    val createdAtMillis = previous?.createdAtMillis ?: nowMillis
    val access =
      accessesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceMediaAccessException(library.root.sourceId)
    val result =
      access.materialize(library.root.itemId, book.sourceItemId).use { materialized ->
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
            zipAnalyzer.analyze(
              bookId = bookId,
              path = materialized.path,
              analyzeDimensions = library.settings.analyzeDimensions,
              hashPages = library.settings.hashPages,
              createdAtMillis = createdAtMillis,
              updatedAtMillis = nowMillis,
            )
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
    val restored = result.restorePageHashesFrom(previous)
    media.upsert(restored)
    return restored
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
