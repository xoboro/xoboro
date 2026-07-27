package io.xoboro.server.persistence

import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.SyncPoint
import io.xoboro.core.domain.SyncPointId
import io.xoboro.core.domain.SyncPointRepository
import io.xoboro.core.domain.UserId
import org.jooq.Record

class JooqSyncPointRepository(
  private val database: XoboroDatabase,
) : SyncPointRepository {
  override fun findByIdOrNull(id: SyncPointId): SyncPoint? =
    database.dsl
      .fetch("$SELECT_SYNC_POINT WHERE id = ?", id.value)
      .map { it.toSyncPoint() }
      .singleOrNull()

  override fun insert(syncPoint: SyncPoint) {
    database.dsl.execute(
      """
      INSERT INTO sync_point (id, user_id, api_key_id, created_at_ms)
      VALUES (?, ?, ?, ?)
      """.trimIndent(),
      syncPoint.id.value,
      syncPoint.userId.value,
      syncPoint.apiKeyId?.value,
      syncPoint.createdAtMillis,
    )
  }

  override fun deleteByUserId(userId: UserId): Int =
    database.dsl.execute(
      "DELETE FROM sync_point WHERE user_id = ?",
      userId.value,
    )

  override fun deleteByUserIdAndApiKeyIds(
    userId: UserId,
    apiKeyIds: Collection<ApiKeyId>,
  ): Int {
    if (apiKeyIds.isEmpty()) return 0
    return apiKeyIds.distinct().chunked(500).sumOf { chunk ->
      val placeholders = List(chunk.size) { "?" }.joinToString()
      database.dsl.execute(
        "DELETE FROM sync_point WHERE user_id = ? AND api_key_id IN ($placeholders)",
        userId.value,
        *chunk.map(ApiKeyId::value).toTypedArray(),
      )
    }
  }

  private fun Record.toSyncPoint(): SyncPoint =
    SyncPoint(
      id = SyncPointId(requiredString("id")),
      userId = UserId(requiredString("user_id")),
      apiKeyId = get("api_key_id", String::class.java)?.let(::ApiKeyId),
      createdAtMillis = requiredString("created_at_ms_64").toLong(),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) {
      "Database field '$field' must not be null"
    }

  companion object {
    private const val SELECT_SYNC_POINT =
      """
      SELECT sync_point.*,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64
      FROM sync_point
      """
  }
}
