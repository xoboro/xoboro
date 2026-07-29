package io.xoboro.server.api

import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.User

/**
 * Decides whether [this] event may be delivered to [user].
 *
 * Kept as a pure function of the event, the subscriber and the catalog so that it can be tested
 * without a server or a live stream, and so the whole authorization decision lives in one place
 * rather than being spread across a publish path and a resume path. The hub must route both live
 * delivery and buffered replay through this function; a replay that skipped it would leak harder
 * than the live path, because it arrives as a burst.
 */
internal fun XoboroNativeEvent.isVisibleTo(
  user: User,
  catalog: CatalogReadRepository,
): Boolean {
  val access = user.nativeCatalogAccess()
  return when (val scope = scope) {
    is XoboroNativeEventScope.Owner -> scope.userId == user.id

    is XoboroNativeEventScope.Library -> user.canAccessLibrary(scope.libraryId)

    is XoboroNativeEventScope.MediaItem ->
      user.canAccessLibrary(scope.libraryId) &&
        (
          scope.removed ||
            !user.hasContentRestrictions() ||
            catalog.findBookByIdOrNull(scope.mediaItemId, access) != null
        )

    is XoboroNativeEventScope.Series ->
      user.canAccessLibrary(scope.libraryId) &&
        (
          scope.removed ||
            !user.hasContentRestrictions() ||
            catalog.findSeriesByIdOrNull(scope.seriesId, access) != null
        )

    is XoboroNativeEventScope.SeriesMembers ->
      scope.seriesIds.anyVisibleTo(user) { catalog.findSeriesByIdOrNull(it, access) != null }

    is XoboroNativeEventScope.MediaItemMembers ->
      scope.mediaItemIds.anyVisibleTo(user) { catalog.findBookByIdOrNull(it, access) != null }
  }
}

/**
 * Membership visibility for groupings, which carry no library of their own.
 *
 * Members are resolved by their own identifiers rather than by querying "members of this
 * grouping". That is deliberate: the read paths query by grouping id, which returns nothing once
 * the grouping is deleted, so reusing them here would deliver no removal to anyone and leave
 * every client holding a deleted collection forever. Members outlive the grouping, so resolving
 * them individually answers the same question for a live grouping and still answers it for a
 * removed one.
 *
 * A subscriber who can reach every library and carries no restrictions is decided without any
 * query. Otherwise the search stops at the first visible member, so the per-member cost is only
 * paid by restricted subscribers and only until one member matches.
 */
private inline fun <T> List<T>.anyVisibleTo(
  user: User,
  visible: (T) -> Boolean,
): Boolean =
  when {
    isEmpty() -> false
    user.canAccessAllLibraries() && !user.hasContentRestrictions() -> true
    else -> any(visible)
  }

private fun User.hasContentRestrictions(): Boolean = restrictions != ContentRestrictions()
