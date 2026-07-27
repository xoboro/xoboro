package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.xoboro.core.application.CatalogMaintenanceRequester
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId

fun Route.komgaCatalogMaintenanceRoutes(maintenance: CatalogMaintenanceRequester) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    post("/api/v1/books/{bookId}/analyze") {
      if (!call.requireCatalogMaintenanceAdministrator()) return@post
      if (maintenance.analyzeBook(call.bookId())) {
        call.respond(HttpStatusCode.Accepted)
      } else {
        call.respond(HttpStatusCode.NotFound)
      }
    }
    post("/api/v1/books/{bookId}/metadata/refresh") {
      if (!call.requireCatalogMaintenanceAdministrator()) return@post
      if (maintenance.refreshBookMetadata(call.bookId())) {
        call.respond(HttpStatusCode.Accepted)
      } else {
        call.respond(HttpStatusCode.NotFound)
      }
    }
    post("/api/v1/series/{seriesId}/analyze") {
      if (!call.requireCatalogMaintenanceAdministrator()) return@post
      maintenance.analyzeSeries(call.seriesId())
      call.respond(HttpStatusCode.Accepted)
    }
    post("/api/v1/series/{seriesId}/metadata/refresh") {
      if (!call.requireCatalogMaintenanceAdministrator()) return@post
      maintenance.refreshSeriesMetadata(call.seriesId())
      call.respond(HttpStatusCode.Accepted)
    }
    delete("/api/v1/tasks") {
      if (!call.requireCatalogMaintenanceAdministrator()) return@delete
      call.respond(maintenance.clearUnclaimedTasks())
    }
  }
}

private fun ApplicationCall.bookId(): BookId =
  BookId(requireNotNull(parameters["bookId"]))

private fun ApplicationCall.seriesId(): SeriesId =
  SeriesId(requireNotNull(parameters["seriesId"]))

private suspend fun ApplicationCall.requireCatalogMaintenanceAdministrator(): Boolean {
  if (requireNotNull(principal<KomgaPrincipal>()).user.isAdmin) return true
  respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
  return false
}
