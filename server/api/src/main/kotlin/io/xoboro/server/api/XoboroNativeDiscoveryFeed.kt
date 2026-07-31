package io.xoboro.server.api

import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection

/**
 * A named discovery feed: a listing whose ordering is part of its definition rather than the caller's
 * choice.
 *
 * Every one of these is expressible with the parameters the listings already accept, and that is exactly
 * the problem being solved. When each client decides for itself what "latest" sorts by, two clients
 * showing a shelf labelled the same way show different shelves — and a bug report saying "latest is
 * wrong" cannot be answered, because nothing ever said what right was.
 *
 * So the feed **owns its sort**. The route rejects a `sort` parameter rather than honouring it: a
 * caller who wants a different order wants the general listing, and silently allowing an override would
 * put the disagreement straight back.
 *
 * A [filter] of `null` means the feed is defined purely by ordering. `ON_DECK` and `KEEP_READING` are
 * the two that also carry a filter, and both are per-caller by nature — they are about what *this*
 * reader has started.
 *
 * [sortField] is the **wire** field name, exactly as a caller would write it in `?sort=`, and it is
 * resolved to a repository property by the same mapper that resolves a caller's parameter. It used to
 * be handed to the repository directly, and none of the three values it holds is a property the
 * repository knows: every feed answered `500 Unsupported catalog sort property`. Nothing caught it,
 * because the route tests assert the string against a fake catalog that accepts any property, and the
 * enum's own test asserted the constants against themselves. Going through the mapper means a feed can
 * only ever name a field the general listing already accepts.
 */
internal enum class XoboroNativeDiscoveryFeed(
  val path: String,
  val sortField: String,
  val direction: CatalogSortDirection,
  val filter: Filter? = null,
) {
  /**
   * Recently added to the catalog, newest first.
   *
   * Sorted by when Xoboro created the row, not by the file's own timestamp: "new to me" is what a reader
   * means by new, and a decade-old file copied in today is new to this library.
   */
  NEW("new", "createdAt", CatalogSortDirection.DESC),

  /**
   * Recently changed, newest first.
   *
   * Distinct from [NEW] because a series gains books over time: `updatedAt` moves when a volume is added
   * to a series that has existed for years, and that is the event a reader following it wants to see.
   */
  UPDATED("updated", "updatedAt", CatalogSortDirection.DESC),

  /**
   * Most recently read, newest first.
   *
   * Per-caller by construction: the underlying sort reads the requesting user's progress, so two readers
   * asking for this feed correctly get different answers.
   */
  RECENTLY_READ("recently-read", "lastReadAt", CatalogSortDirection.DESC),

  /**
   * The next unread item in each series the caller has started, most recently read first.
   *
   * "What do I read next" — as opposed to [KEEP_READING], which is "what am I part-way through".
   */
  ON_DECK("on-deck", "lastReadAt", CatalogSortDirection.DESC, Filter.ON_DECK),

  /**
   * Items the caller has started but not finished, most recently read first.
   */
  KEEP_READING("keep-reading", "lastReadAt", CatalogSortDirection.DESC, Filter.KEEP_READING),
  ;

  internal enum class Filter {
    ON_DECK,
    KEEP_READING,
  }
}
