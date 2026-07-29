package io.xoboro.server.api

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.UserId

/**
 * How a native event is scoped to subscribers.
 *
 * This is a sealed hierarchy so that [isVisibleTo] can decide visibility with an exhaustive
 * `when`. Adding an event family therefore cannot compile until its visibility rule is written,
 * which is the point: the stream has no default, and "I forgot to scope it" is the failure mode
 * that made the compatibility stream broadcast every catalog change to every subscriber.
 */
sealed interface XoboroNativeEventScope {
  /**
   * Visible to subscribers who can access [libraryId].
   *
   * The identifier travels inside the domain event rather than being looked up, so this stays
   * exact for removals: a deleted row does not make its own removal event undeliverable.
   */
  data class Library(
    val libraryId: LibraryId,
  ) : XoboroNativeEventScope

  /**
   * Library-gated, then checked against the item itself for subscribers who carry content
   * restrictions.
   *
   * [removed] suppresses the item check, because the row is already gone by the time a removal
   * is published and a "deliver only if still visible" rule would deliver it to nobody, leaving
   * every client showing the item forever. The residual disclosure is bounded to "something in
   * a library you can see was removed", which is indistinguishable from an item that was added
   * and removed while the subscriber was away.
   */
  data class MediaItem(
    val libraryId: LibraryId,
    val mediaItemId: BookId,
    val removed: Boolean,
  ) : XoboroNativeEventScope

  /** Series equivalent of [MediaItem], with the same [removed] rule and the same reason. */
  data class Series(
    val libraryId: LibraryId,
    val seriesId: SeriesId,
    val removed: Boolean,
  ) : XoboroNativeEventScope

  /**
   * Visible when at least one member is visible, matching what the collection and read list
   * read paths return. A grouping with no visible members is not visible at all, so it is not
   * announced either.
   *
   * Groupings carry no library of their own — a collection may legitimately span libraries — so
   * membership is the only scope available. Unlike [MediaItem] there is no removal exemption:
   * the members are carried in the event payload, so they can still be resolved after the
   * grouping itself is gone.
   */
  data class SeriesMembers(
    val seriesIds: List<SeriesId>,
  ) : XoboroNativeEventScope

  /** Read list equivalent of [SeriesMembers]. */
  data class MediaItemMembers(
    val mediaItemIds: List<BookId>,
  ) : XoboroNativeEventScope

  /** Visible only to the subscriber the event belongs to, regardless of library grants. */
  data class Owner(
    val userId: UserId,
  ) : XoboroNativeEventScope
}
