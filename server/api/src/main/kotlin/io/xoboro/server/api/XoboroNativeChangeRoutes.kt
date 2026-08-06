package io.xoboro.server.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.CatalogChange
import io.xoboro.core.domain.CatalogChangePage
import io.xoboro.core.domain.CatalogChangeRepository
import kotlinx.serialization.Serializable

/**
 * `GET /api/xoboro/v1/changes`: what changed after a cursor.
 *
 * The counterpart to the SSE stream rather than a replacement for it. SSE tells a connected client to
 * invalidate something now; this tells a client that was away what it missed. ADR 0056 decided SSE is
 * "an invalidation channel rather than a durable log; reconnect recovery must refresh current state",
 * which is right for a browser tab and impossible for a device holding a local copy: refreshing current
 * state means re-reading the whole catalogue to learn three things moved, and it cannot report a
 * deletion at all, because a deleted item is absent from current state exactly like one never seen.
 *
 * Every row is scoped by the reader's libraries. A change carries its library on the row so a deletion
 * stays authorizable after the entity it names is gone - the entity cannot be consulted, and guessing
 * would leak the existence of items in libraries the reader was never granted.
 */
fun Route.xoboroNativeChangeRoutes(changes: CatalogChangeRepository) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      get("/changes") {
        val user = call.nativeUser()
        val after = call.optionalLong("after") ?: 0L
        val size = call.optionalInt("size") ?: DEFAULT_CHANGE_PAGE_SIZE
        if (after < 0) throw XoboroInvalidQueryException("after must not be negative")
        if (size !in 1..MAXIMUM_CHANGE_PAGE_SIZE) {
          throw XoboroInvalidQueryException(
            "size must be between 1 and $MAXIMUM_CHANGE_PAGE_SIZE",
          )
        }
        call.respond(
          changes
            .findAfter(
              afterSequence = after,
              libraryIds = user.catalogAccess().libraryIds,
              limit = size,
            ).toNativeResponse(),
        )
      }
    }
  }
}

private fun ApplicationCall.optionalLong(name: String): Long? =
  request.queryParameters[name]?.let {
    it.toLongOrNull() ?: throw XoboroInvalidQueryException("$name must be an integer")
  }

private fun ApplicationCall.optionalInt(name: String): Int? =
  request.queryParameters[name]?.let {
    it.toIntOrNull() ?: throw XoboroInvalidQueryException("$name must be an integer")
  }

/** Enough to catch up a device that was away for a while without asking it to page for minutes. */
private const val DEFAULT_CHANGE_PAGE_SIZE = 500

private const val MAXIMUM_CHANGE_PAGE_SIZE = 2_000

@Serializable
data class XoboroChangeResponse(
  val sequence: Long,
  val entityKind: String,
  val entityId: String,
  val mutation: String,
  val libraryId: String,
  val occurredAtMillis: Long,
)

@Serializable
data class XoboroChangePageResponse(
  val changes: List<XoboroChangeResponse>,
  /** Pass this back as `after` next time. Unchanged when nothing followed the cursor. */
  val nextCursor: Long,
  /** The highest sequence retention has removed through. */
  val floorSequence: Long,
  /**
   * True when the cursor is older than the floor, so what happened in between is gone.
   *
   * The client must discard its local copy and read the catalogue again. Applying the accompanying
   * (empty) change list and carrying on would leave it holding rows for items that no longer exist,
   * with nothing to tell it so - which is why this is a field rather than an empty page.
   */
  val resyncRequired: Boolean,
)

private fun CatalogChangePage.toNativeResponse(): XoboroChangePageResponse =
  XoboroChangePageResponse(
    changes = changes.map(CatalogChange::toNativeResponse),
    nextCursor = nextCursor,
    floorSequence = floorSequence,
    resyncRequired = resyncRequired,
  )

private fun CatalogChange.toNativeResponse(): XoboroChangeResponse =
  XoboroChangeResponse(
    sequence = sequence,
    entityKind = entityKind.name,
    entityId = entityId,
    mutation = mutation.name,
    libraryId = libraryId.value,
    occurredAtMillis = occurredAtMillis,
  )
