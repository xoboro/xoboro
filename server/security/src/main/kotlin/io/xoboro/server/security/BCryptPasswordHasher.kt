package io.xoboro.server.security

import io.xoboro.core.application.PasswordHasher
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.BCryptVersion

class BCryptPasswordHasher(
  strength: Int = DEFAULT_STRENGTH,
) : PasswordHasher {
  private val encoder = BCryptPasswordEncoder(BCryptVersion.`$2A`, strength)

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
      BCrypt.checkpw(rawPassword, passwordHash)
    }.getOrDefault(false)
  }

  companion object {
    const val DEFAULT_STRENGTH: Int = 10
  }
}
