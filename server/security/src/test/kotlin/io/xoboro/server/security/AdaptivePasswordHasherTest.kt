package io.xoboro.server.security

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdaptivePasswordHasherTest {
  private val hasher = AdaptivePasswordHasher()

  @Test
  fun `produces salted Argon2id hashes`() {
    val first = hasher.hash("synthetic-password")
    val second = hasher.hash("synthetic-password")

    assertTrue(first.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
    assertTrue(second.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
    assertEquals(
      AdaptivePasswordHasher.DEFAULT_SALT_LENGTH,
      Base64.getDecoder().decode(first.split('$')[4]).size,
    )
    assertNotEquals(first, second)
    assertTrue(hasher.matches("synthetic-password", first))
    assertTrue(hasher.matches("synthetic-password", second))
  }

  @Test
  fun `rejects incorrect blank and malformed credentials`() {
    val hash = hasher.hash("synthetic-password")

    assertFalse(hasher.matches("wrong-password", hash))
    assertFalse(hasher.matches("", hash))
    assertFalse(hasher.matches("synthetic-password", "not-a-bcrypt-hash"))
  }

  @Test
  fun `verifies and upgrades an existing Spring BCrypt password hash`() {
    val springHash = "\$2a\$10\$w9UjUcbPJ6Xu8R5UrlVX5OI0zix0wduRUs2uwz2i5QLR/5VltZXWS"
    val verification = hasher.verify("synthetic-password", springHash)

    assertTrue(verification.verified)
    assertTrue(requireNotNull(verification.replacementHash).startsWith("\$argon2id\$"))
    assertFalse(hasher.matches("wrong-password", springHash))
  }

  @Test
  fun `upgrades a verified Argon2id hash with outdated parameters`() {
    val outdated =
      AdaptivePasswordHasher(
        memoryKib = 7_168,
        iterations = 2,
        parallelism = 1,
        outputLength = 32,
      ).hash("synthetic-password")

    val verification = hasher.verify("synthetic-password", outdated)

    assertTrue(verification.verified)
    assertTrue(
      requireNotNull(verification.replacementHash)
        .startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"),
    )
  }
}
