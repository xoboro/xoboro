package io.xoboro.core.application

import io.xoboro.core.domain.SessionInsert
import io.xoboro.core.domain.SessionTouch
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserSession
import io.xoboro.core.domain.UserSessionRepository

data class CreatedUserSession(
  val session: UserSession,
  val plainToken: String,
)

class UserSessionLifecycle(
  private val users: UserRepository,
  private val sessions: UserSessionRepository,
  private val tokenEncoder: TokenEncoder,
  private val plainTokenFactory: () -> String,
  private val currentTimeMillis: () -> Long,
  val inactivityTimeoutMillis: Long,
  private val sessionTouchIntervalMillis: Long =
    minOf(
      DEFAULT_SESSION_TOUCH_INTERVAL_MILLIS,
      maxOf(1L, inactivityTimeoutMillis / 2),
    ),
) {
  init {
    require(inactivityTimeoutMillis > 0) { "Session inactivity timeout must be positive" }
    require(sessionTouchIntervalMillis > 0) { "Session touch interval must be positive" }
    require(sessionTouchIntervalMillis <= inactivityTimeoutMillis) {
      "Session touch interval must not exceed the inactivity timeout"
    }
  }

  /**
   * Opens a session, or `null` when the store could not accept one right now.
   *
   * `null` is reserved for contention and means "ask again later", never "something is wrong with
   * this user". Exhausting [MAX_GENERATION_ATTEMPTS] stays an [error] because that is a broken
   * token factory rather than a busy moment, and the two must not arrive at the caller as the same
   * thing: against a real library a scan held the SQLite write lock long enough that the insert
   * lost it, and reporting that as an exhausted-token bug both hid the cause and made a `500` out
   * of a request whose credentials were perfectly good.
   */
  fun create(user: User): CreatedUserSession? {
    val now = now()
    repeat(MAX_GENERATION_ATTEMPTS) {
      val plainToken = plainTokenFactory()
      require(plainToken.isNotBlank()) { "Generated session token must not be blank" }
      val digest = tokenEncoder.encode(plainToken)
      val session =
        UserSession(
          tokenDigest = digest,
          userId = user.id,
          createdAtMillis = now,
          expiresAtMillis = now + inactivityTimeoutMillis,
        )
      when (sessions.insertIfAbsent(session)) {
        SessionInsert.INSERTED -> return CreatedUserSession(session, plainToken)
        // Another token might not collide, so this attempt is spent and the next one runs.
        SessionInsert.DIGEST_TAKEN -> Unit
        // No token will be accepted while the store is busy, so spending the remaining attempts
        // on it would only delay the answer the caller already needs to act on.
        SessionInsert.UNAVAILABLE -> return null
      }
    }
    error("Failed to generate a unique session token")
  }

  fun authenticate(plainToken: String): User? {
    if (plainToken.isBlank()) return null
    val digest = tokenEncoder.encode(plainToken)
    val session = sessions.findByTokenDigestOrNull(digest) ?: return null
    val now = now()
    val user = users.findByIdOrNull(session.userId)
    if (user == null) {
      sessions.deleteByTokenDigest(digest)
      return null
    }
    if (
      session.expiresAtMillis > now &&
        now - session.lastAccessedAtMillis < sessionTouchIntervalMillis
    ) {
      return user
    }
    // The store, not the row read above, decides expiry: a session extended between that read
    // and this call must not be revoked by a stale value. But a store that cannot answer is a
    // third case, and collapsing it into "expired" is what made a busy database log operators
    // out and fail plain reads - extending the sliding window is a write, and under a large scan
    // that write can lose the lock.
    return when (
      sessions.touchIfActive(
        tokenDigest = digest,
        accessedAtMillis = now,
        expiresAtMillis = now + inactivityTimeoutMillis,
      )
    ) {
      SessionTouch.TOUCHED -> user
      SessionTouch.EXPIRED -> {
        sessions.deleteExpired(now)
        null
      }
      // Proceeds on the session that was just read. The window ends up shorter than it would
      // have been and the next request extends it, which is the cheaper of the two mistakes.
      SessionTouch.UNAVAILABLE -> user
    }
  }

  fun invalidate(plainToken: String): Boolean =
    plainToken
      .takeIf(String::isNotBlank)
      ?.let(tokenEncoder::encode)
      ?.let(sessions::deleteByTokenDigest)
      ?: false

  fun deleteExpired(): Int = sessions.deleteExpired(now())

  private fun now(): Long =
    currentTimeMillis().also {
      require(it >= 0) { "Session lifecycle timestamp must not be negative" }
      require(it <= Long.MAX_VALUE - inactivityTimeoutMillis) {
        "Session expiry timestamp overflow"
      }
    }

  companion object {
    const val MAX_GENERATION_ATTEMPTS: Int = 10
    const val DEFAULT_SESSION_TOUCH_INTERVAL_MILLIS: Long = 60_000
  }
}
