package io.xoboro.server

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class HttpAnnouncementFeedProviderTest {
  @Test
  fun `parses JSON Feed and refreshes it after one hour of inactivity`() =
    runBlocking {
      val clock = AtomicLong(1_000)
      val fetches = AtomicInteger()
      val provider =
        HttpAnnouncementFeedProvider(
          fetchContent = {
            fetches.incrementAndGet()
            syntheticFeed("Synthetic announcement ${fetches.get()}")
          },
          currentTimeMillis = clock::get,
        )

      val first = requireNotNull(provider.fetch())
      assertEquals("Synthetic announcement 1", first.items.single().title)
      assertEquals("Synthetic author", first.items.single().author?.name)
      assertEquals(setOf("release"), first.items.single().tags)

      clock.set(2_000)
      assertEquals(first, provider.fetch())
      assertEquals(1, fetches.get())

      clock.addAndGet(HttpAnnouncementFeedProvider.DEFAULT_CACHE_TIMEOUT_MILLIS)
      val refreshed = requireNotNull(provider.fetch())
      assertEquals("Synthetic announcement 2", refreshed.items.single().title)
      assertEquals(2, fetches.get())
    }

  @Test
  fun `returns null for an unavailable feed and rejects invalid content`() =
    runBlocking {
      assertNull(
        HttpAnnouncementFeedProvider(
          fetchContent = { null },
          currentTimeMillis = { 1 },
        ).fetch(),
      )
      assertFails {
        HttpAnnouncementFeedProvider(
          fetchContent = { "{}" },
          currentTimeMillis = { 1 },
        ).fetch()
      }
    }

  private fun syntheticFeed(title: String): String =
    """
    {
      "version": "https://jsonfeed.org/version/1",
      "title": "Synthetic announcements",
      "home_page_url": "https://example.invalid/announcements",
      "items": [
        {
          "id": "announcement-1",
          "title": "$title",
          "date_modified": "2026-01-01T00:00:00Z",
          "author": {
            "name": "Synthetic author",
            "url": "https://example.invalid/author"
          },
          "tags": ["release"],
          "ignored": "value"
        }
      ]
    }
    """.trimIndent()
}
