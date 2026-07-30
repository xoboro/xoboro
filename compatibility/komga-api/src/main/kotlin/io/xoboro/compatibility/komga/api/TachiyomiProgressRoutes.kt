package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.IndexedReadProgress
import io.xoboro.core.application.NumberedReadProgress
import io.xoboro.core.application.SequentialReadProgressLifecycle
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.SeriesId
import kotlinx.serialization.Serializable

fun Route.komgaTachiyomiProgressRoutes(progress: SequentialReadProgressLifecycle) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    get("/api/v1/readlists/{id}/read-progress/tachiyomi") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      val result =
        progress.findReadList(
          id = ReadListId(requireNotNull(call.parameters["id"])),
          access = principal.user.catalogAccess(),
          allowEmpty = principal.user.isAdmin,
        )
      if (result == null) call.respond(HttpStatusCode.NotFound) else call.respond(result.toDto())
    }
    put("/api/v1/readlists/{id}/read-progress/tachiyomi") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      val request = call.receive<TachiyomiReadProgressUpdateDto>()
      try {
        val updated =
          progress.updateReadList(
            id = ReadListId(requireNotNull(call.parameters["id"])),
            access = principal.user.catalogAccess(),
            userId = principal.user.id,
            lastBookRead = request.lastBookRead,
            allowEmpty = principal.user.isAdmin,
          )
        call.respond(if (updated) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
      } catch (failure: IllegalArgumentException) {
        call.respond(
          HttpStatusCode.BadRequest,
          mapOf("error" to (failure.message ?: "Invalid read progress")),
        )
      }
    }
    get("/api/v2/series/{seriesId}/read-progress/tachiyomi") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      val result =
        progress.findSeries(
          id = SeriesId(requireNotNull(call.parameters["seriesId"])),
          access = principal.user.catalogAccess(),
        )
      if (result == null) call.respond(HttpStatusCode.NotFound) else call.respond(result.toDto())
    }
    put("/api/v2/series/{seriesId}/read-progress/tachiyomi") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      val request = call.receive<TachiyomiReadProgressUpdateV2Dto>()
      try {
        val updated =
          progress.updateSeries(
            id = SeriesId(requireNotNull(call.parameters["seriesId"])),
            access = principal.user.catalogAccess(),
            userId = principal.user.id,
            lastBookNumberSortRead = request.lastBookNumberSortRead,
          )
        call.respond(if (updated) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
      } catch (failure: IllegalArgumentException) {
        call.respond(
          HttpStatusCode.BadRequest,
          mapOf("error" to (failure.message ?: "Invalid read progress")),
        )
      }
    }
  }
}

@Serializable
data class TachiyomiReadProgressDto(
  val booksCount: Int,
  val booksReadCount: Int,
  val booksUnreadCount: Int,
  val booksInProgressCount: Int,
  val lastReadContinuousIndex: Int,
)

@Serializable
data class TachiyomiReadProgressV2Dto(
  val booksCount: Int,
  val booksReadCount: Int,
  val booksUnreadCount: Int,
  val booksInProgressCount: Int,
  val lastReadContinuousNumberSort: Float,
  val maxNumberSort: Float,
)

@Serializable
data class TachiyomiReadProgressUpdateDto(
  val lastBookRead: Int,
)

@Serializable
data class TachiyomiReadProgressUpdateV2Dto(
  val lastBookNumberSortRead: Float,
)

private fun IndexedReadProgress.toDto(): TachiyomiReadProgressDto =
  TachiyomiReadProgressDto(
    booksCount,
    booksReadCount,
    booksUnreadCount,
    booksInProgressCount,
    lastReadContinuousIndex,
  )

private fun NumberedReadProgress.toDto(): TachiyomiReadProgressV2Dto =
  TachiyomiReadProgressV2Dto(
    booksCount,
    booksReadCount,
    booksUnreadCount,
    booksInProgressCount,
    lastReadContinuousNumberSort,
    maxNumberSort,
  )
