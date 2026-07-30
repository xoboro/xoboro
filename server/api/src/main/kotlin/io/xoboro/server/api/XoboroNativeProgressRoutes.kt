package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.ReadProgressUpdate
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.BookId
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
              locatorJson = Json.encodeToString(JsonObject.serializer(), request.locator),
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
    }
  }
}
