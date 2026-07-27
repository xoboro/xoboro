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
) {
  init {
    require(createdAtMillis >= 0) { "Sync-point timestamp must not be negative" }
  }
}

interface SyncPointRepository {
  fun findByIdOrNull(id: SyncPointId): SyncPoint?

  fun insert(syncPoint: SyncPoint)

  fun deleteByUserId(userId: UserId): Int

  fun deleteByUserIdAndApiKeyIds(
    userId: UserId,
    apiKeyIds: Collection<ApiKeyId>,
  ): Int
}
