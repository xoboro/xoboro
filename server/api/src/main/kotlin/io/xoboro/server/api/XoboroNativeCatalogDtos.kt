package io.xoboro.server.api

import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.domain.AlternateTitle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.MediaItemType
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.User
import io.xoboro.core.domain.WebLink
import io.xoboro.core.domain.classifyForLibrary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class XoboroPageResponse<T>(
  val items: List<T>,
  val page: Int,
  val size: Int,
  val totalItems: Long,
  val totalPages: Int,
  val hasPrevious: Boolean,
  val hasNext: Boolean,
)

@Serializable
data class XoboroLibraryResponse(
  val id: String,
  val name: String,
  val unavailable: Boolean,
  /**
   * When the outage was first observed, or null while the library is available. [unavailable] on its
   * own cannot distinguish a mount that dropped a minute ago from one that has been gone for a week,
   * which is the difference between waiting and intervening.
   */
  val unavailableSinceMillis: Long? = null,
  val source: XoboroLibrarySourceResponse? = null,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
)

@Serializable
data class XoboroLibrarySourceResponse(
  val provider: String,
  val location: String,
)

@Serializable
data class XoboroSeriesResponse(
  val id: String,
  val libraryId: String,
  val title: String,
  val sortTitle: String,
  val summary: String,
  val status: String,
  val readingDirection: String? = null,
  val publisher: String,
  val ageRating: Int? = null,
  val language: String,
  /**
   * Who made the work, aggregated across the series' items.
   *
   * Authors are recorded per item because that is where a sidecar puts them, and a long-running
   * series can change hands. The aggregation is already computed for the read model, so a series
   * response that omitted it was dropping an answer it was holding: a reader had no way to see who
   * made a work without opening a chapter.
   */
  val authors: List<XoboroAuthorResponse>,
  val genres: Set<String>,
  val tags: Set<String>,
  val links: List<XoboroWebLinkResponse>,
  val alternateTitles: List<XoboroAlternateTitleResponse>,
  val mediaItemCount: Int,
  val expectedMediaItemCount: Int? = null,
  val oneShot: Boolean,
  val deleted: Boolean,
  val progress: XoboroSeriesProgressResponse? = null,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val sourceModifiedAtMillis: Long,
)

@Serializable
data class XoboroMediaItemResponse(
  val id: String,
  val libraryId: String,
  val seriesId: String,
  val type: String,
  val title: String,
  val seriesTitle: String,
  val summary: String,
  val number: String,
  val sortNumber: Float,
  val releaseDate: String? = null,
  val authors: List<XoboroAuthorResponse>,
  val tags: Set<String>,
  val isbn: String,
  val links: List<XoboroWebLinkResponse>,
  val media: XoboroMediaResponse,
  val progress: XoboroMediaProgressResponse? = null,
  val fileSize: Long,
  val oneShot: Boolean,
  val deleted: Boolean,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val sourceModifiedAtMillis: Long,
)

@Serializable
data class XoboroSeriesReaderContextResponse(
  val series: XoboroSeriesResponse,
  val first: XoboroMediaItemResponse? = null,
  val resume: XoboroMediaItemResponse? = null,
)

@Serializable
data class XoboroMediaResponse(
  val status: String,
  val mediaType: String? = null,
  val profile: String? = null,
  val pageCount: Int,
  val message: String? = null,
)

@Serializable
data class XoboroMediaProgressRequest(
  val page: Int,
  /**
   * An opaque Readium locator, stored as given, for a reader that has one.
   *
   * Optional because a comic has neither a spine nor an `href` to point at, so its reader sends a
   * page and nothing else. This field was required, which made every progress write from the comic
   * reader answer `400`; `ReadProgress.locatorJson` has always been `String?` with the invariant
   * "null or non-blank", so an absent locator is what the domain already models and only this type
   * refused to express it.
   *
   * `page` stays required. It is not an alternative to the locator but the position this surface
   * stores and orders by, and an EPUB reader sends both.
   */
  val locator: JsonObject? = null,
  val deviceId: String,
  val deviceName: String,
  val modifiedAtMillis: Long,
)

@Serializable
data class XoboroMediaProgressResponse(
  val page: Int,
  val completed: Boolean,
  val readAtMillis: Long,
  val updatedAtMillis: Long,
  val deviceId: String? = null,
  val deviceName: String? = null,
  val locator: JsonObject? = null,
)

@Serializable
data class XoboroSeriesProgressResponse(
  val completedMediaItems: Int,
  val inProgressMediaItems: Int,
  val lastReadAtMillis: Long,
  val updatedAtMillis: Long,
)

@Serializable
data class XoboroAuthorResponse(
  val name: String,
  val role: String,
)

@Serializable
data class XoboroWebLinkResponse(
  val label: String,
  val url: String,
)

@Serializable
data class XoboroAlternateTitleResponse(
  val label: String,
  val title: String,
)

internal fun Library.toNativeResponse(user: User): XoboroLibraryResponse =
  XoboroLibraryResponse(
    id = id.value,
    name = name,
    unavailable = unavailableAtMillis != null,
    unavailableSinceMillis = unavailableAtMillis,
    source =
      if (user.isAdmin) {
        XoboroLibrarySourceResponse(
          provider = root.sourceId,
          location = root.itemId,
        )
      } else {
        null
      },
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

internal fun CatalogSeries.toNativeResponse(): XoboroSeriesResponse =
  XoboroSeriesResponse(
    id = series.id.value,
    libraryId = series.libraryId.value,
    title = metadata.title,
    sortTitle = metadata.titleSort,
    summary = metadata.summary,
    status = metadata.status.name,
    readingDirection = metadata.readingDirection?.name,
    publisher = metadata.publisher,
    ageRating = metadata.ageRating,
    language = metadata.language,
    authors = booksMetadata.authors.map(Author::toNativeResponse),
    genres = metadata.genres,
    tags = metadata.tags,
    links = metadata.links.map(WebLink::toNativeResponse),
    alternateTitles = metadata.alternateTitles.map(AlternateTitle::toNativeResponse),
    mediaItemCount = series.bookCount,
    expectedMediaItemCount = metadata.totalBookCount,
    oneShot = series.oneshot,
    deleted = series.deletedAtMillis != null,
    progress =
      readProgress?.let {
        XoboroSeriesProgressResponse(
          completedMediaItems = it.booksReadCount,
          inProgressMediaItems = it.booksInProgressCount,
          lastReadAtMillis = it.lastReadAtMillis,
          updatedAtMillis = it.updatedAtMillis,
        )
      },
    createdAtMillis = series.createdAtMillis,
    updatedAtMillis = series.updatedAtMillis,
    sourceModifiedAtMillis = series.fileModifiedAtMillis,
  )

internal fun CatalogBook.toNativeResponse(): XoboroMediaItemResponse =
  XoboroMediaItemResponse(
    id = book.id.value,
    libraryId = book.libraryId.value,
    seriesId = book.seriesId.value,
    type = book.classifyForLibrary().type.toNativeValue(),
    title = metadata.title,
    seriesTitle = seriesTitle,
    summary = metadata.summary,
    number = metadata.number,
    sortNumber = metadata.numberSort,
    releaseDate = metadata.releaseDate,
    authors = metadata.authors.map(Author::toNativeResponse),
    tags = metadata.tags,
    isbn = metadata.isbn,
    links = metadata.links.map(WebLink::toNativeResponse),
    media =
      XoboroMediaResponse(
        status = media?.status?.name ?: "UNKNOWN",
        mediaType = media?.mediaType,
        profile = media?.profile?.name,
        pageCount = media?.pageCount ?: 0,
        message = media?.comment,
      ),
    progress =
      readProgress?.let {
        XoboroMediaProgressResponse(
          page = it.page,
          completed = it.completed,
          readAtMillis = it.readAtMillis,
          updatedAtMillis = it.updatedAtMillis,
        )
      },
    fileSize = book.fileSize,
    oneShot = book.oneshot,
    deleted = book.deletedAtMillis != null,
    createdAtMillis = book.createdAtMillis,
    updatedAtMillis = book.updatedAtMillis,
    sourceModifiedAtMillis = book.fileModifiedAtMillis,
  )

internal fun ReadProgress.toNativeProgressResponse(): XoboroMediaProgressResponse =
  XoboroMediaProgressResponse(
    page = page,
    completed = completed,
    readAtMillis = readAtMillis,
    updatedAtMillis = updatedAtMillis,
    deviceId = deviceId.takeIf(String::isNotEmpty),
    deviceName = deviceName.takeIf(String::isNotEmpty),
    locator = locatorJson?.let { Json.parseToJsonElement(it).jsonObject },
  )

internal fun CatalogPage<CatalogSeries>.toNativeSeriesPage(): XoboroPageResponse<XoboroSeriesResponse> =
  toNativePage(content.map(CatalogSeries::toNativeResponse))

internal fun CatalogPage<CatalogBook>.toNativeMediaItemPage():
  XoboroPageResponse<XoboroMediaItemResponse> =
  toNativePage(content.map(CatalogBook::toNativeResponse))

internal fun <T, R> CatalogPage<T>.toNativePage(items: List<R>): XoboroPageResponse<R> {
  val totalPages =
    if (totalElements == 0L) {
      0
    } else {
      ((totalElements - 1) / size + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
  return XoboroPageResponse(
    items = items,
    page = page,
    size = size,
    totalItems = totalElements,
    totalPages = totalPages,
    hasPrevious = page > 0,
    hasNext = page + 1 < totalPages,
  )
}

internal fun Author.toNativeResponse(): XoboroAuthorResponse =
  XoboroAuthorResponse(name = name, role = role)

internal fun WebLink.toNativeResponse(): XoboroWebLinkResponse =
  XoboroWebLinkResponse(label = label, url = url)

internal fun AlternateTitle.toNativeResponse(): XoboroAlternateTitleResponse =
  XoboroAlternateTitleResponse(label = label, title = title)

private fun MediaItemType.toNativeValue(): String = name
