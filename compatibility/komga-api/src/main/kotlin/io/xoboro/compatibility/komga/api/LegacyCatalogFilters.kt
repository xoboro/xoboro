package io.xoboro.compatibility.komga.api

import io.ktor.server.application.ApplicationCall
import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import java.time.LocalDate

internal enum class LegacyBookFilter {
  AUTHOR,
  MEDIA_STATUS,
  READ_STATUS,
  RELEASED_AFTER,
  TAG,
}

internal enum class LegacySeriesFilter {
  AGE_RATING,
  AUTHOR,
  COLLECTION_ID,
  COMPLETE,
  GENRE,
  LANGUAGE,
  PUBLISHER,
  READ_STATUS,
  RELEASE_YEAR,
  SERIES_STATUS,
  SHARING_LABEL,
  TAG,
}

private val DEPRECATED_BOOK_FILTERS =
  setOf(
    LegacyBookFilter.MEDIA_STATUS,
    LegacyBookFilter.READ_STATUS,
    LegacyBookFilter.RELEASED_AFTER,
    LegacyBookFilter.TAG,
  )

private val DEPRECATED_SERIES_FILTERS = LegacySeriesFilter.entries.toSet()

internal fun ApplicationCall.legacyBookCondition(
  initial: List<CatalogSearchCondition> = emptyList(),
  filters: Set<LegacyBookFilter> = DEPRECATED_BOOK_FILTERS,
): CatalogSearchCondition? =
  buildList {
    addAll(initial)
    if (LegacyBookFilter.MEDIA_STATUS in filters) {
      addLegacyValues(
        request.queryParameters.getAll("media_status"),
        CatalogSearchField.MEDIA_STATUS,
      )
    }
    if (LegacyBookFilter.READ_STATUS in filters) {
      addLegacyValues(
        request.queryParameters.getAll("read_status"),
        CatalogSearchField.READ_STATUS,
      )
    }
    if (LegacyBookFilter.TAG in filters) {
      addLegacyValues(request.queryParameters.getAll("tag"), CatalogSearchField.TAG)
    }
    if (LegacyBookFilter.AUTHOR in filters) {
      addLegacyAuthors(request.queryParameters.getAll("author"))
    }
    if (LegacyBookFilter.RELEASED_AFTER in filters) {
      request.queryParameters["released_after"]?.let { rawDate ->
        val releaseDate = LocalDate.parse(rawDate)
        add(
          CatalogSearchCondition.Predicate(
            field = CatalogSearchField.RELEASE_DATE,
            operator = CatalogSearchOperator.AFTER,
            value = "${releaseDate}T00:00:00Z",
          ),
        )
      }
    }
  }.allOfOrNull()

internal fun ApplicationCall.legacySeriesCondition(
  initial: List<CatalogSearchCondition> = emptyList(),
  filters: Set<LegacySeriesFilter> = DEPRECATED_SERIES_FILTERS,
): CatalogSearchCondition? =
  buildList {
    addAll(initial)
    if (LegacySeriesFilter.COLLECTION_ID in filters) {
      addLegacyValues(
        request.queryParameters.getAll("collection_id"),
        CatalogSearchField.COLLECTION_ID,
      )
    }
    if (LegacySeriesFilter.SERIES_STATUS in filters) {
      addLegacyValues(
        request.queryParameters.getAll("status"),
        CatalogSearchField.SERIES_STATUS,
      )
    }
    if (LegacySeriesFilter.READ_STATUS in filters) {
      addLegacyValues(
        request.queryParameters.getAll("read_status"),
        CatalogSearchField.READ_STATUS,
      )
    }
    if (LegacySeriesFilter.SHARING_LABEL in filters) {
      addLegacyValues(
        request.queryParameters.getAll("sharing_label"),
        CatalogSearchField.SHARING_LABEL,
      )
    }
    if (LegacySeriesFilter.PUBLISHER in filters) {
      addLegacyValues(
        request.queryParameters.getAll("publisher"),
        CatalogSearchField.PUBLISHER,
      )
    }
    if (LegacySeriesFilter.LANGUAGE in filters) {
      addLegacyValues(
        request.queryParameters.getAll("language"),
        CatalogSearchField.LANGUAGE,
      )
    }
    if (LegacySeriesFilter.GENRE in filters) {
      addLegacyValues(request.queryParameters.getAll("genre"), CatalogSearchField.GENRE)
    }
    if (LegacySeriesFilter.TAG in filters) {
      addLegacyValues(request.queryParameters.getAll("tag"), CatalogSearchField.TAG)
    }
    if (LegacySeriesFilter.AGE_RATING in filters) {
      addLegacyAgeRatings(request.queryParameters.getAll("age_rating"))
    }
    if (LegacySeriesFilter.RELEASE_YEAR in filters) {
      addLegacyReleaseYears(request.queryParameters.getAll("release_year"))
    }
    if (LegacySeriesFilter.AUTHOR in filters) {
      addLegacyAuthors(request.queryParameters.getAll("author"))
    }
    if (LegacySeriesFilter.COMPLETE in filters) {
      request.queryParameters["complete"]?.toBooleanStrictOrNull()?.let {
        add(
          CatalogSearchCondition.Predicate(
            CatalogSearchField.COMPLETE,
            if (it) CatalogSearchOperator.IS_TRUE else CatalogSearchOperator.IS_FALSE,
          ),
        )
      }
    }
  }.allOfOrNull()

private fun MutableList<CatalogSearchCondition>.addLegacyAgeRatings(
  ratings: List<String>?,
) {
  ratings?.let {
    val predicates =
      it.map { value ->
        value.toIntOrNull()?.let {
          legacyPredicate(CatalogSearchField.AGE_RATING, it.toString())
        } ?: CatalogSearchCondition.Predicate(
          CatalogSearchField.AGE_RATING,
          CatalogSearchOperator.IS_NULL,
        )
      }
    if (predicates.isNotEmpty()) add(CatalogSearchCondition.AnyOf(predicates))
  }
}

private fun MutableList<CatalogSearchCondition>.addLegacyReleaseYears(
  years: List<String>?,
) {
  years?.let {
    val predicates =
      it.mapNotNull(String::toIntOrNull).map { year ->
        CatalogSearchCondition.AllOf(
          listOf(
            CatalogSearchCondition.Predicate(
              CatalogSearchField.RELEASE_DATE,
              CatalogSearchOperator.AFTER,
              "${year - 1}-12-31T12:00:00Z",
            ),
            CatalogSearchCondition.Predicate(
              CatalogSearchField.RELEASE_DATE,
              CatalogSearchOperator.BEFORE,
              "${year + 1}-01-01T12:00:00Z",
            ),
          ),
        )
      }
    if (predicates.isNotEmpty()) add(CatalogSearchCondition.AnyOf(predicates))
  }
}

private fun MutableList<CatalogSearchCondition>.addLegacyValues(
  values: List<String>?,
  field: CatalogSearchField,
) {
  values
    ?.map { legacyPredicate(field, it) }
    ?.takeIf(List<CatalogSearchCondition>::isNotEmpty)
    ?.let { add(CatalogSearchCondition.AnyOf(it)) }
}

private fun MutableList<CatalogSearchCondition>.addLegacyAuthors(authors: List<String>?) {
  authors
    ?.mapNotNull { raw ->
      raw.takeIf { ',' in it }?.let {
        CatalogSearchCondition.Predicate(
          field = CatalogSearchField.AUTHOR,
          operator = CatalogSearchOperator.IS,
          attributes =
            mapOf(
              "name" to it.substringBeforeLast(','),
              "role" to it.substringAfterLast(','),
            ),
        )
      }
    }?.takeIf(List<CatalogSearchCondition>::isNotEmpty)
    ?.let { add(CatalogSearchCondition.AnyOf(it)) }
}

private fun legacyPredicate(
  field: CatalogSearchField,
  value: String,
): CatalogSearchCondition.Predicate =
  CatalogSearchCondition.Predicate(field, CatalogSearchOperator.IS, value)

private fun List<CatalogSearchCondition>.allOfOrNull(): CatalogSearchCondition? =
  takeIf(List<CatalogSearchCondition>::isNotEmpty)
    ?.let(CatalogSearchCondition::AllOf)
