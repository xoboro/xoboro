package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.OrganizationLifecycle
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SeriesId

fun Route.xoboroNativeCollectionsRoutes(
  organization: OrganizationLifecycle,
  collections: SeriesCollectionRepository,
  readLists: ReadListRepository,
  catalog: CatalogReadRepository,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      route("/collections") {
        get {
          val access = call.nativeUser().nativeCatalogAccess()
          call.respond(
            collections.findAll().mapNotNull { collection ->
              val visible = collection.visibleSeriesInOrder(access, catalog)
              collection.toNativeResponse(visible.size).takeIf { visible.isNotEmpty() }
            },
          )
        }
        post {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondCollectionAdministrationForbidden()
            return@post
          }
          val access = caller.nativeCatalogAccess()
          val request = call.receive<XoboroCollectionCreationRequest>()
          val seriesIds =
            try {
              request.seriesIds.map(::SeriesId)
            } catch (failure: IllegalArgumentException) {
              call.respondCollectionValidationFailure(failure)
              return@post
            }
          if (!call.requireVisibleSeriesMembers(seriesIds, access, catalog)) return@post
          val created =
            try {
              organization.createCollection(
                name = request.name,
                ordered = request.ordered,
                seriesIds = seriesIds,
              )
            } catch (failure: IllegalArgumentException) {
              call.respondCollectionValidationFailure(failure)
              return@post
            }
          val visible = created.visibleSeriesInOrder(access, catalog)
          call.respond(HttpStatusCode.Created, created.toNativeResponse(visible.size))
        }
        get("/{collectionId}") {
          val access = call.nativeUser().nativeCatalogAccess()
          val collection =
            collections.findByIdOrNull(
              CollectionId(call.requiredParameter("collectionId")),
            )
          val visible = collection?.visibleSeriesInOrder(access, catalog).orEmpty()
          if (collection == null || visible.isEmpty()) {
            call.respondCollectionNotFound()
          } else {
            call.respond(collection.toNativeResponse(visible.size))
          }
        }
        put("/{collectionId}") {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondCollectionAdministrationForbidden()
            return@put
          }
          val id = CollectionId(call.requiredParameter("collectionId"))
          val access = caller.nativeCatalogAccess()
          val existing = collections.findByIdOrNull(id)
          if (
            existing == null ||
            existing.visibleSeriesInOrder(access, catalog).isEmpty()
          ) {
            call.respondCollectionNotFound()
            return@put
          }
          val request = call.receive<XoboroCollectionUpdateRequest>()
          val seriesIds =
            try {
              request.seriesIds.map(::SeriesId)
            } catch (failure: IllegalArgumentException) {
              call.respondCollectionValidationFailure(failure)
              return@put
            }
          if (!call.requireVisibleSeriesMembers(seriesIds, access, catalog)) return@put
          val updated =
            try {
              organization.updateCollection(
                id = id,
                name = request.name,
                ordered = request.ordered,
                seriesIds = seriesIds,
              )
            } catch (failure: IllegalArgumentException) {
              call.respondCollectionValidationFailure(failure)
              return@put
            }
          val visible = updated.visibleSeriesInOrder(access, catalog)
          call.respond(updated.toNativeResponse(visible.size))
        }
        delete("/{collectionId}") {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondCollectionAdministrationForbidden()
            return@delete
          }
          val id = CollectionId(call.requiredParameter("collectionId"))
          val access = caller.nativeCatalogAccess()
          val existing = collections.findByIdOrNull(id)
          if (
            existing == null ||
            existing.visibleSeriesInOrder(access, catalog).isEmpty()
          ) {
            call.respondCollectionNotFound()
            return@delete
          }
          if (!organization.deleteCollection(id)) {
            call.respondCollectionNotFound()
            return@delete
          }
          call.respond(HttpStatusCode.NoContent)
        }
        get("/{collectionId}/series") {
          val access = call.nativeUser().nativeCatalogAccess()
          val collection =
            collections.findByIdOrNull(
              CollectionId(call.requiredParameter("collectionId")),
            )
          val visible = collection?.visibleSeriesInOrder(access, catalog).orEmpty()
          if (collection == null || visible.isEmpty()) {
            call.respondCollectionNotFound()
            return@get
          }
          val page = call.nativeOrganizationPage()
          call.respond(
            visible.toNativePage(page) { it.toNativeResponse() },
          )
        }
      }
      route("/read-lists") {
        get {
          val access = call.nativeUser().nativeCatalogAccess()
          call.respond(
            readLists.findAll().mapNotNull { readList ->
              val visible = readList.visibleBooksInOrder(access, catalog)
              readList.toNativeResponse(visible.size).takeIf { visible.isNotEmpty() }
            },
          )
        }
        post {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondReadListAdministrationForbidden()
            return@post
          }
          val access = caller.nativeCatalogAccess()
          val request = call.receive<XoboroReadListCreationRequest>()
          val bookIds =
            try {
              request.mediaItemIds.map(::BookId)
            } catch (failure: IllegalArgumentException) {
              call.respondReadListValidationFailure(failure)
              return@post
            }
          if (!call.requireVisibleBookMembers(bookIds, access, catalog)) return@post
          val created =
            try {
              organization.createReadList(
                name = request.name,
                summary = request.summary,
                ordered = request.ordered,
                bookIds = bookIds,
              )
            } catch (failure: IllegalArgumentException) {
              call.respondReadListValidationFailure(failure)
              return@post
            }
          val visible = created.visibleBooksInOrder(access, catalog)
          call.respond(HttpStatusCode.Created, created.toNativeResponse(visible.size))
        }
        get("/{readListId}") {
          val access = call.nativeUser().nativeCatalogAccess()
          val readList =
            readLists.findByIdOrNull(
              ReadListId(call.requiredParameter("readListId")),
            )
          val visible = readList?.visibleBooksInOrder(access, catalog).orEmpty()
          if (readList == null || visible.isEmpty()) {
            call.respondReadListNotFound()
          } else {
            call.respond(readList.toNativeResponse(visible.size))
          }
        }
        put("/{readListId}") {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondReadListAdministrationForbidden()
            return@put
          }
          val id = ReadListId(call.requiredParameter("readListId"))
          val access = caller.nativeCatalogAccess()
          val existing = readLists.findByIdOrNull(id)
          if (
            existing == null ||
            existing.visibleBooksInOrder(access, catalog).isEmpty()
          ) {
            call.respondReadListNotFound()
            return@put
          }
          val request = call.receive<XoboroReadListUpdateRequest>()
          val bookIds =
            try {
              request.mediaItemIds.map(::BookId)
            } catch (failure: IllegalArgumentException) {
              call.respondReadListValidationFailure(failure)
              return@put
            }
          if (!call.requireVisibleBookMembers(bookIds, access, catalog)) return@put
          val updated =
            try {
              organization.updateReadList(
                id = id,
                name = request.name,
                summary = request.summary,
                ordered = request.ordered,
                bookIds = bookIds,
              )
            } catch (failure: IllegalArgumentException) {
              call.respondReadListValidationFailure(failure)
              return@put
            }
          val visible = updated.visibleBooksInOrder(access, catalog)
          call.respond(updated.toNativeResponse(visible.size))
        }
        delete("/{readListId}") {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondReadListAdministrationForbidden()
            return@delete
          }
          val id = ReadListId(call.requiredParameter("readListId"))
          val access = caller.nativeCatalogAccess()
          val existing = readLists.findByIdOrNull(id)
          if (
            existing == null ||
            existing.visibleBooksInOrder(access, catalog).isEmpty()
          ) {
            call.respondReadListNotFound()
            return@delete
          }
          if (!organization.deleteReadList(id)) {
            call.respondReadListNotFound()
            return@delete
          }
          call.respond(HttpStatusCode.NoContent)
        }
        get("/{readListId}/media-items") {
          val access = call.nativeUser().nativeCatalogAccess()
          val readList =
            readLists.findByIdOrNull(
              ReadListId(call.requiredParameter("readListId")),
            )
          val visible = readList?.visibleBooksInOrder(access, catalog).orEmpty()
          if (readList == null || visible.isEmpty()) {
            call.respondReadListNotFound()
            return@get
          }
          val page = call.nativeOrganizationPage()
          call.respond(
            visible.toNativePage(page) { it.toNativeResponse() },
          )
        }
      }
      get("/series/{seriesId}/collections") {
        val access = call.nativeUser().nativeCatalogAccess()
        val seriesId = SeriesId(call.requiredParameter("seriesId"))
        if (catalog.findSeriesByIdOrNull(seriesId, access) == null) {
          call.respondNativeNotFound("series_not_found", "Series was not found")
          return@get
        }
        call.respond(
          collections.findAllBySeriesId(seriesId).mapNotNull { collection ->
            val visible = collection.visibleSeriesInOrder(access, catalog)
            collection.toNativeResponse(visible.size).takeIf { visible.isNotEmpty() }
          },
        )
      }
      get("/media-items/{mediaItemId}/read-lists") {
        val access = call.nativeUser().nativeCatalogAccess()
        val mediaItemId = BookId(call.requiredParameter("mediaItemId"))
        if (catalog.findBookByIdOrNull(mediaItemId, access) == null) {
          call.respondNativeNotFound("media_item_not_found", "Media item was not found")
          return@get
        }
        call.respond(
          readLists.findAllByBookId(mediaItemId).mapNotNull { readList ->
            val visible = readList.visibleBooksInOrder(access, catalog)
            readList.toNativeResponse(visible.size).takeIf { visible.isNotEmpty() }
          },
        )
      }
    }
  }
}

private fun SeriesCollection.visibleSeriesInOrder(
  access: CatalogAccess,
  catalog: CatalogReadRepository,
): List<CatalogSeries> {
  val visibleById =
    catalog
      .findSeries(
        query =
          SeriesCatalogQuery(
            deleted = null,
            condition =
              CatalogSearchCondition.Predicate(
                CatalogSearchField.COLLECTION_ID,
                CatalogSearchOperator.IS,
                id.value,
              ),
          ),
        access = access,
        page = CatalogPageRequest(unpaged = true),
      ).content
      .associateBy { it.series.id }
  return seriesIds.mapNotNull(visibleById::get)
}

private fun ReadList.visibleBooksInOrder(
  access: CatalogAccess,
  catalog: CatalogReadRepository,
): List<CatalogBook> {
  val visibleById =
    catalog
      .findBooks(
        query =
          BookCatalogQuery(
            deleted = null,
            condition =
              CatalogSearchCondition.Predicate(
                CatalogSearchField.READ_LIST_ID,
                CatalogSearchOperator.IS,
                id.value,
              ),
          ),
        access = access,
        page = CatalogPageRequest(unpaged = true),
      ).content
      .associateBy { it.book.id }
  return bookIds.mapNotNull(visibleById::get)
}

private suspend fun ApplicationCall.requireVisibleSeriesMembers(
  ids: List<SeriesId>,
  access: CatalogAccess,
  catalog: CatalogReadRepository,
): Boolean {
  if (ids.all { catalog.findSeriesByIdOrNull(it, access) != null }) return true
  respond(
    HttpStatusCode.BadRequest,
    XoboroApiError(
      "invalid_request",
      "Collection contains an unknown or invisible series",
    ),
  )
  return false
}

private suspend fun ApplicationCall.requireVisibleBookMembers(
  ids: List<BookId>,
  access: CatalogAccess,
  catalog: CatalogReadRepository,
): Boolean {
  if (ids.all { catalog.findBookByIdOrNull(it, access) != null }) return true
  respond(
    HttpStatusCode.BadRequest,
    XoboroApiError(
      "invalid_request",
      "Read list contains an unknown or invisible media item",
    ),
  )
  return false
}

private data class NativeOrganizationPage(
  val page: Int,
  val size: Int,
)

private fun ApplicationCall.nativeOrganizationPage(): NativeOrganizationPage {
  if (request.queryParameters.contains("sort")) {
    throw XoboroInvalidQueryException("sort is not supported for ordered members")
  }
  val page = optionalOrganizationInteger("page") ?: 0
  val size = optionalOrganizationInteger("size") ?: DEFAULT_PAGE_SIZE
  if (page < 0) throw XoboroInvalidQueryException("page must not be negative")
  if (size !in 1..MAXIMUM_PAGE_SIZE) {
    throw XoboroInvalidQueryException("size must be between 1 and $MAXIMUM_PAGE_SIZE")
  }
  return NativeOrganizationPage(page, size)
}

private fun ApplicationCall.optionalOrganizationInteger(name: String): Int? =
  request.queryParameters[name]?.let {
    it.toIntOrNull() ?: throw XoboroInvalidQueryException("$name must be an integer")
  }

private fun <T, R> List<T>.toNativePage(
  request: NativeOrganizationPage,
  mapper: (T) -> R,
): XoboroPageResponse<R> {
  val totalPages =
    if (isEmpty()) {
      0
    } else {
      ((size.toLong() - 1) / request.size + 1)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    }
  val offset =
    (request.page.toLong() * request.size)
      .coerceAtMost(size.toLong())
      .toInt()
  return XoboroPageResponse(
    items = drop(offset).take(request.size).map(mapper),
    page = request.page,
    size = request.size,
    totalItems = size.toLong(),
    totalPages = totalPages,
    hasPrevious = request.page > 0,
    hasNext = request.page + 1 < totalPages,
  )
}

private fun SeriesCollection.toNativeResponse(visibleMemberCount: Int): XoboroCollectionResponse =
  XoboroCollectionResponse(
    id = id.value,
    name = name,
    ordered = ordered,
    memberCount = visibleMemberCount,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

private fun ReadList.toNativeResponse(visibleMemberCount: Int): XoboroReadListResponse =
  XoboroReadListResponse(
    id = id.value,
    name = name,
    summary = summary,
    ordered = ordered,
    memberCount = visibleMemberCount,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

private suspend fun ApplicationCall.respondCollectionAdministrationForbidden() {
  respondNativeError(
    HttpStatusCode.Forbidden,
    "collection_administration_forbidden",
    "Collection administration requires an administrator",
  )
}

private suspend fun ApplicationCall.respondReadListAdministrationForbidden() {
  respondNativeError(
    HttpStatusCode.Forbidden,
    "read_list_administration_forbidden",
    "Read-list administration requires an administrator",
  )
}

private suspend fun ApplicationCall.respondCollectionNotFound() {
  respondNativeNotFound("collection_not_found", "Collection was not found")
}

private suspend fun ApplicationCall.respondReadListNotFound() {
  respondNativeNotFound("read_list_not_found", "Read list was not found")
}

private suspend fun ApplicationCall.respondCollectionValidationFailure(
  failure: IllegalArgumentException,
) {
  when (failure.message) {
    "Collection name already exists" ->
      respond(
        HttpStatusCode.Conflict,
        XoboroApiError("collection_name_conflict", requireNotNull(failure.message)),
      )
    "Collection not found" -> respondCollectionNotFound()
    else ->
      respond(
        HttpStatusCode.BadRequest,
        XoboroApiError(
          "invalid_request",
          failure.message ?: "Collection request is invalid",
        ),
      )
  }
}

private suspend fun ApplicationCall.respondReadListValidationFailure(
  failure: IllegalArgumentException,
) {
  when (failure.message) {
    "Read-list name already exists" ->
      respond(
        HttpStatusCode.Conflict,
        XoboroApiError("read_list_name_conflict", requireNotNull(failure.message)),
      )
    "Read-list not found" -> respondReadListNotFound()
    else ->
      respond(
        HttpStatusCode.BadRequest,
        XoboroApiError(
          "invalid_request",
          failure.message ?: "Read-list request is invalid",
        ),
      )
  }
}
