package io.xoboro.core.application

import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SeriesMetadataRepository
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.WebLink

data class BookMetadataPatch(
  val title: String? = null,
  val summary: String? = null,
  val number: String? = null,
  val numberSort: Float? = null,
  val releaseDate: String? = null,
  val authors: List<Author>? = null,
  val tags: Set<String>? = null,
  val isbn: String? = null,
  val links: List<WebLink>? = null,
)

data class SeriesMetadataPatch(
  val status: SeriesStatus? = null,
  val title: String? = null,
  val titleSort: String? = null,
  val summary: String? = null,
  val readingDirection: ReadingDirection? = null,
  val publisher: String? = null,
  val ageRating: Int? = null,
  val language: String? = null,
  val genres: Set<String>? = null,
  val tags: Set<String>? = null,
  val totalBookCount: Int? = null,
  val links: List<WebLink>? = null,
)

fun interface BookMetadataProvider {
  fun provide(
    library: Library,
    book: Book,
  ): BookMetadataPatch?
}

fun interface SeriesMetadataProvider {
  fun provide(
    library: Library,
    series: Series,
    books: List<Book>,
  ): SeriesMetadataPatch?
}

interface SourceSidecarAccess {
  val sourceId: String

  fun readSeriesSidecar(
    rootItemId: String,
    seriesItemId: String,
    fileName: String,
    maximumBytes: Int,
  ): ByteArray?
}

class MetadataRefreshLifecycle(
  private val libraries: LibraryRepository,
  private val books: BookRepository,
  private val series: SeriesRepository,
  private val bookMetadata: BookMetadataRepository,
  private val seriesMetadata: SeriesMetadataRepository,
  private val bookProviders: List<BookMetadataProvider>,
  private val seriesProviders: List<SeriesMetadataProvider>,
  private val currentTimeMillis: () -> Long,
  private val eventPublisher: CatalogMutationEventPublisher = CatalogMutationEventPublisher {},
) {
  fun refreshBook(bookId: BookId): BookMetadata? {
    val book = books.findByIdOrNull(bookId) ?: return null
    val library = libraries.findById(book.libraryId)
    val existing =
      bookMetadata.findByBookIdOrNull(book.id)
        ?: BookMetadata(
          bookId = book.id,
          title = book.name,
          number = book.number.toString(),
          numberSort = book.number.toFloat(),
          createdAtMillis = book.createdAtMillis,
          updatedAtMillis = book.updatedAtMillis,
        )
    val updated =
      bookProviders.fold(existing) { metadata, provider ->
        provider.provide(library, book)?.let { patch -> metadata.applyPatch(patch) } ?: metadata
      }.copy(updatedAtMillis = now(existing.updatedAtMillis))
    bookMetadata.upsert(updated)
    return bookMetadata.findByBookIdOrNull(book.id)?.also {
      eventPublisher.publish(
        CatalogMutationEvent.Book(
          kind = CatalogMutationKind.UPDATED,
          bookId = book.id,
          seriesId = book.seriesId,
          libraryId = book.libraryId,
        ),
      )
    }
  }

  fun refreshSeries(seriesId: SeriesId): SeriesMetadata? {
    val item = series.findByIdOrNull(seriesId) ?: return null
    val library = libraries.findById(item.libraryId)
    val seriesBooks = books.findAllBySeriesId(item.id).filter { it.deletedAtMillis == null }
    val existing =
      seriesMetadata.findBySeriesIdOrNull(item.id)
        ?: SeriesMetadata(
          seriesId = item.id,
          title = item.name,
          createdAtMillis = item.createdAtMillis,
          updatedAtMillis = item.updatedAtMillis,
        )
    val updated =
      seriesProviders.fold(existing) { metadata, provider ->
        provider.provide(library, item, seriesBooks)?.let { patch ->
          metadata.applyPatch(patch)
        } ?: metadata
      }.copy(updatedAtMillis = now(existing.updatedAtMillis))
    seriesMetadata.upsert(updated)
    return seriesMetadata.findBySeriesIdOrNull(item.id)?.also {
      eventPublisher.publish(
        CatalogMutationEvent.Series(
          kind = CatalogMutationKind.UPDATED,
          seriesId = item.id,
          libraryId = item.libraryId,
        ),
      )
    }
  }

  private fun now(previousUpdatedAtMillis: Long): Long =
    currentTimeMillis().also {
      require(it >= previousUpdatedAtMillis) {
        "Metadata refresh timestamp must not precede the previous update"
      }
    }

  private fun BookMetadata.applyPatch(patch: BookMetadataPatch): BookMetadata =
    copy(
      title = if (titleLock) title else patch.title ?: title,
      summary = if (summaryLock) summary else patch.summary ?: summary,
      number = if (numberLock) number else patch.number ?: number,
      numberSort = if (numberSortLock) numberSort else patch.numberSort ?: numberSort,
      releaseDate = if (releaseDateLock) releaseDate else patch.releaseDate ?: releaseDate,
      authors = if (authorsLock) authors else patch.authors ?: authors,
      tags = if (tagsLock) tags else patch.tags ?: tags,
      isbn = if (isbnLock) isbn else patch.isbn ?: isbn,
      links = if (linksLock) links else patch.links ?: links,
    )

  private fun SeriesMetadata.applyPatch(patch: SeriesMetadataPatch): SeriesMetadata =
    copy(
      status = if (statusLock) status else patch.status ?: status,
      title = if (titleLock) title else patch.title ?: title,
      titleSort = if (titleSortLock) titleSort else patch.titleSort ?: titleSort,
      summary = if (summaryLock) summary else patch.summary ?: summary,
      readingDirection =
        if (readingDirectionLock) {
          readingDirection
        } else {
          patch.readingDirection ?: readingDirection
        },
      publisher = if (publisherLock) publisher else patch.publisher ?: publisher,
      ageRating = if (ageRatingLock) ageRating else patch.ageRating ?: ageRating,
      language = if (languageLock) language else patch.language ?: language,
      genres = if (genresLock) genres else patch.genres ?: genres,
      tags = if (tagsLock) tags else patch.tags ?: tags,
      totalBookCount =
        if (totalBookCountLock) {
          totalBookCount
        } else {
          patch.totalBookCount ?: totalBookCount
        },
      links = if (linksLock) links else patch.links ?: links,
    )
}
