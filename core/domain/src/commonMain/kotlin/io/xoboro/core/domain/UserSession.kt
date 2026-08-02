package io.xoboro.core.domain

data class UserSession(
  val tokenDigest: String,
  val userId: UserId,
  val createdAtMillis: Long,
  val lastAccessedAtMillis: Long = createdAtMillis,
  val expiresAtMillis: Long,
) {
  init {
    require(tokenDigest.isNotBlank()) { "Session token digest must not be blank" }
    require(createdAtMillis >= 0) { "Session creation timestamp must not be negative" }
    require(lastAccessedAtMillis >= createdAtMillis) {
      "Session access timestamp must not precede creation"
    }
    require(expiresAtMillis >= lastAccessedAtMillis) {
      "Session expiry must not precede last access"
    }
  }
}

interface UserSessionRepository {
  fun findByTokenDigestOrNull(tokenDigest: String): UserSession?

  fun insertIfAbsent(session: UserSession): Boolean

  /**
   * Records an access against a session and extends its sliding window.
   *
   * Three outcomes, and the third is why this does not return a boolean. [SessionTouch.EXPIRED]
   * means the store looked and the session is gone or past its expiry - a revocation.
   * [SessionTouch.UNAVAILABLE] means the store could not answer, which is not a revocation and
   * must not be treated as one: a busy database would otherwise log an operator out, or fail an
   * otherwise valid read that only became a write because sessions have a sliding window.
   */
  fun touchIfActive(
    tokenDigest: String,
    accessedAtMillis: Long,
    expiresAtMillis: Long,
  ): SessionTouch

  fun deleteByTokenDigest(tokenDigest: String): Boolean

  fun deleteByUserId(userId: UserId): Int

  fun deleteExpired(nowMillis: Long): Int
}

/** The outcome of recording an access against a session. See [UserSessionRepository.touchIfActive]. */
enum class SessionTouch {
  TOUCHED,
  EXPIRED,

  /**
   * The store could not record the access. The session's validity is unchanged and unknown to
   * this call; the caller should proceed on what it already read rather than revoke anything.
   */
  UNAVAILABLE,
}
