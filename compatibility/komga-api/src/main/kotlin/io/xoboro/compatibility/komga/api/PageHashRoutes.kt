package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.PageHashLifecycle
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.PageHashMatch
import io.xoboro.core.domain.UnknownPageHash
import java.time.Instant
import kotlinx.serialization.Serializable

fun Route.komgaPageHashRoutes(
  hashes: PageHashRepository,
  lifecycle: PageHashLifecycle,
  content: BookContentAccess,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    route("/api/v1/page-hashes") {
      get {
        if (!call.requirePageHashAdministrator()) return@get
        val requestedActions = call.request.queryParameters.getAll("action").orEmpty()
        val actions =
          requestedActions
            .mapNotNull { runCatching { PageHashAction.valueOf(it) }.getOrNull() }
            .toSet()
        if (actions.size != requestedActions.distinct().size) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid page hash action"))
          return@get
        }
        val page = hashes.findKnown(actions, call.catalogPageRequest())
        call.respond(page.toPageDto(page.content.map(KnownPageHash::toDto)))
      }
      put {
        if (!call.requirePageHashAdministrator()) return@put
        val request = call.receive<PageHashCreationDto>()
        val result =
          runCatching {
            lifecycle.markKnown(request.hash, request.size, request.action)
          }.getOrElse { failure ->
            call.respond(
              HttpStatusCode.BadRequest,
              mapOf("error" to (failure.message ?: "Invalid page hash")),
            )
            return@put
          }
        check(result.hash == request.hash)
        call.respond(HttpStatusCode.Accepted)
      }
      get("/unknown") {
        if (!call.requirePageHashAdministrator()) return@get
        val page = hashes.findUnknown(call.catalogPageRequest())
        call.respond(page.toPageDto(page.content.map(UnknownPageHash::toDto)))
      }
      get("/unknown/{pageHash}/thumbnail") {
        if (!call.requirePageHashAdministrator()) return@get
        val resize = call.request.queryParameters["resize"]?.toIntOrNull()
        if (resize != null && resize <= 0) {
          call.respond(HttpStatusCode.BadRequest)
          return@get
        }
        call.respondPageHashThumbnail(
          hashes = hashes,
          content = content,
          maximumDimension = resize,
        )
      }
      get("/{pageHash}/thumbnail") {
        if (!call.requirePageHashAdministrator()) return@get
        call.respondPageHashThumbnail(
          hashes = hashes,
          content = content,
          maximumDimension = KNOWN_HASH_THUMBNAIL_SIZE,
        )
      }
      get("/{pageHash}") {
        if (!call.requirePageHashAdministrator()) return@get
        val page =
          hashes.findMatches(
            requireNotNull(call.parameters["pageHash"]),
            call.catalogPageRequest(),
          )
        call.respond(page.toPageDto(page.content.map(PageHashMatch::toDto)))
      }
    }
  }
}

@Serializable
data class PageHashCreationDto(
  val hash: String,
  val size: Long? = null,
  val action: PageHashAction,
)

@Serializable
data class PageHashKnownDto(
  val hash: String,
  val size: Long? = null,
  val action: PageHashAction,
  val deleteCount: Int,
  val matchCount: Int,
  val created: String,
  val lastModified: String,
)

@Serializable
data class PageHashUnknownDto(
  val hash: String,
  val size: Long? = null,
  val matchCount: Int,
)

@Serializable
data class PageHashMatchDto(
  val bookId: String,
  val url: String,
  val pageNumber: Int,
  val fileName: String,
  val fileSize: Long,
  val mediaType: String,
)

private fun KnownPageHash.toDto(): PageHashKnownDto =
  PageHashKnownDto(
    hash = hash,
    size = size,
    action = action,
    deleteCount = deleteCount,
    matchCount = matchCount,
    created = Instant.ofEpochMilli(createdAtMillis).toString(),
    lastModified = Instant.ofEpochMilli(updatedAtMillis).toString(),
  )

private fun UnknownPageHash.toDto(): PageHashUnknownDto =
  PageHashUnknownDto(hash = hash, size = size, matchCount = matchCount)

private fun PageHashMatch.toDto(): PageHashMatchDto =
  PageHashMatchDto(
    bookId = mediaItemId.value,
    url = sourceItemId,
    pageNumber = pageNumber,
    fileName = fileName,
    fileSize = fileSize,
    mediaType = mediaType,
  )

private suspend fun ApplicationCall.respondPageHashThumbnail(
  hashes: PageHashRepository,
  content: BookContentAccess,
  maximumDimension: Int?,
) {
  val hash = requireNotNull(parameters["pageHash"])
  val match =
    hashes
      .findMatches(hash, CatalogPageRequest(size = 1))
      .content
      .firstOrNull()
  if (match == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val opened =
    runCatching {
      content.openPage(
        match.mediaItemId,
        match.pageNumber,
        PageImageRequest(
          format = PageImageFormat.JPEG,
          maximumDimension = maximumDimension,
        ),
      )
    }.getOrNull()
  if (opened == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  try {
    val body = opened.readKomgaCachedBody()
    if (respondNotModified(body, lastModifiedMillis = null)) return
    respondBytes(body.bytes, ContentType.Image.JPEG)
  } finally {
    opened.close()
  }
}

private suspend fun ApplicationCall.requirePageHashAdministrator(): Boolean {
  if (requireNotNull(principal<KomgaPrincipal>()).user.isAdmin) return true
  respond(HttpStatusCode.Forbidden)
  return false
}

private const val KNOWN_HASH_THUMBNAIL_SIZE: Int = 1_000
