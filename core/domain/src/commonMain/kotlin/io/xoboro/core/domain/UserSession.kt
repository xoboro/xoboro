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

  fun update(session: UserSession)

  fun deleteByTokenDigest(tokenDigest: String): Boolean

  fun deleteByUserId(userId: UserId): Int

  fun deleteExpired(nowMillis: Long): Int
}
