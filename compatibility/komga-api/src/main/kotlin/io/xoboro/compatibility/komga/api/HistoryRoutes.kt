package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.domain.HistoricalEvent
import io.xoboro.core.domain.HistoricalEventPageRequest
import io.xoboro.core.domain.HistoricalEventRepository
import io.xoboro.core.domain.HistoricalEventSortField
import io.xoboro.core.domain.SortDirection
import io.xoboro.core.domain.UserRole
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

fun Route.komgaHistoryRoutes(history: HistoricalEventRepository) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    get("/api/v1/history") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      if (UserRole.ADMIN !in principal.user.roles) {
        call.respond(HttpStatusCode.Forbidden)
        return@get
      }
      val page = call.catalogPageRequest()
      val requestedSort = page.sorts.firstOrNull()
      val result =
        history.findAll(
          HistoricalEventPageRequest(
            page = page.page,
            size = page.size,
            sortField =
              requestedSort?.property?.toHistorySortField()
                ?: HistoricalEventSortField.TIMESTAMP,
            direction =
              if (requestedSort?.direction == io.xoboro.core.application.CatalogSortDirection.ASC) {
                SortDirection.ASCENDING
              } else {
                SortDirection.DESCENDING
              },
            unpaged = page.unpaged,
          ),
        )
      call.respond(
        CatalogPage(
          content = result.content,
          page = result.request.page,
          size = result.request.size,
          totalElements = result.totalElements,
          unpaged = result.request.unpaged,
        ).toPageDto(result.content.map(HistoricalEvent::toDto)),
      )
    }
  }
}

@Serializable
data class HistoricalEventDto(
  val id: String,
  val type: String,
  val timestamp: String,
  val bookId: String? = null,
  val seriesId: String? = null,
  val properties: Map<String, String>,
)

private fun HistoricalEvent.toDto(): HistoricalEventDto =
  HistoricalEventDto(
    id = id,
    type = type,
    timestamp =
      LocalDateTime
        .ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneId.systemDefault())
        .toString(),
    bookId = bookId?.value,
    seriesId = seriesId?.value,
    properties = properties,
  )

private fun String.toHistorySortField(): HistoricalEventSortField =
  when (this) {
    "type" -> HistoricalEventSortField.TYPE
    "bookId" -> HistoricalEventSortField.BOOK_ID
    "seriesId" -> HistoricalEventSortField.SERIES_ID
    else -> HistoricalEventSortField.TIMESTAMP
  }
