package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import java.io.IOException

/** Persists the smallest media index that lets a reader list and serve a comic's pages. */
class ReaderReadyBookIndexer(
  private val books: BookRepository,
  private val libraries: LibraryRepository,
  private val media: BookMediaRepository,
  randomAccesses: Collection<SourceRandomAccess>,
  private val fallback: (BookId) -> BookMedia,
  private val zipDirectoryAnalyzer: ZipDirectoryMediaAnalyzer = ZipDirectoryMediaAnalyzer(),
  private val currentTimeMillis: () -> Long,
) {
  private val randomAccessesBySourceId = randomAccesses.associateBy(SourceRandomAccess::sourceId)

  init {
    require(randomAccesses.none { it.sourceId.isBlank() }) {
      "Random access source IDs must not be blank"
    }
    require(randomAccessesBySourceId.size == randomAccesses.size) {
      "Random access source IDs must be unique"
    }
  }

  fun execute(bookId: BookId): BookMedia {
    val previous = media.findByBookIdOrNull(bookId)
    if (previous?.status == MediaStatus.READY) return previous

    val book = books.findByIdOrNull(bookId) ?: throw NoSuchElementException("Book not found: ${bookId.value}")
    if (book.mediaKind != MediaKind.COMIC_ARCHIVE) return fallback(bookId)
    val library = libraries.findById(book.libraryId)
    val randomAccess = randomAccessesBySourceId[library.root.sourceId] ?: return fallback(bookId)
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Analysis timestamp must not be negative" }
    val result =
      try {
        randomAccess.open(library.root.itemId, book.sourceItemId).use { opened ->
          zipDirectoryAnalyzer.analyze(
            bookId = bookId,
            media = opened,
            createdAtMillis = previous?.createdAtMillis ?: nowMillis,
            updatedAtMillis = nowMillis,
          )
        }
      } catch (_: ZipDirectoryUnreadableException) {
        return fallback(bookId)
      } catch (_: IOException) {
        return fallback(bookId)
      } catch (_: SecurityException) {
        return fallback(bookId)
      }
    media.upsert(result)
    return result
  }
}
