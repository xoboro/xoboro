package io.xoboro.core.application

import io.xoboro.core.domain.Author
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.SeriesId

enum class MetadataFacet {
  GENRE,
  SERIES_TAG,
  BOOK_TAG,
  LANGUAGE,
  PUBLISHER,
  AGE_RATING,
  SHARING_LABEL,
  RELEASE_YEAR,
}

data class MetadataFacetQuery(
  val libraryIds: Set<LibraryId> = emptySet(),
  val collectionId: CollectionId? = null,
  val seriesId: SeriesId? = null,
  val readListId: ReadListId? = null,
  val search: String? = null,
  val role: String? = null,
)

interface MetadataFacetRepository {
  fun findValues(
    facet: MetadataFacet,
    query: MetadataFacetQuery,
    access: CatalogAccess,
  ): List<String>

  fun findAuthors(
    query: MetadataFacetQuery,
    access: CatalogAccess,
    page: CatalogPageRequest,
  ): CatalogPage<Author>
}
