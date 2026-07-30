package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId
import kotlinx.serialization.Serializable

fun Route.komgaReadProgressRoutes(
  catalog: CatalogReadRepository,
  progress: ReadProgressLifecycle,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    route("/api/v1/books/{bookId}/read-progress") {
      patch {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val bookId = BookId(requireNotNull(call.parameters["bookId"]))
        val access = principal.user.catalogAccess()
        if (catalog.findBookByIdOrNull(bookId, access) == null) {
          call.respond(HttpStatusCode.NotFound)
          return@patch
        }
        val update = call.receive<ReadProgressUpdateDto>()
        try {
          progress.updateBook(
            bookId = bookId,
            userId = principal.user.id,
            page = update.page,
            completed = update.completed,
          )
          call.respond(HttpStatusCode.NoContent)
        } catch (failure: IllegalArgumentException) {
          call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to (failure.message ?: "Invalid read progress")),
          )
        }
      }
      delete {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val bookId = BookId(requireNotNull(call.parameters["bookId"]))
        if (catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess()) == null) {
          call.respond(HttpStatusCode.NotFound)
          return@delete
        }
        progress.deleteBook(bookId, principal.user.id)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    route("/api/v1/series/{seriesId}/read-progress") {
      post {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val seriesId = SeriesId(requireNotNull(call.parameters["seriesId"]))
        if (catalog.findSeriesByIdOrNull(seriesId, principal.user.catalogAccess()) == null) {
          call.respond(HttpStatusCode.NotFound)
          return@post
        }
        progress.markSeriesCompleted(seriesId, principal.user.id)
        call.respond(HttpStatusCode.NoContent)
      }
      delete {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val seriesId = SeriesId(requireNotNull(call.parameters["seriesId"]))
        if (catalog.findSeriesByIdOrNull(seriesId, principal.user.catalogAccess()) == null) {
          call.respond(HttpStatusCode.NotFound)
          return@delete
        }
        progress.markSeriesUnread(seriesId, principal.user.id)
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}

@Serializable
data class ReadProgressUpdateDto(
  val page: Int? = null,
  val completed: Boolean? = null,
)
