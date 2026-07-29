package io.xoboro.server.api

import io.ktor.server.application.ApplicationCall
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.MetadataFacet
import io.xoboro.core.application.MetadataFacetQuery
import io.xoboro.core.domain.LibraryId

internal fun ApplicationCall.nativeMetadataFacetQuery(): MetadataFacetQuery =
  MetadataFacetQuery(
    libraryIds = metadataIdentifierValues("libraryId").mapTo(linkedSetOf(), ::LibraryId),
  )

internal fun ApplicationCall.requiredMetadataFacet(): MetadataFacet {
  val value =
    request.queryParameters["facet"]?.takeIf(String::isNotBlank)
      ?: throw XoboroInvalidQueryException("facet must not be blank")
  return when (value) {
    "genre" -> MetadataFacet.GENRE
    "seriesTag" -> MetadataFacet.SERIES_TAG
    "bookTag" -> MetadataFacet.BOOK_TAG
    "language" -> MetadataFacet.LANGUAGE
    "publisher" -> MetadataFacet.PUBLISHER
    "ageRating" -> MetadataFacet.AGE_RATING
    "sharingLabel" -> MetadataFacet.SHARING_LABEL
    "releaseYear" -> MetadataFacet.RELEASE_YEAR
    else -> throw XoboroInvalidQueryException("Unsupported facet: $value")
  }
}

internal fun ApplicationCall.nativeAuthorPageRequest(): CatalogPageRequest {
  val page = metadataOptionalInteger("page") ?: 0
  val size = metadataOptionalInteger("size") ?: DEFAULT_METADATA_PAGE_SIZE
  if (page < 0) throw XoboroInvalidQueryException("page must not be negative")
  if (size !in 1..XOBORO_METADATA_BULK_PATCH_LIMIT) {
    throw XoboroInvalidQueryException(
      "size must be between 1 and $XOBORO_METADATA_BULK_PATCH_LIMIT",
    )
  }
  return CatalogPageRequest(page = page, size = size, sorts = emptyList())
}

private fun ApplicationCall.metadataOptionalInteger(name: String): Int? =
  request.queryParameters[name]?.let {
    it.toIntOrNull() ?: throw XoboroInvalidQueryException("$name must be an integer")
  }

private fun ApplicationCall.metadataIdentifierValues(name: String): Set<String> {
  val values = request.queryParameters.getAll(name).orEmpty()
  if (values.any(String::isBlank)) {
    throw XoboroInvalidQueryException("$name must not be blank")
  }
  return values.mapTo(linkedSetOf(), String::trim)
}

internal const val XOBORO_METADATA_BULK_PATCH_LIMIT = 200
private const val DEFAULT_METADATA_PAGE_SIZE = 20
