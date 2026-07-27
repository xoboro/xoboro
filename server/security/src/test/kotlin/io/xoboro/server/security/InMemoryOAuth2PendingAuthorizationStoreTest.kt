package io.xoboro.server.security

import io.xoboro.core.application.OAuth2PendingAuthorization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryOAuth2PendingAuthorizationStoreTest {
  @Test
  fun `atomically stores consumes and expires one-time state`() {
    val store = InMemoryOAuth2PendingAuthorizationStore()
    val first = pending("state-1", expiresAtMillis = 1_000)
    val second = pending("state-2", expiresAtMillis = 2_000)

    assertTrue(store.save(first))
    assertFalse(store.save(first))
    assertTrue(store.save(second))
    assertEquals(first, store.consume("state-1"))
    assertNull(store.consume("state-1"))
    assertEquals(0, store.deleteExpired(1_999))
    assertEquals(1, store.deleteExpired(2_000))
    assertEquals(0, store.size())
  }

  private fun pending(
    state: String,
    expiresAtMillis: Long,
  ): OAuth2PendingAuthorization =
    OAuth2PendingAuthorization(
      state = state,
      registrationId = "synthetic",
      redirectUri = "https://reader.example.invalid/login/oauth2/code/synthetic",
      browserBinding = "synthetic-browser-binding",
      nonce = "synthetic-nonce",
      expiresAtMillis = expiresAtMillis,
    )
}
