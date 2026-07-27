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
import io.xoboro.core.application.AnnouncementLifecycle
import io.xoboro.core.domain.AnnouncementAuthor
import io.xoboro.core.domain.AnnouncementFeed
import io.xoboro.core.domain.AnnouncementItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.komgaAnnouncementRoutes(announcements: AnnouncementLifecycle) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    get("/api/v1/announcements") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      if (!principal.user.isAdmin) {
        call.respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        return@get
      }
      val feed = announcements.findForUser(principal.user.id)
      if (feed == null) {
        call.respondError(HttpStatusCode.NotFound, HttpStatusCode.NotFound.description)
        return@get
      }
      call.respond(feed.toDto())
    }
    put("/api/v1/announcements") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      if (!principal.user.isAdmin) {
        call.respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        return@put
      }
      announcements.markRead(principal.user.id, call.receive<Set<String>>())
      call.respond(HttpStatusCode.NoContent)
    }
  }
}

@Serializable
data class JsonFeedDto(
  val version: String,
  val title: String,
  @SerialName("home_page_url")
  val homePageUrl: String? = null,
  val description: String? = null,
  val items: List<AnnouncementItemDto> = emptyList(),
)

@Serializable
data class AnnouncementItemDto(
  val id: String,
  val url: String? = null,
  val title: String? = null,
  val summary: String? = null,
  @SerialName("content_html")
  val contentHtml: String? = null,
  @SerialName("date_modified")
  val dateModified: String? = null,
  val author: AnnouncementAuthorDto? = null,
  val tags: Set<String> = emptySet(),
  @SerialName("_komga")
  val komgaExtension: KomgaExtensionDto? = null,
)

@Serializable
data class AnnouncementAuthorDto(
  val name: String? = null,
  val url: String? = null,
)

@Serializable
data class KomgaExtensionDto(
  val read: Boolean,
)

private fun AnnouncementFeed.toDto(): JsonFeedDto =
  JsonFeedDto(
    version = version,
    title = title,
    homePageUrl = homePageUrl,
    description = description,
    items = items.map(AnnouncementItem::toDto),
  )

private fun AnnouncementItem.toDto(): AnnouncementItemDto =
  AnnouncementItemDto(
    id = id,
    url = url,
    title = title,
    summary = summary,
    contentHtml = contentHtml,
    dateModified = dateModified,
    author = author?.toDto(),
    tags = tags,
    komgaExtension = read?.let(::KomgaExtensionDto),
  )

private fun AnnouncementAuthor.toDto(): AnnouncementAuthorDto =
  AnnouncementAuthorDto(name = name, url = url)
