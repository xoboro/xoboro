package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookMetadataAggregation
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogGroupCount
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.domain.AlternateTitle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.User
import io.xoboro.core.domain.WebLink
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun Route.komgaCatalogRoutes(catalog: CatalogReadRepository) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    route("/api/v1/books") {
      get {
        val principal = call.catalogPrincipal()
        val query =
          BookCatalogQuery(
            libraryIds = call.queryLibraryIds(),
            fullTextSearch = call.request.queryParameters["search"],
            deleted = false,
          )
        call.respond(
          catalog
            .findBooks(query, principal.user.catalogAccess(), call.catalogPageRequest())
            .toBookPageDto(principal.user),
        )
      }
      post("/list") {
        val principal = call.catalogPrincipal()
        val search = call.receive<JsonObject>()
        if (!call.requireSupportedSearch(search)) return@post
        call.respond(
          catalog
            .findBooks(
              query =
                BookCatalogQuery(
                  fullTextSearch = search.string("fullTextSearch"),
                  deleted = false,
                ),
              access = principal.user.catalogAccess(),
              page = call.catalogPageRequest(),
            ).toBookPageDto(principal.user),
        )
      }
      get("/latest") {
        val principal = call.catalogPrincipal()
        call.respond(
          catalog
            .findBooks(
              query = BookCatalogQuery(deleted = false),
              access = principal.user.catalogAccess(),
              page =
                call.catalogPageRequest(
                  defaultSort = listOf(CatalogSort("lastModified", CatalogSortDirection.DESC)),
                ),
            ).toBookPageDto(principal.user),
        )
      }
      get("/ondeck") {
        val principal = call.catalogPrincipal()
        call.respond(
          catalog
            .findBooks(
              query =
                BookCatalogQuery(
                  libraryIds = call.queryLibraryIds(),
                  deleted = false,
                  onDeck = true,
                ),
              access = principal.user.catalogAccess(),
              page =
                call.catalogPageRequest(
                  defaultSort = listOf(CatalogSort("lastModified", CatalogSortDirection.DESC)),
                ),
            ).toBookPageDto(principal.user),
        )
      }
      get("/duplicates") {
        val principal = call.catalogPrincipal()
        if (!principal.user.isAdmin) {
          call.respond(HttpStatusCode.Forbidden)
          return@get
        }
        call.respond(
          catalog
            .findBooks(
              query = BookCatalogQuery(deleted = false, duplicatesOnly = true),
              access = principal.user.catalogAccess(),
              page =
                call.catalogPageRequest(
                  defaultSort = listOf(CatalogSort("fileHash")),
                ),
            ).toBookPageDto(principal.user),
        )
      }
      get("/{bookId}") {
        val principal = call.catalogPrincipal()
        val item =
          catalog.findBookByIdOrNull(
            io.xoboro.core.domain.BookId(requireNotNull(call.parameters["bookId"])),
            principal.user.catalogAccess(),
          )
        if (item == null) {
          call.respond(HttpStatusCode.NotFound)
        } else {
          call.respond(item.toDto(principal.user))
        }
      }
      get("/{bookId}/previous") {
        val principal = call.catalogPrincipal()
        val item =
          catalog.findPreviousBookOrNull(
            io.xoboro.core.domain.BookId(requireNotNull(call.parameters["bookId"])),
            principal.user.catalogAccess(),
          )
        if (item == null) {
          call.respond(HttpStatusCode.NotFound)
        } else {
          call.respond(item.toDto(principal.user))
        }
      }
      get("/{bookId}/next") {
        val principal = call.catalogPrincipal()
        val item =
          catalog.findNextBookOrNull(
            io.xoboro.core.domain.BookId(requireNotNull(call.parameters["bookId"])),
            principal.user.catalogAccess(),
          )
        if (item == null) {
          call.respond(HttpStatusCode.NotFound)
        } else {
          call.respond(item.toDto(principal.user))
        }
      }
    }
    route("/api/v1/series") {
      get {
        val principal = call.catalogPrincipal()
        call.respond(
          catalog
            .findSeries(
              query = call.deprecatedSeriesQuery(),
              access = principal.user.catalogAccess(),
              page = call.catalogPageRequest(),
            ).toSeriesPageDto(principal.user),
        )
      }
      post("/list") {
        val principal = call.catalogPrincipal()
        val search = call.receive<JsonObject>()
        if (!call.requireSupportedSearch(search)) return@post
        call.respond(
          catalog
            .findSeries(
              query =
                SeriesCatalogQuery(
                  fullTextSearch = search.string("fullTextSearch"),
                  deleted = false,
                ),
              access = principal.user.catalogAccess(),
              page = call.catalogPageRequest(),
            ).toSeriesPageDto(principal.user),
        )
      }
      get("/alphabetical-groups") {
        val principal = call.catalogPrincipal()
        call.respond(
          catalog
            .countSeriesByFirstCharacter(
              call.deprecatedSeriesQuery(),
              principal.user.catalogAccess(),
            ).map(CatalogGroupCount::toDto),
        )
      }
      post("/list/alphabetical-groups") {
        val principal = call.catalogPrincipal()
        val search = call.receive<JsonObject>()
        if (!call.requireSupportedSearch(search)) return@post
        call.respond(
          catalog
            .countSeriesByFirstCharacter(
              SeriesCatalogQuery(
                fullTextSearch = search.string("fullTextSearch"),
                deleted = false,
              ),
              principal.user.catalogAccess(),
            ).map(CatalogGroupCount::toDto),
        )
      }
      get("/latest") {
        val principal = call.catalogPrincipal()
        call.respond(
          catalog
            .findSeries(
              query =
                SeriesCatalogQuery(
                  libraryIds = call.queryLibraryIds(),
                  deleted = call.queryBoolean("deleted"),
                  oneshot = call.queryBoolean("oneshot"),
                ),
              access = principal.user.catalogAccess(),
              page =
                call.catalogPageRequest(
                  defaultSort = listOf(CatalogSort("lastModified", CatalogSortDirection.DESC)),
                ),
            ).toSeriesPageDto(principal.user),
        )
      }
      get("/new") {
        val principal = call.catalogPrincipal()
        call.respond(
          catalog
            .findSeries(
              query =
                SeriesCatalogQuery(
                  libraryIds = call.queryLibraryIds(),
                  deleted = call.queryBoolean("deleted"),
                  oneshot = call.queryBoolean("oneshot"),
                ),
              access = principal.user.catalogAccess(),
              page =
                call.catalogPageRequest(
                  defaultSort = listOf(CatalogSort("created", CatalogSortDirection.DESC)),
                ),
            ).toSeriesPageDto(principal.user),
        )
      }
      get("/updated") {
        val principal = call.catalogPrincipal()
        call.respond(
          catalog
            .findSeries(
              query =
                SeriesCatalogQuery(
                  libraryIds = call.queryLibraryIds(),
                  deleted = call.queryBoolean("deleted"),
                  oneshot = call.queryBoolean("oneshot"),
                ),
              access = principal.user.catalogAccess(),
              page =
                call.catalogPageRequest(
                  defaultSort =
                    listOf(CatalogSort("fileLastModified", CatalogSortDirection.DESC)),
                ),
            ).toSeriesPageDto(principal.user),
        )
      }
      get("/{seriesId}") {
        val principal = call.catalogPrincipal()
        val item =
          catalog.findSeriesByIdOrNull(
            io.xoboro.core.domain.SeriesId(requireNotNull(call.parameters["seriesId"])),
            principal.user.catalogAccess(),
          )
        if (item == null) {
          call.respond(HttpStatusCode.NotFound)
        } else {
          call.respond(item.toDto(principal.user))
        }
      }
      get("/{seriesId}/books") {
        val principal = call.catalogPrincipal()
        val seriesId =
          io.xoboro.core.domain.SeriesId(requireNotNull(call.parameters["seriesId"]))
        if (catalog.findSeriesByIdOrNull(seriesId, principal.user.catalogAccess()) == null) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        call.respond(
          catalog
            .findBooks(
              query = BookCatalogQuery(seriesId = seriesId, deleted = false),
              access = principal.user.catalogAccess(),
              page =
                call.catalogPageRequest(
                  defaultSort = listOf(CatalogSort("numberSort")),
                ),
            ).toBookPageDto(principal.user),
        )
      }
    }
  }
}

@Serializable
data class KomgaBookDto(
  val id: String,
  val seriesId: String,
  val seriesTitle: String,
  val libraryId: String,
  val name: String,
  val url: String,
  val number: Int,
  val created: String,
  val lastModified: String,
  val fileLastModified: String,
  val sizeBytes: Long,
  val size: String,
  val media: KomgaMediaDto,
  val metadata: KomgaBookMetadataDto,
  val readProgress: KomgaReadProgressDto? = null,
  val deleted: Boolean,
  val fileHash: String,
  val oneshot: Boolean,
)

@Serializable
data class KomgaMediaDto(
  val status: String,
  val mediaType: String,
  val mediaProfile: String,
  val pagesCount: Int,
  val comment: String,
  val epubDivinaCompatible: Boolean,
  val epubIsKepub: Boolean,
)

@Serializable
data class KomgaBookMetadataDto(
  val title: String,
  val titleLock: Boolean,
  val summary: String,
  val summaryLock: Boolean,
  val number: String,
  val numberLock: Boolean,
  val numberSort: Float,
  val numberSortLock: Boolean,
  val releaseDate: String? = null,
  val releaseDateLock: Boolean,
  val authors: List<KomgaAuthorDto>,
  val authorsLock: Boolean,
  val tags: Set<String>,
  val tagsLock: Boolean,
  val isbn: String,
  val isbnLock: Boolean,
  val links: List<KomgaWebLinkDto>,
  val linksLock: Boolean,
  val created: String,
  val lastModified: String,
)

@Serializable
data class KomgaReadProgressDto(
  val page: Int,
  val completed: Boolean,
  val readDate: String,
  val created: String,
  val lastModified: String,
  val deviceId: String,
  val deviceName: String,
)

@Serializable
data class KomgaSeriesDto(
  val id: String,
  val libraryId: String,
  val name: String,
  val url: String,
  val created: String,
  val lastModified: String,
  val fileLastModified: String,
  val booksCount: Int,
  val booksReadCount: Int,
  val booksUnreadCount: Int,
  val booksInProgressCount: Int,
  val metadata: KomgaSeriesMetadataDto,
  val booksMetadata: KomgaBookMetadataAggregationDto,
  val deleted: Boolean,
  val oneshot: Boolean,
)

@Serializable
data class KomgaSeriesMetadataDto(
  val status: String,
  val statusLock: Boolean,
  val title: String,
  val titleLock: Boolean,
  val titleSort: String,
  val titleSortLock: Boolean,
  val summary: String,
  val summaryLock: Boolean,
  val readingDirection: String,
  val readingDirectionLock: Boolean,
  val publisher: String,
  val publisherLock: Boolean,
  val ageRating: Int? = null,
  val ageRatingLock: Boolean,
  val language: String,
  val languageLock: Boolean,
  val genres: Set<String>,
  val genresLock: Boolean,
  val tags: Set<String>,
  val tagsLock: Boolean,
  val totalBookCount: Int? = null,
  val totalBookCountLock: Boolean,
  val sharingLabels: Set<String>,
  val sharingLabelsLock: Boolean,
  val links: List<KomgaWebLinkDto>,
  val linksLock: Boolean,
  val alternateTitles: List<KomgaAlternateTitleDto>,
  val alternateTitlesLock: Boolean,
  val created: String,
  val lastModified: String,
)

@Serializable
data class KomgaBookMetadataAggregationDto(
  val authors: List<KomgaAuthorDto>,
  val tags: Set<String>,
  val releaseDate: String? = null,
  val summary: String,
  val summaryNumber: String,
  val created: String,
  val lastModified: String,
)

@Serializable
data class KomgaAuthorDto(
  val name: String,
  val role: String,
)

@Serializable
data class KomgaWebLinkDto(
  val label: String,
  val url: String,
)

@Serializable
data class KomgaAlternateTitleDto(
  val label: String,
  val title: String,
)

@Serializable
data class KomgaGroupCountDto(
  val group: String,
  val count: Int,
)

@Serializable
data class KomgaPageDto<T>(
  val content: List<T>,
  val pageable: KomgaPageableDto,
  val totalPages: Int,
  val totalElements: Long,
  val last: Boolean,
  val size: Int,
  val number: Int,
  val sort: KomgaSortDto,
  val numberOfElements: Int,
  val first: Boolean,
  val empty: Boolean,
)

@Serializable
data class KomgaPageableDto(
  val offset: Long,
  val sort: KomgaSortDto,
  val paged: Boolean,
  val pageNumber: Int,
  val pageSize: Int,
  val unpaged: Boolean,
)

@Serializable
data class KomgaSortDto(
  val empty: Boolean,
  val sorted: Boolean,
  val unsorted: Boolean,
)

private fun ApplicationCall.catalogPrincipal(): KomgaPrincipal =
  requireNotNull(principal<KomgaPrincipal>()) { "Catalog routes require authentication" }

internal fun User.catalogAccess(): CatalogAccess =
  CatalogAccess(
    userId = id,
    libraryIds = if (canAccessAllLibraries()) null else sharedLibraryIds,
    restrictions = restrictions,
  )

internal fun ApplicationCall.catalogPageRequest(
  defaultSort: List<CatalogSort> = emptyList(),
): CatalogPageRequest {
  val page = request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
  val size =
    request.queryParameters["size"]
      ?.toIntOrNull()
      ?.coerceIn(1, CatalogPageRequest.MAXIMUM_PAGE_SIZE)
      ?: 20
  val unpaged = request.queryParameters["unpaged"]?.toBooleanStrictOrNull() ?: false
  val sorts =
    request.queryParameters.getAll("sort")
      ?.mapNotNull(String::toCatalogSort)
      .orEmpty()
      .ifEmpty { defaultSort }
  return CatalogPageRequest(page = page, size = size, sorts = sorts, unpaged = unpaged)
}

private fun String.toCatalogSort(): CatalogSort? {
  val parts = split(',')
  val property = parts.firstOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val direction =
    when (parts.getOrNull(1)?.trim()?.lowercase()) {
      "desc" -> CatalogSortDirection.DESC
      else -> CatalogSortDirection.ASC
    }
  return CatalogSort(property, direction)
}

private fun ApplicationCall.deprecatedSeriesQuery(): SeriesCatalogQuery =
  SeriesCatalogQuery(
    libraryIds = queryLibraryIds(),
    fullTextSearch = request.queryParameters["search"],
    deleted = queryBoolean("deleted") ?: false,
    oneshot = queryBoolean("oneshot"),
    publishers = request.queryParameters.getAll("publisher").orEmpty().toSet(),
    languages = request.queryParameters.getAll("language").orEmpty().toSet(),
    genres = request.queryParameters.getAll("genre").orEmpty().toSet(),
    tags = request.queryParameters.getAll("tag").orEmpty().toSet(),
  )

private fun ApplicationCall.queryLibraryIds(): Set<LibraryId> =
  request.queryParameters.getAll("library_id").orEmpty().map(::LibraryId).toSet()

private fun ApplicationCall.queryBoolean(name: String): Boolean? =
  request.queryParameters[name]?.toBooleanStrictOrNull()

private suspend fun ApplicationCall.requireSupportedSearch(search: JsonObject): Boolean {
  val condition = search["condition"]
  if (condition != null && condition !is JsonNull) {
    respond(
      HttpStatusCode.BadRequest,
      mapOf("error" to "Structured search conditions are not implemented yet"),
    )
    return false
  }
  return true
}

private fun JsonObject.string(name: String): String? =
  get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

internal fun CatalogBook.toDto(user: User): KomgaBookDto =
  KomgaBookDto(
    id = book.id.value,
    seriesId = book.seriesId.value,
    seriesTitle = seriesTitle,
    libraryId = book.libraryId.value,
    name = book.name,
    url = if (user.isAdmin) book.sourceItemId else book.relativePath.substringAfterLast('/'),
    number = book.number,
    created = book.createdAtMillis.toWireTime(),
    lastModified = book.updatedAtMillis.toWireTime(),
    fileLastModified = book.fileModifiedAtMillis.toWireTime(),
    sizeBytes = book.fileSize,
    size = book.fileSize.toHumanSize(),
    media = media.toDto(),
    metadata = metadata.toDto(),
    readProgress =
      readProgress?.let {
        KomgaReadProgressDto(
          page = it.page,
          completed = it.completed,
          readDate = it.readAtMillis.toWireTime(),
          created = it.createdAtMillis.toWireTime(),
          lastModified = it.updatedAtMillis.toWireTime(),
          deviceId = it.deviceId,
          deviceName = it.deviceName,
        )
      },
    deleted = book.deletedAtMillis != null,
    fileHash = book.fileHash,
    oneshot = book.oneshot,
  )

private fun BookMedia?.toDto(): KomgaMediaDto =
  KomgaMediaDto(
    status = this?.status?.name ?: MediaStatus.UNKNOWN.name,
    mediaType = this?.mediaType.orEmpty(),
    mediaProfile = this?.profile?.name.orEmpty(),
    pagesCount = this?.pageCount ?: 0,
    comment = this?.comment.orEmpty(),
    epubDivinaCompatible = this?.profile?.name == "DIVINA",
    epubIsKepub = false,
  )

private fun BookMetadata.toDto(): KomgaBookMetadataDto =
  KomgaBookMetadataDto(
    title = title,
    titleLock = titleLock,
    summary = summary,
    summaryLock = summaryLock,
    number = number,
    numberLock = numberLock,
    numberSort = numberSort,
    numberSortLock = numberSortLock,
    releaseDate = releaseDate,
    releaseDateLock = releaseDateLock,
    authors = authors.map(Author::toDto),
    authorsLock = authorsLock,
    tags = tags,
    tagsLock = tagsLock,
    isbn = isbn,
    isbnLock = isbnLock,
    links = links.map(WebLink::toDto),
    linksLock = linksLock,
    created = createdAtMillis.toWireTime(),
    lastModified = updatedAtMillis.toWireTime(),
  )

internal fun CatalogSeries.toDto(user: User): KomgaSeriesDto =
  KomgaSeriesDto(
    id = series.id.value,
    libraryId = series.libraryId.value,
    name = series.name,
    url = if (user.isAdmin) series.sourceItemId else "",
    created = series.createdAtMillis.toWireTime(),
    lastModified = series.updatedAtMillis.toWireTime(),
    fileLastModified = series.fileModifiedAtMillis.toWireTime(),
    booksCount = series.bookCount,
    booksReadCount = readProgress?.booksReadCount ?: 0,
    booksUnreadCount =
      (series.bookCount -
        (readProgress?.booksReadCount ?: 0) -
        (readProgress?.booksInProgressCount ?: 0)).coerceAtLeast(0),
    booksInProgressCount = readProgress?.booksInProgressCount ?: 0,
    metadata = metadata.toDto(),
    booksMetadata = booksMetadata.toDto(),
    deleted = series.deletedAtMillis != null,
    oneshot = series.oneshot,
  )

private fun SeriesMetadata.toDto(): KomgaSeriesMetadataDto =
  KomgaSeriesMetadataDto(
    status = status.name,
    statusLock = statusLock,
    title = title,
    titleLock = titleLock,
    titleSort = titleSort,
    titleSortLock = titleSortLock,
    summary = summary,
    summaryLock = summaryLock,
    readingDirection = readingDirection?.name.orEmpty(),
    readingDirectionLock = readingDirectionLock,
    publisher = publisher,
    publisherLock = publisherLock,
    ageRating = ageRating,
    ageRatingLock = ageRatingLock,
    language = language,
    languageLock = languageLock,
    genres = genres,
    genresLock = genresLock,
    tags = tags,
    tagsLock = tagsLock,
    totalBookCount = totalBookCount,
    totalBookCountLock = totalBookCountLock,
    sharingLabels = sharingLabels,
    sharingLabelsLock = sharingLabelsLock,
    links = links.map(WebLink::toDto),
    linksLock = linksLock,
    alternateTitles = alternateTitles.map(AlternateTitle::toDto),
    alternateTitlesLock = alternateTitlesLock,
    created = createdAtMillis.toWireTime(),
    lastModified = updatedAtMillis.toWireTime(),
  )

private fun BookMetadataAggregation.toDto(): KomgaBookMetadataAggregationDto =
  KomgaBookMetadataAggregationDto(
    authors = authors.map(Author::toDto),
    tags = tags,
    releaseDate = releaseDate,
    summary = summary,
    summaryNumber = summaryNumber,
    created = createdAtMillis.toWireTime(),
    lastModified = updatedAtMillis.toWireTime(),
  )

private fun Author.toDto(): KomgaAuthorDto = KomgaAuthorDto(name, role)

private fun WebLink.toDto(): KomgaWebLinkDto = KomgaWebLinkDto(label, url)

private fun AlternateTitle.toDto(): KomgaAlternateTitleDto =
  KomgaAlternateTitleDto(label, title)

private fun CatalogGroupCount.toDto(): KomgaGroupCountDto = KomgaGroupCountDto(group, count)

internal fun CatalogPage<CatalogBook>.toBookPageDto(user: User): KomgaPageDto<KomgaBookDto> =
  toPageDto(content.map { it.toDto(user) })

internal fun CatalogPage<CatalogSeries>.toSeriesPageDto(
  user: User,
): KomgaPageDto<KomgaSeriesDto> = toPageDto(content.map { it.toDto(user) })

internal fun <T, R> CatalogPage<T>.toPageDto(mapped: List<R>): KomgaPageDto<R> {
  val totalPages = ceil(totalElements.toDouble() / size).toInt()
  val sort = KomgaSortDto(empty = false, sorted = true, unsorted = false)
  return KomgaPageDto(
    content = mapped,
    pageable =
      KomgaPageableDto(
        offset = page.toLong() * size,
        sort = sort,
        paged = !unpaged,
        pageNumber = page,
        pageSize = size,
        unpaged = unpaged,
      ),
    totalPages = totalPages,
    totalElements = totalElements,
    last = page + 1 >= totalPages,
    size = size,
    number = page,
    sort = sort,
    numberOfElements = mapped.size,
    first = page == 0,
    empty = mapped.isEmpty(),
  )
}

private fun Long.toWireTime(): String = Instant.ofEpochMilli(this).toString()

private fun Long.toHumanSize(): String {
  if (this < 1_024) return "$this B"
  val units = listOf("KiB", "MiB", "GiB", "TiB")
  var value = toDouble()
  var unit = -1
  while (value >= 1_024 && unit < units.lastIndex) {
    value /= 1_024
    unit += 1
  }
  return if (value >= 10 || value % 1.0 == 0.0) {
    "${value.toLong()} ${units[unit]}"
  } else {
    "${"%.1f".format(Locale.ROOT, value)} ${units[unit]}"
  }
}
