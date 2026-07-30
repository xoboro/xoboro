package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.OrganizationLifecycle
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserRole
import java.time.Instant
import kotlinx.serialization.Serializable

private val COLLECTION_MEMBER_FILTERS =
  setOf(
    LegacySeriesFilter.AGE_RATING,
    LegacySeriesFilter.AUTHOR,
    LegacySeriesFilter.COMPLETE,
    LegacySeriesFilter.GENRE,
    LegacySeriesFilter.LANGUAGE,
    LegacySeriesFilter.PUBLISHER,
    LegacySeriesFilter.READ_STATUS,
    LegacySeriesFilter.RELEASE_YEAR,
    LegacySeriesFilter.SERIES_STATUS,
    LegacySeriesFilter.TAG,
  )

private val READ_LIST_MEMBER_FILTERS =
  setOf(
    LegacyBookFilter.AUTHOR,
    LegacyBookFilter.MEDIA_STATUS,
    LegacyBookFilter.READ_STATUS,
    LegacyBookFilter.TAG,
  )

private const val ORGANIZATION_VISIBILITY_BATCH_SIZE = 500

fun Route.komgaOrganizationRoutes(
  collections: SeriesCollectionRepository,
  readLists: ReadListRepository,
  lifecycle: OrganizationLifecycle,
  catalog: CatalogReadRepository,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    route("/api/v1/collections") {
      get {
        val principal = call.organizationPrincipal()
        val requestedLibraries = call.queryLibraryIds()
        val search = call.request.queryParameters["search"]?.trim().orEmpty()
        val visible =
          visibleCollections(
            collections = collections.findAll(),
            user = principal.user,
            catalog = catalog,
            requestedLibraries = requestedLibraries,
          )
            .filter { search.isEmpty() || it.collection.name.contains(search, ignoreCase = true) }
        val page = visible.paginate(call.catalogPageRequest())
        call.respond(page.toPageDto(page.content.map(VisibleCollection::toDto)))
      }
      post {
        if (!call.requireAdministrator()) return@post
        val request = call.receive<CollectionCreationDto>()
        try {
          call.respond(
            lifecycle
              .createCollection(
                name = request.name,
                ordered = request.ordered,
                seriesIds = request.seriesIds.map(::SeriesId),
              ).toDto(filtered = false),
          )
        } catch (failure: IllegalArgumentException) {
          call.respondBadOrganizationRequest(failure)
        }
      }
      get("/{id}") {
        val principal = call.organizationPrincipal()
        val visible =
          collections
            .findByIdOrNull(CollectionId(requireNotNull(call.parameters["id"])))
            ?.let {
              visibleCollections(listOf(it), principal.user, catalog).singleOrNull()
            }
        if (visible == null) call.respond(HttpStatusCode.NotFound) else call.respond(visible.toDto())
      }
      patch("/{id}") {
        if (!call.requireAdministrator()) return@patch
        val id = CollectionId(requireNotNull(call.parameters["id"]))
        val request = call.receive<CollectionUpdateDto>()
        try {
          lifecycle.updateCollection(
            id = id,
            name = request.name,
            ordered = request.ordered,
            seriesIds = request.seriesIds?.map(::SeriesId),
          )
          call.respond(HttpStatusCode.NoContent)
        } catch (failure: IllegalArgumentException) {
          call.respondBadOrganizationRequest(failure)
        } catch (_: NoSuchElementException) {
          call.respond(HttpStatusCode.NotFound)
        }
      }
      delete("/{id}") {
        if (!call.requireAdministrator()) return@delete
        val deleted =
          lifecycle.deleteCollection(CollectionId(requireNotNull(call.parameters["id"])))
        call.respond(if (deleted) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
      }
      get("/{id}/series") {
        val principal = call.organizationPrincipal()
        val collectionId = CollectionId(requireNotNull(call.parameters["id"]))
        val collection = collections.findByIdOrNull(collectionId)
        if (
          collection == null ||
          !collection.isVisibleTo(principal.user, catalog)
        ) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        val sort =
          if (collection.ordered) {
            CatalogSort("collection.number")
          } else {
            CatalogSort("metadata.titleSort")
          }
        val page =
          catalog.findSeries(
            query =
              SeriesCatalogQuery(
                libraryIds = call.queryLibraryIds(),
                deleted = call.organizationQueryBoolean("deleted"),
                condition =
                  call.legacySeriesCondition(
                    initial =
                      listOf(
                        membershipCondition(
                          CatalogSearchField.COLLECTION_ID,
                          collectionId.value,
                        ),
                      ),
                    filters = COLLECTION_MEMBER_FILTERS,
                  ),
              ),
            access = principal.user.catalogAccess(),
            page = call.catalogPageRequest().copy(sorts = listOf(sort)),
          )
        call.respondCatalog(
          page.toSeriesPageDto(principal.user),
        )
      }
    }

    get("/api/v1/series/{seriesId}/collections") {
      val principal = call.organizationPrincipal()
      val seriesId = SeriesId(requireNotNull(call.parameters["seriesId"]))
      if (catalog.findSeriesByIdOrNull(seriesId, principal.user.catalogAccess()) == null) {
        call.respond(HttpStatusCode.NotFound)
        return@get
      }
      call.respond(
        visibleCollections(
          collections = collections.findAllBySeriesId(seriesId),
          user = principal.user,
          catalog = catalog,
        )
          .map(VisibleCollection::toDto),
      )
    }

    route("/api/v1/readlists") {
      get {
        val principal = call.organizationPrincipal()
        val requestedLibraries = call.queryLibraryIds()
        val search = call.request.queryParameters["search"]?.trim().orEmpty()
        val visible =
          visibleReadLists(
            readLists = readLists.findAll(),
            user = principal.user,
            catalog = catalog,
            requestedLibraries = requestedLibraries,
          )
            .filter { search.isEmpty() || it.readList.name.contains(search, ignoreCase = true) }
        val page = visible.paginate(call.catalogPageRequest())
        call.respond(page.toPageDto(page.content.map(VisibleReadList::toDto)))
      }
      post {
        if (!call.requireAdministrator()) return@post
        val request = call.receive<ReadListCreationDto>()
        try {
          call.respond(
            lifecycle
              .createReadList(
                name = request.name,
                summary = request.summary,
                ordered = request.ordered,
                bookIds = request.bookIds.map(::BookId),
              ).toDto(filtered = false),
          )
        } catch (failure: IllegalArgumentException) {
          call.respondBadOrganizationRequest(failure)
        }
      }
      get("/{id}") {
        val principal = call.organizationPrincipal()
        val visible =
          readLists
            .findByIdOrNull(ReadListId(requireNotNull(call.parameters["id"])))
            ?.let {
              visibleReadLists(listOf(it), principal.user, catalog).singleOrNull()
            }
        if (visible == null) call.respond(HttpStatusCode.NotFound) else call.respond(visible.toDto())
      }
      patch("/{id}") {
        if (!call.requireAdministrator()) return@patch
        val id = ReadListId(requireNotNull(call.parameters["id"]))
        val request = call.receive<ReadListUpdateDto>()
        try {
          lifecycle.updateReadList(
            id = id,
            name = request.name,
            summary = request.summary,
            ordered = request.ordered,
            bookIds = request.bookIds?.map(::BookId),
          )
          call.respond(HttpStatusCode.NoContent)
        } catch (failure: IllegalArgumentException) {
          call.respondBadOrganizationRequest(failure)
        } catch (_: NoSuchElementException) {
          call.respond(HttpStatusCode.NotFound)
        }
      }
      delete("/{id}") {
        if (!call.requireAdministrator()) return@delete
        val deleted = lifecycle.deleteReadList(ReadListId(requireNotNull(call.parameters["id"])))
        call.respond(if (deleted) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
      }
      get("/{id}/books") {
        val principal = call.organizationPrincipal()
        val readListId = ReadListId(requireNotNull(call.parameters["id"]))
        val readList = readLists.findByIdOrNull(readListId)
        if (
          readList == null ||
          !readList.isVisibleTo(principal.user, catalog)
        ) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        val sort =
          if (readList.ordered) {
            CatalogSort("readList.number")
          } else {
            CatalogSort("metadata.releaseDate")
          }
        val page =
          catalog.findBooks(
            query =
              BookCatalogQuery(
                libraryIds = call.queryLibraryIds(),
                deleted = call.organizationQueryBoolean("deleted"),
                condition =
                  call.legacyBookCondition(
                    initial =
                      listOf(
                        membershipCondition(
                          CatalogSearchField.READ_LIST_ID,
                          readListId.value,
                        ),
                      ),
                    filters = READ_LIST_MEMBER_FILTERS,
                  ),
              ),
            access = principal.user.catalogAccess(),
            page = call.catalogPageRequest().copy(sorts = listOf(sort)),
          )
        call.respondCatalog(page.toBookPageDto(principal.user))
      }
      get("/{id}/books/{bookId}/previous") {
        call.respondReadListSibling(readLists, catalog, previous = true)
      }
      get("/{id}/books/{bookId}/next") {
        call.respondReadListSibling(readLists, catalog, previous = false)
      }
    }

    get("/api/v1/books/{bookId}/readlists") {
      val principal = call.organizationPrincipal()
      val bookId = BookId(requireNotNull(call.parameters["bookId"]))
      if (catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess()) == null) {
        call.respond(HttpStatusCode.NotFound)
        return@get
      }
      call.respond(
        visibleReadLists(
          readLists = readLists.findAllByBookId(bookId),
          user = principal.user,
          catalog = catalog,
        )
          .map(VisibleReadList::toDto),
      )
    }
  }
}

@Serializable
data class CollectionCreationDto(
  val name: String,
  val ordered: Boolean,
  val seriesIds: List<String>,
)

@Serializable
data class CollectionUpdateDto(
  val name: String? = null,
  val ordered: Boolean? = null,
  val seriesIds: List<String>? = null,
)

@Serializable
data class KomgaCollectionDto(
  val id: String,
  val name: String,
  val ordered: Boolean,
  val seriesIds: List<String>,
  val createdDate: String,
  val lastModifiedDate: String,
  val filtered: Boolean,
)

@Serializable
data class ReadListCreationDto(
  val name: String,
  val summary: String = "",
  val ordered: Boolean = true,
  val bookIds: List<String>,
)

@Serializable
data class ReadListUpdateDto(
  val name: String? = null,
  val summary: String? = null,
  val ordered: Boolean? = null,
  val bookIds: List<String>? = null,
)

@Serializable
data class KomgaReadListDto(
  val id: String,
  val name: String,
  val summary: String,
  val ordered: Boolean,
  val bookIds: List<String>,
  val createdDate: String,
  val lastModifiedDate: String,
  val filtered: Boolean,
)

private data class VisibleCollection(
  val collection: SeriesCollection,
  val series: List<CatalogSeries>,
) {
  fun toDto(): KomgaCollectionDto =
    collection.copy(seriesIds = series.map { it.series.id }).toDto(
      filtered = series.size != collection.seriesIds.size,
    )
}

private data class VisibleReadList(
  val readList: ReadList,
  val books: List<CatalogBook>,
) {
  fun toDto(): KomgaReadListDto =
    readList.copy(bookIds = books.map { it.book.id }).toDto(
      filtered = books.size != readList.bookIds.size,
    )
}

private fun visibleCollections(
  collections: List<SeriesCollection>,
  user: User,
  catalog: CatalogReadRepository,
  requestedLibraries: Set<LibraryId> = emptySet(),
): List<VisibleCollection> {
  val visibleById =
    collections
      .chunked(ORGANIZATION_VISIBILITY_BATCH_SIZE)
      .flatMap { batch ->
        val condition =
          organizationMembershipCondition(
            field = CatalogSearchField.COLLECTION_ID,
            values = batch.map { it.id.value },
          ) ?: return@flatMap emptyList()
        catalog
          .findSeries(
            query =
              SeriesCatalogQuery(
                libraryIds = requestedLibraries,
                deleted = null,
                condition = condition,
              ),
            access = user.catalogAccess(),
            page = CatalogPageRequest(unpaged = true),
          ).content
      }.associateBy { it.series.id }
  return collections.mapNotNull { collection ->
    val visible = collection.seriesIds.mapNotNull(visibleById::get)
    VisibleCollection(collection, visible).takeIf {
      visible.isNotEmpty() ||
        (user.isAdmin && requestedLibraries.isEmpty() && collection.seriesIds.isEmpty())
    }
  }
}

private fun visibleReadLists(
  readLists: List<ReadList>,
  user: User,
  catalog: CatalogReadRepository,
  requestedLibraries: Set<LibraryId> = emptySet(),
): List<VisibleReadList> {
  val visibleById =
    readLists
      .chunked(ORGANIZATION_VISIBILITY_BATCH_SIZE)
      .flatMap { batch ->
        val condition =
          organizationMembershipCondition(
            field = CatalogSearchField.READ_LIST_ID,
            values = batch.map { it.id.value },
          ) ?: return@flatMap emptyList()
        catalog
          .findBooks(
            query =
              BookCatalogQuery(
                libraryIds = requestedLibraries,
                deleted = null,
                condition = condition,
              ),
            access = user.catalogAccess(),
            page = CatalogPageRequest(unpaged = true),
          ).content
      }.associateBy { it.book.id }
  return readLists.mapNotNull { readList ->
    val visible = readList.bookIds.mapNotNull(visibleById::get)
    VisibleReadList(readList, visible).takeIf {
      visible.isNotEmpty() ||
        (user.isAdmin && requestedLibraries.isEmpty() && readList.bookIds.isEmpty())
    }
  }
}

private fun organizationMembershipCondition(
  field: CatalogSearchField,
  values: List<String>,
): CatalogSearchCondition? {
  val predicates = values.map { membershipCondition(field, it) }
  return when (predicates.size) {
    0 -> null
    1 -> predicates.single()
    else -> CatalogSearchCondition.AnyOf(predicates)
  }
}

private fun SeriesCollection.isVisibleTo(
  user: User,
  catalog: CatalogReadRepository,
): Boolean {
  if (user.isAdmin) return true
  return catalog
    .findSeries(
      query =
        SeriesCatalogQuery(
          deleted = null,
          condition = membershipCondition(CatalogSearchField.COLLECTION_ID, id.value),
        ),
      access = user.catalogAccess(),
      page = CatalogPageRequest(size = 1),
    ).totalElements > 0
}

private fun ReadList.isVisibleTo(
  user: User,
  catalog: CatalogReadRepository,
): Boolean {
  if (user.isAdmin) return true
  return catalog
    .findBooks(
      query =
        BookCatalogQuery(
          deleted = null,
          condition = membershipCondition(CatalogSearchField.READ_LIST_ID, id.value),
        ),
      access = user.catalogAccess(),
      page = CatalogPageRequest(size = 1),
    ).totalElements > 0
}

private fun membershipCondition(
  field: CatalogSearchField,
  value: String,
): CatalogSearchCondition.Predicate =
  CatalogSearchCondition.Predicate(field, CatalogSearchOperator.IS, value)

private fun SeriesCollection.toDto(filtered: Boolean): KomgaCollectionDto =
  KomgaCollectionDto(
    id = id.value,
    name = name,
    ordered = ordered,
    seriesIds = seriesIds.map(SeriesId::value),
    createdDate = Instant.ofEpochMilli(createdAtMillis).toString(),
    lastModifiedDate = Instant.ofEpochMilli(updatedAtMillis).toString(),
    filtered = filtered,
  )

private fun ReadList.toDto(filtered: Boolean): KomgaReadListDto =
  KomgaReadListDto(
    id = id.value,
    name = name,
    summary = summary,
    ordered = ordered,
    bookIds = bookIds.map(BookId::value),
    createdDate = Instant.ofEpochMilli(createdAtMillis).toString(),
    lastModifiedDate = Instant.ofEpochMilli(updatedAtMillis).toString(),
    filtered = filtered,
  )

private fun <T> List<T>.paginate(request: CatalogPageRequest): CatalogPage<T> {
  if (request.unpaged) {
    return CatalogPage(
      content = this,
      page = 0,
      size = size.coerceAtLeast(1),
      totalElements = size.toLong(),
      unpaged = true,
    )
  }
  val first = (request.page.toLong() * request.size).coerceAtMost(size.toLong()).toInt()
  val last = (first + request.size).coerceAtMost(size)
  return CatalogPage(
    content = subList(first, last),
    page = request.page,
    size = request.size,
    totalElements = size.toLong(),
  )
}

private suspend fun ApplicationCall.respondReadListSibling(
  readLists: ReadListRepository,
  catalog: CatalogReadRepository,
  previous: Boolean,
) {
  val principal = organizationPrincipal()
  val visible =
    readLists
      .findByIdOrNull(ReadListId(requireNotNull(parameters["id"])))
      ?.let { visibleReadLists(listOf(it), principal.user, catalog).singleOrNull() }
  if (visible == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  val position = visible.books.indexOfFirst { it.book.id == bookId }
  val sibling = visible.books.getOrNull(position + if (previous) -1 else 1)
  if (position < 0 || sibling == null) {
    respond(HttpStatusCode.NotFound)
  } else {
    respond(sibling.toDto(principal.user))
  }
}

private fun ApplicationCall.organizationPrincipal(): KomgaPrincipal =
  requireNotNull(principal<KomgaPrincipal>()) { "Organization routes require authentication" }

private suspend fun ApplicationCall.requireAdministrator(): Boolean {
  if (UserRole.ADMIN in organizationPrincipal().user.roles) return true
  respond(HttpStatusCode.Forbidden)
  return false
}

private fun ApplicationCall.queryLibraryIds(): Set<LibraryId> =
  request.queryParameters.getAll("library_id").orEmpty().map(::LibraryId).toSet()

private fun ApplicationCall.organizationQueryBoolean(name: String): Boolean? =
  request.queryParameters[name]?.toBooleanStrictOrNull()

private suspend fun ApplicationCall.respondBadOrganizationRequest(failure: IllegalArgumentException) {
  respond(
    HttpStatusCode.BadRequest,
    mapOf("error" to (failure.message ?: "Invalid organization request")),
  )
}
