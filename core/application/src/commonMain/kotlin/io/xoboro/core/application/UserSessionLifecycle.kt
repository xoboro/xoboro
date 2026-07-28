package io.xoboro.core.application

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
) {
  init {
    require(inactivityTimeoutMillis > 0) { "Session inactivity timeout must be positive" }
  }

  fun create(user: User): CreatedUserSession {
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
      if (sessions.insertIfAbsent(session)) {
        return CreatedUserSession(session, plainToken)
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
    val touched =
      sessions.touchIfActive(
        tokenDigest = digest,
        accessedAtMillis = now,
        expiresAtMillis = now + inactivityTimeoutMillis,
      )
    if (!touched) sessions.deleteExpired(now)
    return user.takeIf { touched }
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
  }
}
