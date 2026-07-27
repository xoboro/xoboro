package io.xoboro.core.domain

data class AnnouncementFeed(
  val version: String,
  val title: String,
  val homePageUrl: String? = null,
  val description: String? = null,
  val items: List<AnnouncementItem> = emptyList(),
)

data class AnnouncementItem(
  val id: String,
  val url: String? = null,
  val title: String? = null,
  val summary: String? = null,
  val contentHtml: String? = null,
  val dateModified: String? = null,
  val author: AnnouncementAuthor? = null,
  val tags: Set<String> = emptySet(),
  val read: Boolean? = null,
) {
  init {
    require(id.isNotBlank()) { "Announcement ID must not be blank" }
  }
}

data class AnnouncementAuthor(
  val name: String? = null,
  val url: String? = null,
)

fun interface AnnouncementFeedProvider {
  suspend fun fetch(): AnnouncementFeed?
}

interface AnnouncementReadRepository {
  fun findReadIds(userId: UserId): Set<String>

  fun markRead(
    userId: UserId,
    announcementIds: Set<String>,
  )
}
