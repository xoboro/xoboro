package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.User

fun Route.xoboroNativeCatalogRoutes(
  libraries: LibraryAdministrationLifecycle,
  catalog: CatalogReadRepository,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      route("/libraries") {
        get {
          val user = call.nativeUser()
          call.respond(
            libraries
              .findAll()
              .asSequence()
              .filter { user.canAccessLibrary(it.id) }
              .sortedBy { it.name.lowercase() }
              .map { it.toNativeResponse(user) }
              .toList(),
          )
        }
        get("/{libraryId}") {
          val user = call.nativeUser()
          val library =
            libraries
              .findByIdOrNull(LibraryId(call.requiredParameter("libraryId")))
              ?.takeIf { user.canAccessLibrary(it.id) }
          if (library == null) {
            call.respondNativeNotFound("library_not_found", "Library was not found")
          } else {
            call.respond(library.toNativeResponse(user))
          }
        }
      }
      route("/series") {
        get {
          val user = call.nativeUser()
          call.respond(
            catalog
              .findSeries(
                query = call.nativeSeriesQuery(),
                access = user.nativeCatalogAccess(),
                page = call.nativeSeriesPageRequest(),
              ).toNativeSeriesPage(),
          )
        }
        get("/{seriesId}") {
          val user = call.nativeUser()
          val series =
            catalog.findSeriesByIdOrNull(
              SeriesId(call.requiredParameter("seriesId")),
              user.nativeCatalogAccess(),
            )
          if (series == null) {
            call.respondNativeNotFound("series_not_found", "Series was not found")
          } else {
            call.respond(series.toNativeResponse())
          }
        }
        get("/{seriesId}/media-items") {
          val user = call.nativeUser()
          val seriesId = SeriesId(call.requiredParameter("seriesId"))
          val access = user.nativeCatalogAccess()
          if (catalog.findSeriesByIdOrNull(seriesId, access) == null) {
            call.respondNativeNotFound("series_not_found", "Series was not found")
            return@get
          }
          call.respond(
            catalog
              .findBooks(
                query = call.nativeMediaItemQuery(seriesId),
                access = access,
                page = call.nativeSeriesMediaItemPageRequest(),
              ).toNativeMediaItemPage(),
          )
        }
      }
      route("/media-items") {
        get {
          val user = call.nativeUser()
          call.respond(
            catalog
              .findBooks(
                query = call.nativeMediaItemQuery(),
                access = user.nativeCatalogAccess(),
                page = call.nativeMediaItemPageRequest(),
              ).toNativeMediaItemPage(),
          )
        }
        get("/{mediaItemId}") {
          val user = call.nativeUser()
          val item =
            catalog.findBookByIdOrNull(
              BookId(call.requiredParameter("mediaItemId")),
              user.nativeCatalogAccess(),
            )
          if (item == null) {
            call.respondNativeNotFound("media_item_not_found", "Media item was not found")
          } else {
            call.respond(item.toNativeResponse())
          }
        }
        get("/{mediaItemId}/previous") {
          call.respondAdjacentMediaItem(catalog, previous = true)
        }
        get("/{mediaItemId}/next") {
          call.respondAdjacentMediaItem(catalog, previous = false)
        }
      }
    }
  }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondAdjacentMediaItem(
  catalog: CatalogReadRepository,
  previous: Boolean,
) {
  val id = BookId(requiredParameter("mediaItemId"))
  val access = nativeUser().nativeCatalogAccess()
  val item =
    if (previous) {
      catalog.findPreviousBookOrNull(id, access)
    } else {
      catalog.findNextBookOrNull(id, access)
    }
  if (item == null) {
    respondNativeNotFound("media_item_not_found", "Adjacent media item was not found")
  } else {
    respond(item.toNativeResponse())
  }
}

internal fun io.ktor.server.application.ApplicationCall.nativeUser(): User =
  requireNotNull(principal<XoboroPrincipal>()).user

internal fun User.nativeCatalogAccess(): CatalogAccess =
  CatalogAccess(
    userId = id,
    libraryIds = if (canAccessAllLibraries()) null else sharedLibraryIds,
    restrictions = restrictions,
  )

internal fun io.ktor.server.application.ApplicationCall.requiredParameter(name: String): String =
  requireNotNull(parameters[name]).takeIf(String::isNotBlank)
    ?: throw XoboroInvalidQueryException("$name must not be blank")

internal suspend fun io.ktor.server.application.ApplicationCall.respondNativeNotFound(
  code: String,
  message: String,
) {
  respondNativeError(HttpStatusCode.NotFound, code, message)
}
