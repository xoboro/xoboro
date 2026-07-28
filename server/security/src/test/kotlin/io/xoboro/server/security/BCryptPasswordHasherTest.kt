package io.xoboro.server.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BCryptPasswordHasherTest {
  private val hasher = BCryptPasswordHasher()

  @Test
  fun `produces salted Komga-compatible BCrypt hashes`() {
    val first = hasher.hash("synthetic-password")
    val second = hasher.hash("synthetic-password")

    assertTrue(first.startsWith("\$2a\$10\$"))
    assertTrue(second.startsWith("\$2a\$10\$"))
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
  fun `verifies an existing Spring BCrypt password hash`() {
    val springHash = "\$2a\$10\$w9UjUcbPJ6Xu8R5UrlVX5OI0zix0wduRUs2uwz2i5QLR/5VltZXWS"

    assertTrue(hasher.matches("synthetic-password", springHash))
    assertFalse(hasher.matches("wrong-password", springHash))
  }
}
