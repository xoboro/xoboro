package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.DuplicatePageRemovalRequester
import io.xoboro.core.application.PageHashLifecycle
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.PageHashMatch
import io.xoboro.core.domain.UnknownPageHash
import kotlinx.serialization.Serializable

/**
 * Administrator surface for pages that appear in more than one media item.
 *
 * Duplicate pages are almost always scanner credits, advertisements, or an "end of volume" filler that a
 * releaser stamps into every archive. Detection already existed and stored its results; nothing exposed
 * them outside the Komga-compatible surface, so a native-only deployment could not see or act on them.
 *
 * The surface is split into recording and executing a decision, and deliberately not merged into
 * one step. `PUT /{pageHash}` records what an operator wants done - `IGNORE`, `DELETE_AUTO`, or
 * `DELETE_MANUAL` - and only `IGNORE` has an effect at that point, by removing the hash from the
 * candidate list. `POST /{pageHash}/removals` is what actually executes a delete: it rewrites the
 * archive on the operator's disk, which is destructive and irreversible for those files, so it
 * refuses to run against a hash that carries no recorded delete decision. Requiring the decision
 * first is what keeps a single unconsidered call from deleting anything - a caller has to have
 * already said what it wants before this route will act on it.
 *
 * `IGNORE` is the one action with an effect purely from being recorded: the candidate list excludes
 * any hash that has been marked known, so ignoring a hash removes it from the list permanently. That
 * is why the decision and the removal are not rejected outright before each other exists - each half
 * of the surface is useful on its own.
 */
fun Route.xoboroNativeDuplicatePageRoutes(
  hashes: PageHashRepository,
  lifecycle: PageHashLifecycle,
  removals: DuplicatePageRemovalRequester,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      route("/duplicate-pages") {
        get {
          call.requireDuplicatePageAdministrator() ?: return@get
          val page = hashes.findUnknown(call.duplicatePageRequest())
          call.respond(page.toNativePage(UnknownPageHash::toNativeResponse))
        }
        get("/decided") {
          call.requireDuplicatePageAdministrator() ?: return@get
          // Filtering by action is the point of this route: an administrator reviewing what they marked
          // for deletion is a different question from what they chose to ignore.
          val actions =
            call.request
              .queryParameters
              .getAll("action")
              .orEmpty()
              .map { value ->
                PageHashAction.entries.firstOrNull { it.name == value }
                  ?: throw XoboroInvalidQueryException("Unknown duplicate-page action: $value")
              }.toSet()
              // No filter means every action, so a caller that just wants "what have I decided" does not
              // have to enumerate the enum to ask.
              .ifEmpty { PageHashAction.entries.toSet() }
          val page = hashes.findKnown(actions, call.duplicatePageRequest())
          call.respond(page.toNativePage(KnownPageHash::toNativeResponse))
        }
        get("/{pageHash}/media-items") {
          call.requireDuplicatePageAdministrator() ?: return@get
          val hash = call.requiredParameter("pageHash")
          val page = hashes.findMatches(hash, call.duplicatePageRequest())
          call.respond(page.toNativePage(PageHashMatch::toNativeResponse))
        }
        put("/{pageHash}") {
          call.requireDuplicatePageAdministrator() ?: return@put
          val hash = call.requiredParameter("pageHash")
          val request = call.receive<XoboroDuplicatePageDecisionRequest>()
          val action =
            PageHashAction.entries.firstOrNull { it.name == request.action }
              ?: throw XoboroInvalidQueryException("Unknown duplicate-page action: ${request.action}")
          // The size is taken from the request rather than looked up, because a caller deciding about a
          // hash has the candidate row in front of it and a lookup here would be a second source of
          // truth for a value that is only ever displayed.
          val known =
            try {
              lifecycle.markKnown(hash = hash, size = request.sizeBytes, action = action)
            } catch (_: IllegalArgumentException) {
              call.respond(
                HttpStatusCode.BadRequest,
                XoboroApiError("invalid_request", "Page hash and size must be valid"),
              )
              return@put
            }
          call.respond(known.toNativeResponse())
        }
        post("/{pageHash}/removals") {
          call.requireDuplicatePageAdministrator() ?: return@post
          val hash = call.requiredParameter("pageHash")
          val known = hashes.findKnownOrNull(hash)
          // Executing a removal requires a recorded delete decision - this route is how a stated
          // intent is carried out, not a second way to delete an arbitrary hash. A hash with no
          // decision, or one recorded as IGNORE, has not asked for this.
          if (known == null || known.action == PageHashAction.IGNORE) {
            call.respondNativeError(
              HttpStatusCode.Conflict,
              "duplicate_page_not_marked_for_deletion",
              "Page hash has no recorded DELETE_AUTO or DELETE_MANUAL decision",
            )
            return@post
          }
          // The body is required, and "every match" is a value inside it rather than its absence.
          //
          // Treating a missing body as "remove every match" reads naturally and is wrong here: the
          // route rewrites archives, and whether a body arrived is not something a server can
          // establish reliably. Sniffing `Content-Length` takes a chunked request - which carries a
          // body and declares no length - for an empty one, so a caller naming a single media item
          // would have its list ignored and every match carrying the hash deleted instead. Any such
          // test resolves an ambiguity, and on this route one side of every ambiguity is mass
          // deletion.
          //
          // So the body is simply read. A request without one is refused by content negotiation
          // before reaching the queue, which is the failure worth having: asking again is cheap,
          // and rewriting archives nobody named is not.
          val requestedMediaItemIds =
            call.receive<XoboroDuplicatePageRemovalRequest>().mediaItemIds
          val allMatches = hashes.findMatches(hash, CatalogPageRequest(unpaged = true)).content
          val matches =
            if (requestedMediaItemIds == null) {
              allMatches
            } else {
              val requested = requestedMediaItemIds.toSet()
              allMatches.filter { it.mediaItemId.value in requested }
            }
          if (requestedMediaItemIds != null && matches.isEmpty()) {
            call.respondNativeError(
              HttpStatusCode.NotFound,
              "duplicate_page_match_not_found",
              "No media item in the request matches this page hash",
            )
            return@post
          }
          val queued = removals.deleteDuplicatePages(hash, matches)
          call.respond(HttpStatusCode.Accepted, XoboroDuplicatePageRemovalResponse(queued))
        }
      }
    }
  }
}

/**
 * Returns null after responding when the caller is not an administrator.
 *
 * Duplicate pages expose file names and sizes across every library, so this is administrator-only
 * regardless of library grants: a caller who can see one library must not learn the file layout of
 * another from a duplicate-page listing.
 */
private suspend fun io.ktor.server.application.ApplicationCall.requireDuplicatePageAdministrator(): Unit? {
  if (nativeUser().isAdmin) return Unit
  respondNativeError(
    HttpStatusCode.Forbidden,
    "duplicate_pages_forbidden",
    "Administrator role is required",
  )
  return null
}

private fun io.ktor.server.application.ApplicationCall.duplicatePageRequest(): CatalogPageRequest {
  val page =
    request.queryParameters["page"]?.let {
      it.toIntOrNull() ?: throw XoboroInvalidQueryException("page must be an integer")
    } ?: 0
  val size =
    request.queryParameters["size"]?.let {
      it.toIntOrNull() ?: throw XoboroInvalidQueryException("size must be an integer")
    } ?: DEFAULT_DUPLICATE_PAGE_SIZE
  // Constructed through the domain type so its own bounds are the single definition of a valid page,
  // and translated to a 400 rather than surfacing as a 500.
  return try {
    CatalogPageRequest(page = page, size = size)
  } catch (failure: IllegalArgumentException) {
    throw XoboroInvalidQueryException(failure.message ?: "page and size must be valid")
  }
}

private fun <T, R> CatalogPage<T>.toNativePage(transform: (T) -> R): XoboroPageResponse<R> {
  val totalPages =
    if (totalElements == 0L) 0 else ((totalElements + size - 1) / size).toInt()
  return XoboroPageResponse(
    items = content.map(transform),
    page = page,
    size = size,
    totalItems = totalElements,
    totalPages = totalPages,
    hasPrevious = page > 0,
    hasNext = page + 1 < totalPages,
  )
}

private const val DEFAULT_DUPLICATE_PAGE_SIZE = 20

private fun UnknownPageHash.toNativeResponse(): XoboroDuplicatePageResponse =
  XoboroDuplicatePageResponse(
    hash = hash,
    sizeBytes = size,
    matchCount = matchCount,
  )

private fun KnownPageHash.toNativeResponse(): XoboroDecidedDuplicatePageResponse =
  XoboroDecidedDuplicatePageResponse(
    hash = hash,
    sizeBytes = size,
    action = action.name,
    matchCount = matchCount,
    deleteCount = deleteCount,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

private fun PageHashMatch.toNativeResponse(): XoboroDuplicatePageMatchResponse =
  XoboroDuplicatePageMatchResponse(
    mediaItemId = mediaItemId.value,
    pageNumber = pageNumber,
    fileName = fileName,
    fileSizeBytes = fileSize,
    mediaType = mediaType,
  )

@Serializable
data class XoboroDuplicatePageResponse(
  val hash: String,
  val sizeBytes: Long? = null,
  val matchCount: Int,
)

@Serializable
data class XoboroDecidedDuplicatePageResponse(
  val hash: String,
  val sizeBytes: Long? = null,
  val action: String,
  val matchCount: Int,
  /**
   * How many pages a removal has deleted for this hash.
   *
   * `0` until `POST /duplicate-pages/{pageHash}/removals` actually runs a removal for this hash;
   * it is the stored value, not a placeholder that changes shape once removal is implemented.
   */
  val deleteCount: Int,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
)

@Serializable
data class XoboroDuplicatePageMatchResponse(
  val mediaItemId: String,
  val pageNumber: Int,
  val fileName: String,
  val fileSizeBytes: Long,
  val mediaType: String,
)

/**
 * [sizeBytes] is optional: a hash whose pages differ in size has no single size, and the candidate
 * listing reports null for it.
 */
@Serializable
data class XoboroDuplicatePageDecisionRequest(
  val action: String,
  val sizeBytes: Long? = null,
)

/**
 * The request body for `POST /duplicate-pages/{pageHash}/removals`. Absent entirely, or present
 * with [mediaItemIds] null or omitted, means every match is queued; a non-null list restricts
 * removal to matches whose media item is in it.
 */
@Serializable
data class XoboroDuplicatePageRemovalRequest(
  val mediaItemIds: List<String>? = null,
)

@Serializable
data class XoboroDuplicatePageRemovalResponse(
  val queuedMediaItems: Int,
)
