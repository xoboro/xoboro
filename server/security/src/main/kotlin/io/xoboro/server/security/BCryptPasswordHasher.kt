package io.xoboro.server.security

import io.xoboro.core.application.PasswordHasher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class BCryptPasswordHasher(
  strength: Int = DEFAULT_STRENGTH,
) : PasswordHasher {
  private val encoder = BCryptPasswordEncoder(strength)

  init {
    require(strength in 4..31) { "BCrypt strength must be between 4 and 31" }
  }

  override fun hash(rawPassword: String): String {
    require(rawPassword.isNotBlank()) { "Raw password must not be blank" }
    return requireNotNull(encoder.encode(rawPassword)) {
      "BCrypt encoder returned no password hash"
    }
  }

  override fun matches(
    rawPassword: String,
    passwordHash: String,
  ): Boolean {
    if (rawPassword.isBlank() || passwordHash.isBlank()) return false
    return runCatching {
      encoder.matches(rawPassword, passwordHash)
    }.getOrDefault(false)
  }

  companion object {
    const val DEFAULT_STRENGTH: Int = 10
  }
}
