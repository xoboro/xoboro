package io.xoboro.core.domain

data class SyncPointId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Sync-point ID must not be blank" }
  }
}

data class SyncPoint(
  val id: SyncPointId,
  val userId: UserId,
  val apiKeyId: ApiKeyId?,
  val createdAtMillis: Long,
  val cursor: Int = 0,
) {
  init {
    require(createdAtMillis >= 0) { "Sync-point timestamp must not be negative" }
    require(cursor >= 0) { "Sync-point cursor must not be negative" }
  }
}

interface SyncPointRepository {
  fun findByIdOrNull(id: SyncPointId): SyncPoint?

  fun insert(syncPoint: SyncPoint)

  fun updateCursor(
    id: SyncPointId,
    cursor: Int,
  )

  fun delete(id: SyncPointId)

  fun deleteByUserId(userId: UserId): Int

  fun deleteByUserIdAndApiKeyIds(
    userId: UserId,
    apiKeyIds: Collection<ApiKeyId>,
  ): Int
}

data class SyncMediaItemState(
  val mediaItemId: MediaItemId,
  val revision: String,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
) {
  init {
    require(revision.isNotBlank()) { "Sync media-item revision must not be blank" }
    require(createdAtMillis >= 0) { "Sync media-item creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Sync media-item update timestamp must not precede creation"
    }
  }
}

data class SyncReadListState(
  val readListId: ReadListId,
  val name: String,
  val revision: String,
  val mediaItemIds: List<MediaItemId>,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
) {
  init {
    require(name.isNotBlank()) { "Sync read-list name must not be blank" }
    require(revision.isNotBlank()) { "Sync read-list revision must not be blank" }
    require(mediaItemIds.distinct().size == mediaItemIds.size) {
      "Sync read-list media items must be unique"
    }
    require(createdAtMillis >= 0) { "Sync read-list creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Sync read-list update timestamp must not precede creation"
    }
  }
}

data class SyncProgressState(
  val mediaItemId: MediaItemId,
  val revision: String,
) {
  init {
    require(revision.isNotBlank()) { "Sync progress revision must not be blank" }
  }
}

data class MediaSyncSnapshot(
  val syncPointId: SyncPointId,
  val mediaItems: List<SyncMediaItemState>,
  val readLists: List<SyncReadListState>,
  val progresses: List<SyncProgressState>,
) {
  init {
    require(mediaItems.distinctBy(SyncMediaItemState::mediaItemId).size == mediaItems.size) {
      "Sync snapshot media items must be unique"
    }
    require(readLists.distinctBy(SyncReadListState::readListId).size == readLists.size) {
      "Sync snapshot read lists must be unique"
    }
    require(progresses.distinctBy(SyncProgressState::mediaItemId).size == progresses.size) {
      "Sync snapshot progress rows must be unique"
    }
  }
}

interface MediaSyncSnapshotRepository {
  fun findBySyncPointIdOrNull(id: SyncPointId): MediaSyncSnapshot?

  fun insert(snapshot: MediaSyncSnapshot)
}
