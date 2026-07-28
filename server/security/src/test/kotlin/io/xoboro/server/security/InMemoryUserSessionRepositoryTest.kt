package io.xoboro.server.security

import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryUserSessionRepositoryTest {
  @Test
  fun `inserts updates expires and revokes sessions atomically`() {
    val repository = InMemoryUserSessionRepository()
    val first = session("digest-1", "user-1", expiresAtMillis = 100)
    val second = session("digest-2", "user-1", expiresAtMillis = 200)
    val third = session("digest-3", "user-2", expiresAtMillis = 300)

    assertTrue(repository.insertIfAbsent(first))
    assertFalse(repository.insertIfAbsent(first))
    repository.insertIfAbsent(second)
    repository.insertIfAbsent(third)
    assertTrue(repository.touchIfActive("digest-1", 10, 110))
    assertEquals(110, repository.findByTokenDigestOrNull("digest-1")?.expiresAtMillis)
    assertTrue(repository.touchIfActive("digest-1", 5, 105))
    assertEquals(110, repository.findByTokenDigestOrNull("digest-1")?.expiresAtMillis)
    assertFalse(repository.touchIfActive("digest-1", 110, 210))

    assertEquals(1, repository.deleteExpired(150))
    assertNull(repository.findByTokenDigestOrNull("digest-1"))
    assertEquals(1, repository.deleteByUserId(UserId("user-1")))
    assertTrue(repository.deleteByTokenDigest("digest-3"))
    assertFalse(repository.deleteByTokenDigest("digest-3"))
  }

  private fun session(
    digest: String,
    userId: String,
    expiresAtMillis: Long,
  ): UserSession =
    UserSession(
      tokenDigest = digest,
      userId = UserId(userId),
      createdAtMillis = 0,
      expiresAtMillis = expiresAtMillis,
    )
}
