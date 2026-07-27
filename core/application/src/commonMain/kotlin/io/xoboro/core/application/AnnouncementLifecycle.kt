package io.xoboro.core.application

import io.xoboro.core.domain.AnnouncementFeed
import io.xoboro.core.domain.AnnouncementFeedProvider
import io.xoboro.core.domain.AnnouncementReadRepository
import io.xoboro.core.domain.UserId

class AnnouncementLifecycle(
  private val feedProvider: AnnouncementFeedProvider,
  private val reads: AnnouncementReadRepository,
) {
  suspend fun findForUser(userId: UserId): AnnouncementFeed? {
    val feed = feedProvider.fetch() ?: return null
    val readIds = reads.findReadIds(userId)
    return feed.copy(
      items = feed.items.map { item -> item.copy(read = item.id in readIds) },
    )
  }

  fun markRead(
    userId: UserId,
    announcementIds: Set<String>,
  ) {
    reads.markRead(userId, announcementIds)
  }
}
