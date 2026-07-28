package io.xoboro.server.security

import com.password4j.BcryptFunction
import com.password4j.Password
import com.password4j.types.Bcrypt
import io.xoboro.core.application.PasswordHasher

class BCryptPasswordHasher(
  private val strength: Int = DEFAULT_STRENGTH,
) : PasswordHasher {
  private val function = BcryptFunction.getInstance(Bcrypt.A, strength)

  init {
    require(strength in 4..31) { "BCrypt strength must be between 4 and 31" }
  }

  override fun hash(rawPassword: String): String {
    require(rawPassword.isNotBlank()) { "Raw password must not be blank" }
    return Password.hash(rawPassword).with(function).result
  }

  override fun matches(
    rawPassword: String,
    passwordHash: String,
  ): Boolean {
    if (rawPassword.isBlank() || passwordHash.isBlank()) return false
    return runCatching {
      Password
        .check(rawPassword, passwordHash)
        .with(BcryptFunction.getInstanceFromHash(passwordHash))
    }.getOrDefault(false)
  }

  companion object {
    const val DEFAULT_STRENGTH: Int = 10
  }
}
