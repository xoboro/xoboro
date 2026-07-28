package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.UserRole

fun Route.xoboroNativeDeliveryRoutes(
  catalog: CatalogReadRepository,
  content: BookContentAccess,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      route("/media-items") {
        get("/{mediaItemId}/pages") {
          val user = call.nativeUser()
          if (UserRole.PAGE_STREAMING !in user.roles) {
            call.respondPageStreamingForbidden()
            return@get
          }
          val item =
            catalog.findBookByIdOrNull(
              BookId(call.requiredParameter("mediaItemId")),
              user.nativeCatalogAccess(),
            )
          if (item == null) {
            call.respondNativeNotFound("media_item_not_found", "Media item was not found")
            return@get
          }
          call.respond(
            item.media
              ?.pages
              .orEmpty()
              .map { page ->
                XoboroMediaPageResponse(
                  number = page.number,
                  mediaType = page.mediaType,
                  width = page.dimension?.width,
                  height = page.dimension?.height,
                  sizeBytes = page.fileSize,
                )
              },
          )
        }
        get("/{mediaItemId}/pages/{pageNumber}") {
          val user = call.nativeUser()
          if (UserRole.PAGE_STREAMING !in user.roles) {
            call.respondPageStreamingForbidden()
            return@get
          }
          val bookId = BookId(call.requiredParameter("mediaItemId"))
          val item = catalog.findBookByIdOrNull(bookId, user.nativeCatalogAccess())
          if (item == null) {
            call.respondNativeNotFound("media_item_not_found", "Media item was not found")
            return@get
          }
          val pageNumber =
            call.requiredParameter("pageNumber").toIntOrNull()
              ?: throw XoboroInvalidQueryException("pageNumber must be an integer")
          val media = item.media
          if (media == null || media.status != MediaStatus.READY) {
            call.respond(
              HttpStatusCode.Conflict,
              XoboroApiError("media_not_ready", "Media item is not ready"),
            )
            return@get
          }
          if (pageNumber !in 1..media.pageCount) {
            call.respondNativeNotFound("page_not_found", "Page was not found")
            return@get
          }
          val request = call.nativePageImageRequest()
          val stream =
            try {
              content.openPage(bookId, pageNumber, request)
            } catch (_: IllegalArgumentException) {
              call.respond(
                HttpStatusCode.Conflict,
                XoboroApiError("page_not_decodable", "Page could not be decoded"),
              )
              return@get
            }
          if (stream == null) {
            call.respondNativeNotFound("page_not_found", "Page was not found")
            return@get
          }
          try {
            call.respondNativeCachedContent(stream, media.updatedAtMillis)
          } finally {
            stream.close()
          }
        }
        get("/{mediaItemId}/resources") {
          val user = call.nativeUser()
          if (UserRole.PAGE_STREAMING !in user.roles) {
            call.respondPageStreamingForbidden()
            return@get
          }
          val item =
            catalog.findBookByIdOrNull(
              BookId(call.requiredParameter("mediaItemId")),
              user.nativeCatalogAccess(),
            )
          if (item == null) {
            call.respondNativeNotFound("media_item_not_found", "Media item was not found")
            return@get
          }
          call.respond(
            item.media
              ?.files
              .orEmpty()
              .filter { file -> file.kind != MediaFileKind.GENERAL }
              .map { file ->
                XoboroResourceResponse(
                  path = file.fileName,
                  mediaType = file.mediaType,
                  sizeBytes = file.fileSize,
                  kind = file.kind.name,
                )
              },
          )
        }
      }
    }
  }
}

private fun ApplicationCall.nativePageImageRequest(): PageImageRequest {
  val format = request.queryParameters["format"]?.lowercase()
  if (format != null && format !in ALLOWED_PAGE_FORMATS) {
    throw XoboroInvalidQueryException("format must be jpeg, png, or source")
  }
  val maximumDimension =
    request.queryParameters["maxDimension"]?.let { value ->
      val parsed = value.toLongOrNull()
      if (parsed == null || parsed <= 0) {
        throw XoboroInvalidQueryException("maxDimension must be a positive integer")
      }
      minOf(parsed, MAXIMUM_PAGE_DIMENSION.toLong()).toInt()
    }
  if (format == "source" && maximumDimension != null) {
    throw XoboroInvalidQueryException("format=source cannot be combined with maxDimension")
  }
  return when (format) {
    null -> PageImageRequest(maximumDimension = maximumDimension)
    "jpeg" ->
      PageImageRequest(
        format = PageImageFormat.JPEG,
        maximumDimension = maximumDimension,
      )
    "png" ->
      PageImageRequest(
        format = PageImageFormat.PNG,
        maximumDimension = maximumDimension,
      )
    "source" -> PageImageRequest(raw = true)
    else -> error("Validated page format was not handled")
  }
}

private suspend fun ApplicationCall.respondPageStreamingForbidden() {
  respond(
    HttpStatusCode.Forbidden,
    XoboroApiError(
      "page_streaming_forbidden",
      "Page streaming permission is required",
    ),
  )
}

private val ALLOWED_PAGE_FORMATS = setOf("jpeg", "png", "source")
private const val MAXIMUM_PAGE_DIMENSION = 4_096
