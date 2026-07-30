package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.MetadataEditingLifecycle
import io.xoboro.core.application.MetadataFacetRepository
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.SeriesId
import kotlinx.serialization.json.JsonObject

fun Route.xoboroNativeMetadataRoutes(
  catalog: CatalogReadRepository,
  editing: MetadataEditingLifecycle,
  facets: MetadataFacetRepository,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      patch("/media-items/{mediaItemId}/metadata") {
        val user = call.nativeUser()
        val access = user.catalogAccess()
        val id = BookId(call.requiredParameter("mediaItemId"))
        if (catalog.findBookByIdOrNull(id, access) == null) {
          call.respondNativeNotFound("media_item_not_found", "Media item was not found")
          return@patch
        }
        val raw = call.receive<JsonObject>()
        val patch =
          runCatching(raw::toManualBookMetadataPatch).getOrElse { failure ->
            call.respondInvalidMetadataRequest(failure)
            return@patch
          }
        call.respond(HttpStatusCode.OK, editing.patchBook(id, patch).toNativeMetadataResponse())
      }
      patch("/media-items/metadata") {
        val user = call.nativeUser()
        val access = user.catalogAccess()
        val raw = call.receive<JsonObject>()
        val requested =
          runCatching {
            raw.entries.associate { (rawId, value) ->
              val patch = value as? JsonObject
                ?: throw IllegalArgumentException("Patch for $rawId must be an object")
              BookId(rawId) to patch.toManualBookMetadataPatch()
            }
          }.getOrElse { failure ->
            call.respondInvalidMetadataRequest(failure)
            return@patch
          }
        if (requested.size > XOBORO_METADATA_BULK_PATCH_LIMIT) {
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError(
              "invalid_request",
              "At most $XOBORO_METADATA_BULK_PATCH_LIMIT media items can be patched at once",
            ),
          )
          return@patch
        }
        if (requested.keys.any { catalog.findBookByIdOrNull(it, access) == null }) {
          call.respondNativeNotFound(
            "media_item_not_found",
            "One or more media items were not found",
          )
          return@patch
        }
        val patched = editing.patchBooks(requested)
        if (patched.size != requested.size) {
          // patchBooks drops entries whose patch fails inside the lifecycle and gives no signal
          // of which ones, so a short result means part of the batch landed and part did not.
          // Answering 200 with a shorter array would report success for edits that never
          // happened, which is the failure this endpoint most needs to avoid.
          call.respond(
            HttpStatusCode.Conflict,
            XoboroApiError(
              "bulk_patch_incomplete",
              "Only ${patched.size} of ${requested.size} media items could be patched. " +
                "The batch is partially applied; re-read the requested media items.",
            ),
          )
          return@patch
        }
        call.respond(
          HttpStatusCode.OK,
          patched.map(BookMetadata::toNativeMetadataResponse),
        )
      }
      patch("/series/{seriesId}/metadata") {
        val user = call.nativeUser()
        val access = user.catalogAccess()
        val id = SeriesId(call.requiredParameter("seriesId"))
        if (catalog.findSeriesByIdOrNull(id, access) == null) {
          call.respondNativeNotFound("series_not_found", "Series was not found")
          return@patch
        }
        val raw = call.receive<JsonObject>()
        val patch =
          runCatching(raw::toManualSeriesMetadataPatch).getOrElse { failure ->
            call.respondInvalidMetadataRequest(failure)
            return@patch
          }
        call.respond(HttpStatusCode.OK, editing.patchSeries(id, patch).toNativeMetadataResponse())
      }
      get("/facets") {
        val user = call.nativeUser()
        call.respond(
          HttpStatusCode.OK,
          facets.findValues(
            call.requiredMetadataFacet(),
            call.nativeMetadataFacetQuery(),
            user.catalogAccess(),
          ),
        )
      }
      get("/facets/authors") {
        val user = call.nativeUser()
        call.respond(
          HttpStatusCode.OK,
          facets
            .findAuthors(
              call.nativeMetadataFacetQuery(),
              user.catalogAccess(),
              call.nativeAuthorPageRequest(),
            ).toNativeAuthorPage(),
        )
      }
    }
  }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondInvalidMetadataRequest(
  failure: Throwable,
) {
  respond(
    HttpStatusCode.BadRequest,
    XoboroApiError(
      "invalid_request",
      failure.message ?: "Metadata request is invalid",
    ),
  )
}
