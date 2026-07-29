package io.xoboro.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.util.date.GMTDate
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaKind
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
        get("/{mediaItemId}/resources/{resource...}") {
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
          if (item.book.mediaKind != MediaKind.EPUB) {
            call.respondNativeNotFound("resource_not_found", "Resource was not found")
            return@get
          }
          val resourcePath = call.parameters.getAll("resource")?.joinToString("/").orEmpty()
          if (resourcePath.isBlank()) {
            call.respondNativeNotFound("resource_not_found", "Resource was not found")
            return@get
          }
          val stream =
            try {
              content.openResource(bookId, resourcePath)
            } catch (_: IllegalArgumentException) {
              call.respondNativeNotFound("resource_not_found", "Resource was not found")
              return@get
            }
          if (stream == null) {
            call.respondNativeNotFound("resource_not_found", "Resource was not found")
            return@get
          }
          try {
            call.response.header(
              "Content-Security-Policy",
              "script-src 'none'; object-src 'none';",
            )
            call.respondNativeCachedContent(stream, item.media!!.updatedAtMillis)
          } finally {
            stream.close()
          }
        }
        get("/{mediaItemId}/file") {
          val user = call.nativeUser()
          if (UserRole.FILE_DOWNLOAD !in user.roles) {
            call.respondFileDownloadForbidden()
            return@get
          }
          val bookId = BookId(call.requiredParameter("mediaItemId"))
          val item = catalog.findBookByIdOrNull(bookId, user.nativeCatalogAccess())
          if (item == null) {
            call.respondNativeNotFound("media_item_not_found", "Media item was not found")
            return@get
          }
          val totalLength = item.book.fileSize
          val entityTag = "W/\"$totalLength-${item.book.fileModifiedAtMillis}\""
          val lastModified =
            GMTDate(item.book.fileModifiedAtMillis.toHttpSecond()).toHttpDate()
          call.response.header(HttpHeaders.ETag, entityTag)
          call.response.header(HttpHeaders.LastModified, lastModified)
          if (call.isNativeDownloadNotModified(entityTag)) {
            call.respond(HttpStatusCode.NotModified)
            return@get
          }
          call.response.header(HttpHeaders.AcceptRanges, "bytes")
          val opened =
            try {
              content.openBook(bookId)
            } catch (_: IllegalArgumentException) {
              call.respondNativeNotFound("media_item_not_found", "Media item was not found")
              return@get
            }
          if (opened == null) {
            call.respondNativeNotFound("media_item_not_found", "Media item was not found")
            return@get
          }
          try {
            call.response.header(
              HttpHeaders.ContentDisposition,
              nativeDownloadContentDisposition(item.book.name),
            )
            // The validator above is derived from catalog metadata so a conditional request can
            // short-circuit without opening the file. Body length and range arithmetic must use
            // the opened stream instead: if the file was replaced without a rescan the catalog
            // size is stale, and advertising it would emit a Content-Length or Content-Range that
            // does not match the bytes actually written.
            val bodyLength = opened.contentLength ?: totalLength
            when (
              val range =
                resolveNativeDownloadRange(
                  rangeHeader = call.request.headers[HttpHeaders.Range],
                  ifRangeHeader = call.request.headers[HttpHeaders.IfRange],
                  entityTag = entityTag,
                  lastModifiedMillis = item.book.fileModifiedAtMillis,
                  totalLength = bodyLength,
                )
            ) {
              NativeDownloadRange.Full ->
                call.respondOutputStream(
                  contentType = opened.mediaType.toNativeContentType(),
                  status = HttpStatusCode.OK,
                  contentLength = bodyLength,
                ) {
                  opened.writeNativeDownload(this, bodyLength)
                }
              is NativeDownloadRange.Partial -> {
                call.response.header(
                  HttpHeaders.ContentRange,
                  "bytes ${range.first}-${range.last}/$bodyLength",
                )
                opened.skipNativeDownloadBytes(range.first)
                call.respondOutputStream(
                  contentType = opened.mediaType.toNativeContentType(),
                  status = HttpStatusCode.PartialContent,
                  contentLength = range.length,
                ) {
                  opened.writeNativeDownload(this, range.length)
                }
              }
              NativeDownloadRange.Unsatisfiable -> {
                call.response.header(HttpHeaders.ContentRange, "bytes */$bodyLength")
                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
              }
            }
          } finally {
            opened.close()
          }
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

private suspend fun ApplicationCall.respondFileDownloadForbidden() {
  respond(
    HttpStatusCode.Forbidden,
    XoboroApiError(
      "file_download_forbidden",
      "File download permission is required",
    ),
  )
}

private val ALLOWED_PAGE_FORMATS = setOf("jpeg", "png", "source")
private const val MAXIMUM_PAGE_DIMENSION = 4_096
