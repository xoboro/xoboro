package io.xoboro.server.persistence

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyCommentAlreadyExistsException
import io.xoboro.core.domain.ApiKeyHashAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.ApiKeyRepository
import io.xoboro.core.domain.UserId
import org.jooq.Record
import org.jooq.exception.DataAccessException

class JooqApiKeyRepository(
  private val database: XoboroDatabase,
) : ApiKeyRepository {
  override fun findByKeyHashOrNull(keyHash: String): ApiKey? =
    database.dsl
      .fetch("$SELECT_API_KEY WHERE key_hash = ?", keyHash)
      .map { it.toApiKey() }
      .singleOrNull()

  override fun findAllByUserId(userId: UserId): List<ApiKey> =
    database.dsl
      .fetch(
        "$SELECT_API_KEY WHERE user_id = ? ORDER BY created_at_ms, id",
        userId.value,
      ).map { it.toApiKey() }

  override fun existsByCommentIgnoreCase(
    userId: UserId,
    comment: String,
  ): Boolean =
    database.dsl.fetchExists(
      database.dsl
        .selectOne()
        .from("user_api_key")
        .where("user_id = ? AND comment = ? COLLATE NOCASE", userId.value, comment),
    )

  override fun insert(apiKey: ApiKey) {
    try {
      database.dsl.execute(
        """
        INSERT INTO user_api_key (
          id, user_id, key_hash, comment, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        apiKey.id.value,
        apiKey.userId.value,
        apiKey.keyHash,
        apiKey.comment,
        apiKey.createdAtMillis,
        apiKey.updatedAtMillis,
      )
    } catch (failure: DataAccessException) {
      if (existsByCommentIgnoreCase(apiKey.userId, apiKey.comment)) {
        throw ApiKeyCommentAlreadyExistsException(apiKey.comment)
      }
      if (findByKeyHashOrNull(apiKey.keyHash) != null) {
        throw ApiKeyHashAlreadyExistsException()
      }
      throw failure
    }
  }

  override fun deleteByIdAndUserId(
    id: ApiKeyId,
    userId: UserId,
  ): Boolean =
    database.dsl.execute(
      "DELETE FROM user_api_key WHERE id = ? AND user_id = ?",
      id.value,
      userId.value,
    ) == 1

  private fun Record.toApiKey(): ApiKey =
    ApiKey(
      id = ApiKeyId(requiredString("id")),
      userId = UserId(requiredString("user_id")),
      keyHash = requiredString("key_hash"),
      comment = requiredString("comment"),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  companion object {
    private const val SELECT_API_KEY =
      """
      SELECT user_api_key.*,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64,
        CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
      FROM user_api_key
      """
  }
}
