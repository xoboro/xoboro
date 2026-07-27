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
    val result =
      when (book.mediaKind) {
        MediaKind.COMIC_ARCHIVE -> {
          val access =
            accessesBySourceId[library.root.sourceId]
              ?: throw UnknownSourceMediaAccessException(library.root.sourceId)
          access.materialize(library.root.itemId, book.sourceItemId).use { materialized ->
            zipAnalyzer.analyze(
              bookId = bookId,
              path = materialized.path,
              analyzeDimensions = library.settings.analyzeDimensions,
              createdAtMillis = createdAtMillis,
              updatedAtMillis = nowMillis,
            )
          }
        }
        MediaKind.PDF, MediaKind.EPUB ->
          BookMedia(
            bookId = bookId,
            status = MediaStatus.UNSUPPORTED,
            comment = "ERR_1001",
            createdAtMillis = createdAtMillis,
            updatedAtMillis = nowMillis,
          )
      }
    media.upsert(result)
    return result
  }
}
