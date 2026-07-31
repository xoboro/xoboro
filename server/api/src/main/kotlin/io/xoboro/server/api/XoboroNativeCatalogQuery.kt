package io.xoboro.server.api

import io.ktor.server.application.ApplicationCall
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId

internal fun ApplicationCall.nativeSeriesQuery(): SeriesCatalogQuery =
  SeriesCatalogQuery(
    libraryIds = identifierValues("libraryId").mapTo(linkedSetOf(), ::LibraryId),
    fullTextSearch = optionalTrimmed("query"),
    deleted = trashedFilter(),
    oneshot = optionalBoolean("oneShot"),
    publishers = repeatedValues("publisher"),
    languages = repeatedValues("language"),
    genres = repeatedValues("genre"),
    tags = repeatedValues("tag"),
  )

internal fun ApplicationCall.nativeMediaItemQuery(
  requiredSeriesId: SeriesId? = null,
): BookCatalogQuery =
  BookCatalogQuery(
    libraryIds = identifierValues("libraryId").mapTo(linkedSetOf(), ::LibraryId),
    seriesId = requiredSeriesId ?: optionalIdentifier("seriesId")?.let(::SeriesId),
    fullTextSearch = optionalTrimmed("query"),
    deleted = trashedFilter(),
    onDeck = optionalBoolean("onDeck") ?: false,
    keepReading = optionalBoolean("keepReading") ?: false,
  )

/**
 * Resolves `trashed`, which selects between live and trashed entries.
 *
 * Both listings previously hardcoded live-only, which left the trash unreadable: reconciliation
 * soft-deletes what disappeared from storage and `empty-trash` then destroys it, so there was no way
 * to see what a scan had removed before agreeing to lose it. Restoring needs no endpoint - a scan
 * that finds the files again clears the flag itself - but deciding whether to wait for that or empty
 * the trash needs the list.
 *
 * The parameter is named for the state rather than the column: `deleted=true` would suggest the rows
 * are gone, and these are exactly the rows that are not gone yet.
 */
private fun ApplicationCall.trashedFilter(): Boolean = optionalBoolean("trashed") ?: false

/**
 * Page request for a named discovery feed: the caller's paging, the feed's ordering.
 *
 * A `sort` parameter is **rejected** rather than honoured. The whole point of a named feed is that its
 * ordering is part of its definition, so allowing an override would put back the disagreement the feed
 * exists to remove - and doing it silently would be worse, because the caller would believe they had
 * changed something.
 *
 * There is one builder per collection because the two resolve the same wire field to different
 * repository properties - `lastReadAt` is `readProgress.readDate` for a media item and `readDate` for a
 * series. Handing the wire name to the repository unresolved is what made every feed answer `500`.
 */
internal fun ApplicationCall.nativeSeriesFeedPageRequest(
  feed: XoboroNativeDiscoveryFeed,
): CatalogPageRequest = nativeFeedPageRequest(feed, String::toSeriesSort)

internal fun ApplicationCall.nativeMediaItemFeedPageRequest(
  feed: XoboroNativeDiscoveryFeed,
): CatalogPageRequest = nativeFeedPageRequest(feed, String::toMediaItemSort)

private fun ApplicationCall.nativeFeedPageRequest(
  feed: XoboroNativeDiscoveryFeed,
  sortMapper: (String) -> CatalogSort,
): CatalogPageRequest {
  if (request.queryParameters["sort"] != null) {
    throw XoboroInvalidQueryException(
      "sort is not accepted on a discovery feed; use the general listing to choose an order",
    )
  }
  // Routed through the same helper the general listings use, so page and size bounds have one
  // definition. The mapper passed on is never reached: a `sort` parameter was rejected above, and the
  // feed's own field is resolved here instead - through the caller-facing mapper, so a feed cannot name
  // a field the listing would refuse.
  return nativePageRequest(
    sortMapper = { error("a discovery feed does not accept a sort") },
    defaultSort = sortMapper(feed.sortField).copy(direction = feed.direction),
  )
}

/** Applies the feed's own filter, for the two feeds defined by more than an ordering. */
internal fun BookCatalogQuery.withFeedFilter(feed: XoboroNativeDiscoveryFeed): BookCatalogQuery =
  when (feed.filter) {
    XoboroNativeDiscoveryFeed.Filter.ON_DECK -> copy(onDeck = true)
    XoboroNativeDiscoveryFeed.Filter.KEEP_READING -> copy(keepReading = true)
    null -> this
  }

internal fun ApplicationCall.nativeSeriesPageRequest(): CatalogPageRequest =
  nativePageRequest(String::toSeriesSort, NativeSeriesSort.TITLE.toCatalogSort())

internal fun ApplicationCall.nativeMediaItemPageRequest(): CatalogPageRequest =
  nativeMediaItemPageRequest(NativeMediaItemSort.SERIES_TITLE)

internal fun ApplicationCall.nativeSeriesMediaItemPageRequest(): CatalogPageRequest =
  nativeMediaItemPageRequest(NativeMediaItemSort.NUMBER)

private fun ApplicationCall.nativeMediaItemPageRequest(
  defaultSort: NativeMediaItemSort,
): CatalogPageRequest =
  nativePageRequest(String::toMediaItemSort, defaultSort.toCatalogSort())

private fun ApplicationCall.nativePageRequest(
  sortMapper: (String) -> CatalogSort,
  defaultSort: CatalogSort,
): CatalogPageRequest {
  val page = optionalInteger("page") ?: 0
  val size = optionalInteger("size") ?: DEFAULT_PAGE_SIZE
  if (page < 0) throw XoboroInvalidQueryException("page must not be negative")
  if (size !in 1..MAXIMUM_PAGE_SIZE) {
    throw XoboroInvalidQueryException("size must be between 1 and $MAXIMUM_PAGE_SIZE")
  }
  val sorts =
    request.queryParameters
      .getAll("sort")
      .orEmpty()
      .map(sortMapper)
      .ifEmpty { listOf(defaultSort) }
  return CatalogPageRequest(page = page, size = size, sorts = sorts)
}

private fun NativeSeriesSort.toCatalogSort(): CatalogSort =
  CatalogSort(
    property =
      when (this) {
        NativeSeriesSort.TITLE -> "titleSort"
        NativeSeriesSort.CREATED_AT -> "created"
        NativeSeriesSort.UPDATED_AT -> "lastModified"
        NativeSeriesSort.SOURCE_MODIFIED_AT -> "fileLastModified"
        NativeSeriesSort.LAST_READ_AT -> "readDate"
        NativeSeriesSort.MEDIA_ITEM_COUNT -> "booksCount"
      },
  )

private fun NativeMediaItemSort.toCatalogSort(): CatalogSort =
  CatalogSort(
    property =
      when (this) {
        NativeMediaItemSort.TITLE -> "title"
        NativeMediaItemSort.SERIES_TITLE -> "seriesTitle"
        NativeMediaItemSort.NUMBER -> "numberSort"
        NativeMediaItemSort.CREATED_AT -> "created"
        NativeMediaItemSort.UPDATED_AT -> "lastModified"
        NativeMediaItemSort.SOURCE_MODIFIED_AT -> "fileLastModified"
        NativeMediaItemSort.FILE_SIZE -> "fileSize"
        NativeMediaItemSort.LAST_READ_AT -> "readProgress.readDate"
      },
  )

private fun <T : Enum<T>> String.toSort(
  values: Array<T>,
  mapper: (T) -> CatalogSort,
): CatalogSort {
  val parts = split(',')
  if (parts.size !in 1..2) throw XoboroInvalidQueryException("sort must be field[,direction]")
  val field =
    values.firstOrNull { it.wireName == parts[0].trim() }
      ?: throw XoboroInvalidQueryException("Unsupported sort field: ${parts[0].trim()}")
  val direction =
    when (parts.getOrNull(1)?.trim()?.lowercase()) {
      null, "", "asc" -> CatalogSortDirection.ASC
      "desc" -> CatalogSortDirection.DESC
      else -> throw XoboroInvalidQueryException("Sort direction must be asc or desc")
    }
  return mapper(field).copy(direction = direction)
}

private fun String.toSeriesSort(): CatalogSort =
  toSort(NativeSeriesSort.entries.toTypedArray(), NativeSeriesSort::toCatalogSort)

private fun String.toMediaItemSort(): CatalogSort =
  toSort(NativeMediaItemSort.entries.toTypedArray(), NativeMediaItemSort::toCatalogSort)

private fun ApplicationCall.optionalInteger(name: String): Int? =
  request.queryParameters[name]?.let {
    it.toIntOrNull() ?: throw XoboroInvalidQueryException("$name must be an integer")
  }

private fun ApplicationCall.optionalBoolean(name: String): Boolean? =
  request.queryParameters[name]?.let {
    it.toBooleanStrictOrNull() ?: throw XoboroInvalidQueryException("$name must be a boolean")
  }

private fun ApplicationCall.optionalTrimmed(name: String): String? =
  request.queryParameters[name]?.trim()?.takeIf(String::isNotEmpty)

private fun ApplicationCall.optionalIdentifier(name: String): String? =
  request.queryParameters[name]?.let {
    it.trim().takeIf(String::isNotEmpty)
      ?: throw XoboroInvalidQueryException("$name must not be blank")
  }

private fun ApplicationCall.identifierValues(name: String): Set<String> {
  val values = request.queryParameters.getAll(name).orEmpty()
  if (values.any { it.isBlank() }) {
    throw XoboroInvalidQueryException("$name must not be blank")
  }
  return values.mapTo(linkedSetOf(), String::trim)
}

private fun ApplicationCall.repeatedValues(name: String): Set<String> =
  request.queryParameters
    .getAll(name)
    .orEmpty()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toCollection(linkedSetOf())

private val Enum<*>.wireName: String
  get() =
    name
      .lowercase()
      .split('_')
      .let { parts -> parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) } }

private enum class NativeSeriesSort {
  TITLE,
  CREATED_AT,
  UPDATED_AT,
  SOURCE_MODIFIED_AT,
  LAST_READ_AT,
  MEDIA_ITEM_COUNT,
}

private enum class NativeMediaItemSort {
  TITLE,
  SERIES_TITLE,
  NUMBER,
  CREATED_AT,
  UPDATED_AT,
  SOURCE_MODIFIED_AT,
  FILE_SIZE,
  LAST_READ_AT,
}

class XoboroInvalidQueryException(
  message: String,
) : IllegalArgumentException(message)

internal const val DEFAULT_PAGE_SIZE = 20
internal const val MAXIMUM_PAGE_SIZE = 200
