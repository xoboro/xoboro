package io.xoboro.core.application

import io.xoboro.core.domain.AlternateTitle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SeriesMetadataRepository
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.WebLink

data class PatchField<T>(
  val present: Boolean = false,
  val value: T? = null,
)

data class ManualBookMetadataPatch(
  val title: String? = null,
  val titleLock: Boolean? = null,
  val summary: PatchField<String> = PatchField(),
  val summaryLock: Boolean? = null,
  val number: String? = null,
  val numberLock: Boolean? = null,
  val numberSort: Float? = null,
  val numberSortLock: Boolean? = null,
  val releaseDate: PatchField<String> = PatchField(),
  val releaseDateLock: Boolean? = null,
  val authors: PatchField<List<Author>> = PatchField(),
  val authorsLock: Boolean? = null,
  val tags: PatchField<Set<String>> = PatchField(),
  val tagsLock: Boolean? = null,
  val isbn: PatchField<String> = PatchField(),
  val isbnLock: Boolean? = null,
  val links: PatchField<List<WebLink>> = PatchField(),
  val linksLock: Boolean? = null,
)

data class ManualSeriesMetadataPatch(
  val status: SeriesStatus? = null,
  val statusLock: Boolean? = null,
  val title: String? = null,
  val titleLock: Boolean? = null,
  val titleSort: String? = null,
  val titleSortLock: Boolean? = null,
  val summary: String? = null,
  val summaryLock: Boolean? = null,
  val readingDirection: PatchField<ReadingDirection> = PatchField(),
  val readingDirectionLock: Boolean? = null,
  val publisher: String? = null,
  val publisherLock: Boolean? = null,
  val ageRating: PatchField<Int> = PatchField(),
  val ageRatingLock: Boolean? = null,
  val language: String? = null,
  val languageLock: Boolean? = null,
  val genres: PatchField<Set<String>> = PatchField(),
  val genresLock: Boolean? = null,
  val tags: PatchField<Set<String>> = PatchField(),
  val tagsLock: Boolean? = null,
  val totalBookCount: PatchField<Int> = PatchField(),
  val totalBookCountLock: Boolean? = null,
  val sharingLabels: PatchField<Set<String>> = PatchField(),
  val sharingLabelsLock: Boolean? = null,
  val links: PatchField<List<WebLink>> = PatchField(),
  val linksLock: Boolean? = null,
  val alternateTitles: PatchField<List<AlternateTitle>> = PatchField(),
  val alternateTitlesLock: Boolean? = null,
)

class MetadataEditingLifecycle(
  private val books: BookRepository,
  private val series: SeriesRepository,
  private val bookMetadata: BookMetadataRepository,
  private val seriesMetadata: SeriesMetadataRepository,
  private val currentTimeMillis: () -> Long,
  private val eventPublisher: CatalogMutationEventPublisher = CatalogMutationEventPublisher {},
) {
  fun patchBook(
    id: BookId,
    patch: ManualBookMetadataPatch,
  ): BookMetadata {
    val book = requireNotNull(books.findByIdOrNull(id)) { "Book not found" }
    require(book.deletedAtMillis == null) { "Book not found" }
    val existing = requireNotNull(bookMetadata.findByBookIdOrNull(id)) { "Book metadata not found" }
    val updated =
      existing.copy(
        title = patch.title ?: existing.title,
        titleLock = patch.titleLock ?: existing.titleLock,
        summary = patch.summary.resolve(existing.summary, ""),
        summaryLock = patch.summaryLock ?: existing.summaryLock,
        number = patch.number ?: existing.number,
        numberLock = patch.numberLock ?: existing.numberLock,
        numberSort = patch.numberSort ?: existing.numberSort,
        numberSortLock = patch.numberSortLock ?: existing.numberSortLock,
        releaseDate = patch.releaseDate.resolveNullable(existing.releaseDate),
        releaseDateLock = patch.releaseDateLock ?: existing.releaseDateLock,
        authors = patch.authors.resolve(existing.authors, emptyList()),
        authorsLock = patch.authorsLock ?: existing.authorsLock,
        tags = patch.tags.resolve(existing.tags, emptySet()),
        tagsLock = patch.tagsLock ?: existing.tagsLock,
        isbn =
          patch.isbn
            .resolve(existing.isbn, "")
            .filter(Char::isDigit),
        isbnLock = patch.isbnLock ?: existing.isbnLock,
        links = patch.links.resolve(existing.links, emptyList()),
        linksLock = patch.linksLock ?: existing.linksLock,
        updatedAtMillis = now(),
      )
    bookMetadata.upsert(updated)
    return requireNotNull(bookMetadata.findByBookIdOrNull(id)).also {
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

  fun patchBooks(patches: Map<BookId, ManualBookMetadataPatch>): List<BookMetadata> =
    patches.mapNotNull { (id, patch) ->
      runCatching { patchBook(id, patch) }.getOrNull()
    }

  fun patchSeries(
    id: SeriesId,
    patch: ManualSeriesMetadataPatch,
  ): SeriesMetadata {
    val item = requireNotNull(series.findByIdOrNull(id)) { "Series not found" }
    require(item.deletedAtMillis == null) { "Series not found" }
    val existing =
      requireNotNull(seriesMetadata.findBySeriesIdOrNull(id)) { "Series metadata not found" }
    val updated =
      existing.copy(
        status = patch.status ?: existing.status,
        statusLock = patch.statusLock ?: existing.statusLock,
        title = patch.title ?: existing.title,
        titleLock = patch.titleLock ?: existing.titleLock,
        titleSort = patch.titleSort ?: existing.titleSort,
        titleSortLock = patch.titleSortLock ?: existing.titleSortLock,
        summary = patch.summary ?: existing.summary,
        summaryLock = patch.summaryLock ?: existing.summaryLock,
        readingDirection = patch.readingDirection.resolveNullable(existing.readingDirection),
        readingDirectionLock =
          patch.readingDirectionLock ?: existing.readingDirectionLock,
        publisher = patch.publisher ?: existing.publisher,
        publisherLock = patch.publisherLock ?: existing.publisherLock,
        ageRating = patch.ageRating.resolveNullable(existing.ageRating),
        ageRatingLock = patch.ageRatingLock ?: existing.ageRatingLock,
        language = patch.language ?: existing.language,
        languageLock = patch.languageLock ?: existing.languageLock,
        genres = patch.genres.resolve(existing.genres, emptySet()),
        genresLock = patch.genresLock ?: existing.genresLock,
        tags = patch.tags.resolve(existing.tags, emptySet()),
        tagsLock = patch.tagsLock ?: existing.tagsLock,
        totalBookCount = patch.totalBookCount.resolveNullable(existing.totalBookCount),
        totalBookCountLock = patch.totalBookCountLock ?: existing.totalBookCountLock,
        sharingLabels = patch.sharingLabels.resolve(existing.sharingLabels, emptySet()),
        sharingLabelsLock = patch.sharingLabelsLock ?: existing.sharingLabelsLock,
        links = patch.links.resolve(existing.links, emptyList()),
        linksLock = patch.linksLock ?: existing.linksLock,
        alternateTitles = patch.alternateTitles.resolve(existing.alternateTitles, emptyList()),
        alternateTitlesLock =
          patch.alternateTitlesLock ?: existing.alternateTitlesLock,
        updatedAtMillis = now(),
      )
    seriesMetadata.upsert(updated)
    return requireNotNull(seriesMetadata.findBySeriesIdOrNull(id)).also {
      eventPublisher.publish(
        CatalogMutationEvent.Series(
          kind = CatalogMutationKind.UPDATED,
          seriesId = item.id,
          libraryId = item.libraryId,
        ),
      )
    }
  }

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Timestamp must not be negative" } }
}

private fun <T> PatchField<T>.resolve(
  existing: T,
  empty: T,
): T = if (present) value ?: empty else existing

private fun <T> PatchField<T>.resolveNullable(existing: T?): T? =
  if (present) value else existing
