package io.xoboro.server.persistence

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyCommentAlreadyExistsException
import io.xoboro.core.domain.ApiKeyHashAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.ApiKeyRepository
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import org.jooq.DSLContext
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
      ?.withScopes()

  override fun findAllByUserId(userId: UserId): List<ApiKey> =
    database.dsl
      .fetch(
        "$SELECT_API_KEY WHERE user_id = ? ORDER BY created_at_ms, id",
        userId.value,
      ).map { it.toApiKey().withScopes() }

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
      // One transaction: a key that exists without the scopes it was created with would authorize
      // more than it was meant to, which is the wrong way for a partial write to fail.
      database.transaction { transaction ->
        transaction.execute(
          """
          INSERT INTO user_api_key (
            id, user_id, key_hash, comment, expires_at_ms, created_at_ms, updated_at_ms
          ) VALUES (?, ?, ?, ?, ?, ?, ?)
          """.trimIndent(),
          apiKey.id.value,
          apiKey.userId.value,
          apiKey.keyHash,
          apiKey.comment,
          apiKey.expiresAtMillis,
          apiKey.createdAtMillis,
          apiKey.updatedAtMillis,
        )
        apiKey.scopes.forEach { scope ->
          transaction.execute(
            "INSERT INTO user_api_key_scope (api_key_id, scope) VALUES (?, ?)",
            apiKey.id.value,
            scope.name,
          )
        }
      }
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
      expiresAtMillis = get("expires_at_ms_64", String::class.java)?.toLong(),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  /**
   * Scopes are fetched per key rather than joined into [SELECT_API_KEY]. A join would fan the key row
   * out across its scopes and make the single-row read a grouping problem; keys per user are few and
   * the authentication path reads exactly one.
   */
  private fun ApiKey.withScopes(): ApiKey =
    copy(
      scopes =
        database.dsl
          .fetch("SELECT scope FROM user_api_key_scope WHERE api_key_id = ?", id.value)
          .mapNotNull { record ->
            val name = record.requiredString("scope")
            // An unknown scope must never widen the key. Dropping it narrows, which is the safe
            // direction if a future release removes a role that a stored key still names.
            UserRole.entries.firstOrNull { it.name == name }
          }.toSet(),
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
        CAST(updated_at_ms AS TEXT) AS updated_at_ms_64,
        CAST(expires_at_ms AS TEXT) AS expires_at_ms_64
      FROM user_api_key
      """
  }
}
