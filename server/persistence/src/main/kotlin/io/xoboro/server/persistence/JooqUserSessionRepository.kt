package io.xoboro.server.persistence

import io.xoboro.core.domain.SessionInsert
import io.xoboro.core.domain.SessionTouch
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserSession
import io.xoboro.core.domain.UserSessionRepository
import org.jooq.Record
import org.jooq.exception.DataAccessException

class JooqUserSessionRepository(
  private val database: XoboroDatabase,
) : UserSessionRepository {
  override fun findByTokenDigestOrNull(tokenDigest: String): UserSession? =
    database.dsl
      .fetch("$SELECT_SESSION WHERE token_digest = ?", tokenDigest)
      .map { it.toUserSession() }
      .singleOrNull()

  /**
   * A locked database answers [SessionInsert.UNAVAILABLE] rather than throwing, for the same
   * reason [touchIfActive] does - and reported apart from a digest collision, because the two ask
   * the caller for opposite things: a collision wants another token, a busy store wants a
   * different answer to the request. Observed against a library of 18,211 archives, where a scan
   * held the write lock while a `Basic` request tried to open its session, and the escaping
   * exception turned valid credentials into a `500`.
   */
  override fun insertIfAbsent(session: UserSession): SessionInsert =
    try {
      val inserted =
        database.dsl.execute(
          """
          INSERT INTO user_session (
            token_digest, user_id, created_at_ms, last_accessed_at_ms, expires_at_ms
          ) VALUES (?, ?, ?, ?, ?)
          ON CONFLICT(token_digest) DO NOTHING
          """.trimIndent(),
          session.tokenDigest,
          session.userId.value,
          session.createdAtMillis,
          session.lastAccessedAtMillis,
          session.expiresAtMillis,
        )
      if (inserted == 1) SessionInsert.INSERTED else SessionInsert.DIGEST_TAKEN
    } catch (failure: DataAccessException) {
      if (failure.isDatabaseLocked()) SessionInsert.UNAVAILABLE else throw failure
    }

  /**
   * A locked database answers [SessionTouch.UNAVAILABLE] rather than throwing.
   *
   * Every authenticated request extends the session's sliding window, so every read is also a
   * write. Under a large scan that write can wait out its busy timeout, and letting the
   * exception escape failed the whole request - a `500` on a plain `GET`, observed against a
   * library of 18,211 archives. Contention is a property of the store, so the store is where it
   * is absorbed; it is reported as distinct from an expiry so the caller cannot mistake a busy
   * moment for a revocation.
   */
  override fun touchIfActive(
    tokenDigest: String,
    accessedAtMillis: Long,
    expiresAtMillis: Long,
  ): SessionTouch =
    try {
      val updated =
        database.dsl.execute(
          """
          UPDATE user_session SET
            last_accessed_at_ms = max(last_accessed_at_ms, ?),
            expires_at_ms = max(expires_at_ms, ?)
          WHERE token_digest = ? AND expires_at_ms > ?
          """.trimIndent(),
          accessedAtMillis,
          expiresAtMillis,
          tokenDigest,
          accessedAtMillis,
        )
      if (updated == 1) SessionTouch.TOUCHED else SessionTouch.EXPIRED
    } catch (failure: DataAccessException) {
      if (failure.isDatabaseLocked()) SessionTouch.UNAVAILABLE else throw failure
    }


  override fun deleteByTokenDigest(tokenDigest: String): Boolean =
    database.dsl.execute(
      "DELETE FROM user_session WHERE token_digest = ?",
      tokenDigest,
    ) == 1

  override fun deleteByUserId(userId: UserId): Int =
    database.dsl.execute(
      "DELETE FROM user_session WHERE user_id = ?",
      userId.value,
    )

  override fun deleteExpired(nowMillis: Long): Int =
    database.dsl.execute(
      "DELETE FROM user_session WHERE expires_at_ms <= ?",
      nowMillis,
    )

  private fun Record.toUserSession(): UserSession =
    UserSession(
      tokenDigest = requiredString("token_digest"),
      userId = UserId(requiredString("user_id")),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      lastAccessedAtMillis = requiredLongText("last_accessed_at_ms_64"),
      expiresAtMillis = requiredLongText("expires_at_ms_64"),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private companion object {
    const val SELECT_SESSION =
      """
      SELECT user_session.*,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64,
        CAST(last_accessed_at_ms AS TEXT) AS last_accessed_at_ms_64,
        CAST(expires_at_ms AS TEXT) AS expires_at_ms_64
      FROM user_session
      """
  }
}
