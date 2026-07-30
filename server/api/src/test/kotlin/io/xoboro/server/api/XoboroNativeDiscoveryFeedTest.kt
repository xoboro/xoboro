package io.xoboro.server.api

import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogSortDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the feed definitions themselves.
 *
 * The value of a named feed is that its ordering is fixed server-side, so these assertions are the
 * contract: change one and a client's shelf changes meaning. Asserting them here rather than only
 * through HTTP means the definition is pinned in one place, where the enum is.
 */
class XoboroNativeDiscoveryFeedTest {
  @Test
  fun `every feed has a distinct path`() {
    val paths = XoboroNativeDiscoveryFeed.entries.map(XoboroNativeDiscoveryFeed::path)

    // Two feeds sharing a path would make one of them unreachable, and Ktor would not complain.
    assertEquals(paths.size, paths.toSet().size, "feed paths must be unique: $paths")
    assertTrue(paths.none { it.isBlank() })
  }

  @Test
  fun `no feed path collides with an identifier-shaped segment`() {
    // Feeds live under /feeds/, so a path cannot be mistaken for a series or media-item identifier.
    // Asserted because the alternative - mounting them at the collection root - is the obvious shortcut
    // and would make a series literally named "new" unreachable.
    assertTrue(XoboroNativeDiscoveryFeed.entries.none { "/" in it.path })
  }

  @Test
  fun `pins what each feed means`() {
    // These four are the definitions a client depends on. `new` sorts by when Xoboro created the row
    // rather than the file's own timestamp, because "new to me" is what a reader means: a decade-old
    // file copied in today is new to this library.
    assertEquals("createdAt", XoboroNativeDiscoveryFeed.NEW.sortProperty)
    // `updated` is distinct from `new` because a series gains books over time - updatedAt moves when a
    // volume joins a series that has existed for years.
    assertEquals("updatedAt", XoboroNativeDiscoveryFeed.UPDATED.sortProperty)
    assertEquals("lastReadAt", XoboroNativeDiscoveryFeed.RECENTLY_READ.sortProperty)
    assertEquals("lastReadAt", XoboroNativeDiscoveryFeed.ON_DECK.sortProperty)
    assertEquals("lastReadAt", XoboroNativeDiscoveryFeed.KEEP_READING.sortProperty)

    // Every feed is newest-first. A discovery shelf that put the oldest thing first would be a list, not
    // a shelf.
    assertTrue(
      XoboroNativeDiscoveryFeed.entries.all { it.direction == CatalogSortDirection.DESC },
    )
  }

  @Test
  fun `only the two reader-scoped feeds carry a filter`() {
    val filtered =
      XoboroNativeDiscoveryFeed.entries.filter { it.filter != null }.map { it.name }.toSet()

    // The rest are defined purely by ordering, which is why they apply to series listings as well as
    // media-item listings. on-deck and keep-reading are about what *this* reader has started, so they
    // cannot be.
    assertEquals(setOf("ON_DECK", "KEEP_READING"), filtered)
  }

  @Test
  fun `applies the feed filter to a media item query`() {
    val base = BookCatalogQuery()

    assertTrue(base.withFeedFilter(XoboroNativeDiscoveryFeed.ON_DECK).onDeck)
    assertFalse(base.withFeedFilter(XoboroNativeDiscoveryFeed.ON_DECK).keepReading)
    assertTrue(base.withFeedFilter(XoboroNativeDiscoveryFeed.KEEP_READING).keepReading)
    assertFalse(base.withFeedFilter(XoboroNativeDiscoveryFeed.KEEP_READING).onDeck)
    // An ordering-only feed must not quietly filter as well.
    assertEquals(base, base.withFeedFilter(XoboroNativeDiscoveryFeed.NEW))
  }
}
