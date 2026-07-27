package io.xoboro.core.application

import io.xoboro.core.domain.AnnouncementFeed
import io.xoboro.core.domain.AnnouncementFeedProvider
import io.xoboro.core.domain.AnnouncementItem
import io.xoboro.core.domain.AnnouncementReadRepository
import io.xoboro.core.domain.UserId
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnouncementLifecycleTest {
  @Test
  fun `adds per-user read state without mutating the remote feed`() =
    runSuspend {
      val feed =
        AnnouncementFeed(
          version = "https://jsonfeed.org/version/1",
          title = "Synthetic announcements",
          items =
            listOf(
              AnnouncementItem("announcement-1"),
              AnnouncementItem("announcement-2"),
            ),
        )
      val reads = InMemoryAnnouncementReads()
      val lifecycle =
        AnnouncementLifecycle(
          feedProvider = AnnouncementFeedProvider { feed },
          reads = reads,
        )
      val userId = UserId("user-1")

      lifecycle.markRead(userId, setOf("announcement-2"))
      val result = requireNotNull(lifecycle.findForUser(userId))

      assertEquals(listOf(false, true), result.items.map { it.read })
      assertEquals(listOf(null, null), feed.items.map { it.read })
    }

  @Test
  fun `preserves an unavailable feed`() =
    runSuspend {
      val lifecycle =
        AnnouncementLifecycle(
          feedProvider = AnnouncementFeedProvider { null },
          reads = InMemoryAnnouncementReads(),
        )

      assertNull(lifecycle.findForUser(UserId("user-1")))
    }

  private class InMemoryAnnouncementReads : AnnouncementReadRepository {
    private val values = linkedMapOf<UserId, MutableSet<String>>()

    override fun findReadIds(userId: UserId): Set<String> =
      values[userId].orEmpty()

    override fun markRead(
      userId: UserId,
      announcementIds: Set<String>,
    ) {
      values.getOrPut(userId, ::linkedSetOf).addAll(announcementIds)
    }
  }

  private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
      object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
          outcome = result
        }
      },
    )
    return requireNotNull(outcome).getOrThrow()
  }
}
