package io.xoboro.server.security

import io.xoboro.core.application.OAuth2PendingAuthorization
import io.xoboro.core.application.OAuth2PendingAuthorizationStore
import java.util.concurrent.ConcurrentHashMap

class InMemoryOAuth2PendingAuthorizationStore : OAuth2PendingAuthorizationStore {
  private val values = ConcurrentHashMap<String, OAuth2PendingAuthorization>()

  override fun save(pending: OAuth2PendingAuthorization): Boolean =
    values.putIfAbsent(pending.state, pending) == null

  override fun consume(state: String): OAuth2PendingAuthorization? = values.remove(state)

  override fun deleteExpired(cutoffMillis: Long): Int {
    var deleted = 0
    values.forEach { (state, pending) ->
      if (pending.expiresAtMillis <= cutoffMillis && values.remove(state, pending)) {
        deleted += 1
      }
    }
    return deleted
  }

  fun size(): Int = values.size
}
