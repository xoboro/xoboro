package io.xoboro.server.security

import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserSession
import io.xoboro.core.domain.UserSessionRepository
import java.util.concurrent.ConcurrentHashMap

class InMemoryUserSessionRepository : UserSessionRepository {
  private val sessions = ConcurrentHashMap<String, UserSession>()

  override fun findByTokenDigestOrNull(tokenDigest: String): UserSession? =
    sessions[tokenDigest]

  override fun insertIfAbsent(session: UserSession): Boolean =
    sessions.putIfAbsent(session.tokenDigest, session) == null

  override fun touchIfActive(
    tokenDigest: String,
    accessedAtMillis: Long,
    expiresAtMillis: Long,
  ): Boolean {
    var touched = false
    sessions.computeIfPresent(tokenDigest) { _, current ->
      if (current.expiresAtMillis <= accessedAtMillis) {
        current
      } else {
        touched = true
        current.copy(
          lastAccessedAtMillis = maxOf(current.lastAccessedAtMillis, accessedAtMillis),
          expiresAtMillis = maxOf(current.expiresAtMillis, expiresAtMillis),
        )
      }
    }
    return touched
  }

  override fun deleteByTokenDigest(tokenDigest: String): Boolean =
    sessions.remove(tokenDigest) != null

  override fun deleteByUserId(userId: UserId): Int {
    var deleted = 0
    sessions.entries.removeIf { entry ->
      (entry.value.userId == userId).also { removed ->
        if (removed) deleted++
      }
    }
    return deleted
  }

  override fun deleteExpired(nowMillis: Long): Int {
    var deleted = 0
    sessions.entries.removeIf { entry ->
      (entry.value.expiresAtMillis <= nowMillis).also { removed ->
        if (removed) deleted++
      }
    }
    return deleted
  }
}
