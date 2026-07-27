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
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.SeriesReadProgress
import io.xoboro.core.domain.UserId

data class CatalogAccess(
  val userId: UserId? = null,
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
  val sorts: List<CatalogSort> = emptyList(),
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
  val onDeck: Boolean = false,
  val keepReading: Boolean = false,
  val duplicatesOnly: Boolean = false,
  val condition: CatalogSearchCondition? = null,
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
  val condition: CatalogSearchCondition? = null,
)

enum class CatalogSearchField {
  LIBRARY_ID,
  COLLECTION_ID,
  READ_LIST_ID,
  SERIES_ID,
  DELETED,
  COMPLETE,
  ONE_SHOT,
  TITLE,
  TITLE_SORT,
  RELEASE_DATE,
  TAG,
  SHARING_LABEL,
  PUBLISHER,
  LANGUAGE,
  GENRE,
  AGE_RATING,
  READ_STATUS,
  SERIES_STATUS,
  AUTHOR,
  NUMBER_SORT,
  MEDIA_STATUS,
  MEDIA_PROFILE,
  POSTER,
}

enum class CatalogSearchOperator {
  IS,
  IS_NOT,
  CONTAINS,
  DOES_NOT_CONTAIN,
  BEGINS_WITH,
  DOES_NOT_BEGIN_WITH,
  ENDS_WITH,
  DOES_NOT_END_WITH,
  GREATER_THAN,
  LESS_THAN,
  BEFORE,
  AFTER,
  IS_IN_THE_LAST,
  IS_NOT_IN_THE_LAST,
  IS_NULL,
  IS_NOT_NULL,
  IS_TRUE,
  IS_FALSE,
}

sealed interface CatalogSearchCondition {
  data class AllOf(
    val conditions: List<CatalogSearchCondition>,
  ) : CatalogSearchCondition {
    init {
      require(conditions.isNotEmpty()) { "All-of search conditions must not be empty" }
    }
  }

  data class AnyOf(
    val conditions: List<CatalogSearchCondition>,
  ) : CatalogSearchCondition {
    init {
      require(conditions.isNotEmpty()) { "Any-of search conditions must not be empty" }
    }
  }

  data class Predicate(
    val field: CatalogSearchField,
    val operator: CatalogSearchOperator,
    val value: String? = null,
    val attributes: Map<String, String> = emptyMap(),
  ) : CatalogSearchCondition {
    init {
      require(attributes.keys.none(String::isBlank)) {
        "Search predicate attribute names must not be blank"
      }
    }
  }
}

data class CatalogBook(
  val book: Book,
  val seriesTitle: String,
  val seriesMetadata: SeriesMetadata,
  val metadata: BookMetadata,
  val media: BookMedia?,
  val readProgress: ReadProgress?,
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
  val readProgress: SeriesReadProgress?,
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
