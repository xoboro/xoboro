package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.domain.ArtworkId
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId
import kotlinx.io.readByteArray

fun Route.xoboroNativeArtworkRoutes(
  catalog: CatalogReadRepository,
  artwork: ArtworkLifecycle,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      nativeArtworkOwnerRoutes(
        prefix = "/media-items/{mediaItemId}",
        kind = ArtworkOwnerKind.MEDIA_ITEM,
        catalog = catalog,
        artwork = artwork,
      )
      nativeArtworkOwnerRoutes(
        prefix = "/series/{seriesId}",
        kind = ArtworkOwnerKind.SERIES,
        catalog = catalog,
        artwork = artwork,
      )
    }
  }
}

private fun Route.nativeArtworkOwnerRoutes(
  prefix: String,
  kind: ArtworkOwnerKind,
  catalog: CatalogReadRepository,
  artwork: ArtworkLifecycle,
) {
  route(prefix) {
    get("/artwork") {
      val owner = call.authorizedArtworkOwnerOrNull(catalog, kind) ?: return@get
      val content = artwork.selectedContentOrNull(owner)
      if (content == null) {
        call.respondArtworkNotFound()
        return@get
      }
      val stream =
        InMemoryArtworkContentStream(
          bytes = content.bytes,
          mediaType = content.artwork.mediaType,
        )
      try {
        call.respondNativeCachedContent(stream, content.artwork.updatedAtMillis)
      } finally {
        stream.close()
      }
    }
    get("/artworks") {
      val owner = call.authorizedArtworkOwnerOrNull(catalog, kind) ?: return@get
      call.respond(artwork.findAll(owner).map { it.toNativeArtworkResponse() })
    }
    get("/artworks/{artworkId}") {
      val owner = call.authorizedArtworkOwnerOrNull(catalog, kind) ?: return@get
      val id = ArtworkId(call.requiredParameter("artworkId"))
      val content = artwork.contentOrNull(owner, id)
      if (content == null) {
        call.respondArtworkNotFound()
        return@get
      }
      val stream =
        InMemoryArtworkContentStream(
          bytes = content.bytes,
          mediaType = content.artwork.mediaType,
        )
      try {
        call.respondNativeCachedContent(stream, content.artwork.updatedAtMillis)
      } finally {
        stream.close()
      }
    }
    post("/artworks") {
      val user = call.nativeUser()
      if (!user.isAdmin) {
        call.respondArtworkAdministrationForbidden()
        return@post
      }
      val owner = call.authorizedArtworkOwnerOrNull(catalog, kind) ?: return@post
      val input = call.receiveNativeArtworkUpload() ?: return@post
      if (input.size > ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES) {
        call.respond(
          HttpStatusCode.PayloadTooLarge,
          XoboroApiError("artwork_too_large", "Artwork exceeds the upload size limit"),
        )
        return@post
      }
      val created =
        try {
          artwork.addUploaded(owner, input, selected = true)
        } catch (_: IllegalArgumentException) {
          call.respond(
            HttpStatusCode.UnsupportedMediaType,
            XoboroApiError("artwork_not_supported", "Artwork content is not supported"),
          )
          return@post
        }
      call.respond(HttpStatusCode.Created, created.toNativeArtworkResponse())
    }
    put("/artworks/{artworkId}/selected") {
      val user = call.nativeUser()
      if (!user.isAdmin) {
        call.respondArtworkAdministrationForbidden()
        return@put
      }
      val owner = call.authorizedArtworkOwnerOrNull(catalog, kind) ?: return@put
      val id = ArtworkId(call.requiredParameter("artworkId"))
      if (!artwork.markSelected(owner, id)) {
        call.respondArtworkNotFound()
        return@put
      }
      call.respond(HttpStatusCode.NoContent)
    }
    delete("/artworks/{artworkId}") {
      val user = call.nativeUser()
      if (!user.isAdmin) {
        call.respondArtworkAdministrationForbidden()
        return@delete
      }
      val owner = call.authorizedArtworkOwnerOrNull(catalog, kind) ?: return@delete
      val id = ArtworkId(call.requiredParameter("artworkId"))
      val deleted =
        try {
          artwork.deleteUploaded(owner, id)
        } catch (_: IllegalArgumentException) {
          call.respond(
            HttpStatusCode.Conflict,
            XoboroApiError(
              "artwork_delete_not_supported",
              "Only uploaded artwork can be deleted",
            ),
          )
          return@delete
        }
      if (!deleted) {
        call.respondArtworkNotFound()
        return@delete
      }
      call.respond(HttpStatusCode.NoContent)
    }
  }
}

internal suspend fun ApplicationCall.authorizedArtworkOwnerOrNull(
  catalog: CatalogReadRepository,
  kind: ArtworkOwnerKind,
): ArtworkOwner? {
  val user = nativeUser()
  val id =
    when (kind) {
      ArtworkOwnerKind.MEDIA_ITEM -> {
        val mediaItemId = requiredParameter("mediaItemId")
        if (
          catalog.findBookByIdOrNull(
            BookId(mediaItemId),
            user.nativeCatalogAccess(),
          ) == null
        ) {
          respondNativeNotFound("media_item_not_found", "Media item was not found")
          return null
        }
        mediaItemId
      }
      ArtworkOwnerKind.SERIES -> {
        val seriesId = requiredParameter("seriesId")
        if (
          catalog.findSeriesByIdOrNull(
            SeriesId(seriesId),
            user.nativeCatalogAccess(),
          ) == null
        ) {
          respondNativeNotFound("series_not_found", "Series was not found")
          return null
        }
        seriesId
      }
      ArtworkOwnerKind.COLLECTION,
      ArtworkOwnerKind.READ_LIST,
      -> error("Unsupported native artwork owner kind: $kind")
    }
  return ArtworkOwner(kind, id)
}

private suspend fun ApplicationCall.receiveNativeArtworkUpload(): ByteArray? {
  var bytes: ByteArray? = null
  try {
    receiveMultipart(
      formFieldLimit = ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES.toLong() + 1,
    ).forEachPart { part ->
      try {
        if (part is PartData.FileItem && part.name == "file" && bytes == null) {
          bytes =
            part.provider()
              .readRemaining(ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES.toLong() + 1)
              .readByteArray()
        }
      } finally {
        part.release()
      }
    }
  } catch (_: PayloadTooLargeException) {
    respond(
      HttpStatusCode.PayloadTooLarge,
      XoboroApiError("artwork_too_large", "Artwork exceeds the upload size limit"),
    )
    return null
  }
  if (bytes == null || bytes.isEmpty()) {
    respond(
      HttpStatusCode.BadRequest,
      XoboroApiError("invalid_request", "A non-empty artwork file part is required"),
    )
    return null
  }
  return bytes
}

private suspend fun ApplicationCall.respondArtworkAdministrationForbidden() {
  respondNativeError(
    HttpStatusCode.Forbidden,
    "artwork_administration_forbidden",
    "Artwork administration permission is required",
  )
}

private suspend fun ApplicationCall.respondArtworkNotFound() {
  respondNativeNotFound("artwork_not_found", "Artwork was not found")
}

private class InMemoryArtworkContentStream(
  private val bytes: ByteArray,
  override val mediaType: String,
) : MediaContentStream {
  private var position = 0
  override val contentLength: Long = bytes.size.toLong()

  override fun read(
    buffer: ByteArray,
    offset: Int,
    length: Int,
  ): Int {
    if (position >= bytes.size) return -1
    val count = minOf(length, bytes.size - position)
    bytes.copyInto(buffer, offset, position, position + count)
    position += count
    return count
  }

  override fun close() = Unit
}
