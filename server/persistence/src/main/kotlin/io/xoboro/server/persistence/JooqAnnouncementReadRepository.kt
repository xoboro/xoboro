package io.xoboro.server.persistence

import io.xoboro.core.domain.AnnouncementReadRepository
import io.xoboro.core.domain.UserId

class JooqAnnouncementReadRepository(
  private val database: XoboroDatabase,
) : AnnouncementReadRepository {
  override fun findReadIds(userId: UserId): Set<String> =
    database.dsl
      .fetch(
        """
        SELECT announcement_id
        FROM user_announcement_read
        WHERE user_id = ?
        ORDER BY announcement_id
        """.trimIndent(),
        userId.value,
      ).getValues(0, String::class.java)
      .toSet()

  override fun markRead(
    userId: UserId,
    announcementIds: Set<String>,
  ) {
    database.transaction { transaction ->
      announcementIds.forEach { announcementId ->
        transaction.execute(
          """
          INSERT OR IGNORE INTO user_announcement_read (user_id, announcement_id)
          VALUES (?, ?)
          """.trimIndent(),
          userId.value,
          announcementId,
        )
      }
    }
  }
}
