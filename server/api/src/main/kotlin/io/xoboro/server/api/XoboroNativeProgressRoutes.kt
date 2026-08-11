package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.ReadProgressUpdate
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

fun Route.xoboroNativeProgressRoutes(
  catalog: CatalogReadRepository,
  progress: ReadProgressLifecycle,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      put("/media-items/{mediaItemId}/progress") {
        val user = call.nativeUser()
        val id = BookId(call.requiredParameter("mediaItemId"))
        if (catalog.findBookByIdOrNull(id, user.catalogAccess()) == null) {
          call.respondNativeNotFound("media_item_not_found", "Media item was not found")
          return@put
        }
        val request = call.receive<XoboroMediaProgressRequest>()
        val result =
          try {
            progress.updateBookProgression(
              bookId = id,
              userId = user.id,
              page = request.page,
              modifiedAtMillis = request.modifiedAtMillis,
              deviceId = request.deviceId,
              deviceName = request.deviceName,
              // Absent stays absent rather than becoming `{}`: an empty object would claim a
              // locator was recorded and carried no fields, which is a different statement from
              // this reader not having one.
              locatorJson =
                request.locator?.let { Json.encodeToString(JsonObject.serializer(), it) },
            )
          } catch (_: IllegalArgumentException) {
            call.respond(
              HttpStatusCode.BadRequest,
              XoboroApiError("invalid_request", "Read progress request is invalid"),
            )
            return@put
          }
        when (result) {
          is ReadProgressUpdate.Applied -> call.respond(result.progress.toNativeProgressResponse())
          is ReadProgressUpdate.Stale ->
            call.respond(
              HttpStatusCode.Conflict,
              XoboroApiError(
                "stale_progress",
                "Read progress is not newer than the stored progress",
              ),
            )
          ReadProgressUpdate.MediaItemNotFound ->
            call.respondNativeNotFound("media_item_not_found", "Media item was not found")
        }
      }

      /**
       * Clears this reader's progress for one item.
       *
       * The surface offered `PUT` and nothing else, so "unread" was a state the server could
       * hold and no client could ask for: a chapter opened by accident could only be undone by
       * reading it to the end.
       *
       * Idempotent. A reader pressing "unread" on a chapter they never started has asked for
       * the state it is already in, and answering `404` would report that as a failure - so the
       * lookup that can 404 is the *visibility* check above, not the presence of a stored row.
       */
      delete("/media-items/{mediaItemId}/progress") {
        val user = call.nativeUser()
        val id = BookId(call.requiredParameter("mediaItemId"))
        if (catalog.findBookByIdOrNull(id, user.catalogAccess()) == null) {
          call.respondNativeNotFound("media_item_not_found", "Media item was not found")
          return@delete
        }
        progress.deleteBook(bookId = id, userId = user.id)
        call.respond(HttpStatusCode.NoContent)
      }

      /**
       * Marks every item in a series read, or clears every one of them.
       *
       * Both directions already existed in the domain and were reachable only through the
       * Tachiyomi compatibility routes, so a reader using this server's own client could mark
       * one chapter at a time and nothing else.
       *
       * Authorization is the series lookup, which answers `404` for a series this caller
       * cannot see - and it runs before either write, so an unauthorized call cannot change a
       * single row.
       */
      put("/series/{seriesId}/progress") {
        val user = call.nativeUser()
        val id = SeriesId(call.requiredParameter("seriesId"))
        if (catalog.findSeriesByIdOrNull(id, user.catalogAccess()) == null) {
          call.respondNativeNotFound("series_not_found", "Series was not found")
          return@put
        }
        // The boolean is honoured rather than discarded. The visibility check above answers
        // for what this caller may see; this answers whether the series is still there to write
        // to, and reporting "done" for a series the domain could not find would be a lie the
        // client has no way to notice.
        if (!progress.markSeriesCompleted(seriesId = id, userId = user.id)) {
          call.respondNativeNotFound("series_not_found", "Series was not found")
          return@put
        }
        call.respond(HttpStatusCode.NoContent)
      }

      delete("/series/{seriesId}/progress") {
        val user = call.nativeUser()
        val id = SeriesId(call.requiredParameter("seriesId"))
        if (catalog.findSeriesByIdOrNull(id, user.catalogAccess()) == null) {
          call.respondNativeNotFound("series_not_found", "Series was not found")
          return@delete
        }
        if (!progress.markSeriesUnread(seriesId = id, userId = user.id)) {
          call.respondNativeNotFound("series_not_found", "Series was not found")
          return@delete
        }
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}
