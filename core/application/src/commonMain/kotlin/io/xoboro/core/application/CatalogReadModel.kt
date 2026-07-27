package io.xoboro.core.application

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata

data class CatalogAccess(
  val libraryIds: Set<LibraryId>? = null,
  val restrictions: ContentRestrictions = ContentRestrictions(),
)

enum class CatalogSortDirection {
  ASC,
  DESC,
}

data class CatalogSort(
  val property: String,
  val direction: CatalogSortDirection = CatalogSortDirection.ASC,
) {
  init {
    require(property.isNotBlank()) { "Catalog sort property must not be blank" }
  }
}

data class CatalogPageRequest(
  val page: Int = 0,
  val size: Int = 20,
  val sorts: List<CatalogSort> = emptyList(),
  val unpaged: Boolean = false,
) {
  init {
    require(page >= 0) { "Catalog page number must not be negative" }
    require(size in 1..MAXIMUM_PAGE_SIZE) {
      "Catalog page size must be between 1 and $MAXIMUM_PAGE_SIZE"
    }
  }

  companion object {
    const val MAXIMUM_PAGE_SIZE: Int = 10_000
  }
}

data class CatalogPage<T>(
  val content: List<T>,
  val page: Int,
  val size: Int,
  val totalElements: Long,
  val unpaged: Boolean = false,
) {
  init {
    require(page >= 0) { "Catalog page number must not be negative" }
    require(size > 0) { "Catalog page size must be positive" }
    require(totalElements >= 0) { "Catalog total must not be negative" }
  }
}

data class BookCatalogQuery(
  val libraryIds: Set<LibraryId> = emptySet(),
  val seriesId: SeriesId? = null,
  val fullTextSearch: String? = null,
  val deleted: Boolean? = false,
)

data class SeriesCatalogQuery(
  val libraryIds: Set<LibraryId> = emptySet(),
  val fullTextSearch: String? = null,
  val deleted: Boolean? = false,
  val oneshot: Boolean? = null,
  val publishers: Set<String> = emptySet(),
  val languages: Set<String> = emptySet(),
  val genres: Set<String> = emptySet(),
  val tags: Set<String> = emptySet(),
)

data class CatalogBook(
  val book: Book,
  val seriesTitle: String,
  val metadata: BookMetadata,
  val media: BookMedia?,
)

data class BookMetadataAggregation(
  val authors: List<io.xoboro.core.domain.Author> = emptyList(),
  val tags: Set<String> = emptySet(),
  val releaseDate: String? = null,
  val summary: String = "",
  val summaryNumber: String = "",
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
)

data class CatalogSeries(
  val series: Series,
  val metadata: SeriesMetadata,
  val booksMetadata: BookMetadataAggregation,
)

data class CatalogGroupCount(
  val group: String,
  val count: Int,
)

interface CatalogReadRepository {
  fun findBooks(
    query: BookCatalogQuery,
    access: CatalogAccess,
    page: CatalogPageRequest,
  ): CatalogPage<CatalogBook>

  fun findBookByIdOrNull(
    id: BookId,
    access: CatalogAccess,
  ): CatalogBook?

  fun findPreviousBookOrNull(
    id: BookId,
    access: CatalogAccess,
  ): CatalogBook?

  fun findNextBookOrNull(
    id: BookId,
    access: CatalogAccess,
  ): CatalogBook?

  fun findSeries(
    query: SeriesCatalogQuery,
    access: CatalogAccess,
    page: CatalogPageRequest,
  ): CatalogPage<CatalogSeries>

  fun findSeriesByIdOrNull(
    id: SeriesId,
    access: CatalogAccess,
  ): CatalogSeries?

  fun countSeriesByFirstCharacter(
    query: SeriesCatalogQuery,
    access: CatalogAccess,
  ): List<CatalogGroupCount>
}
