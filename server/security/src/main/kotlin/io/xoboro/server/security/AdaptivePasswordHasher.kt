package io.xoboro.server.security

import com.password4j.Argon2Function
import com.password4j.BcryptFunction
import com.password4j.Password
import com.password4j.types.Argon2
import io.xoboro.core.application.PasswordHasher
import io.xoboro.core.application.PasswordVerification

class AdaptivePasswordHasher(
  memoryKib: Int = DEFAULT_MEMORY_KIB,
  iterations: Int = DEFAULT_ITERATIONS,
  parallelism: Int = DEFAULT_PARALLELISM,
  outputLength: Int = DEFAULT_OUTPUT_LENGTH,
) : PasswordHasher {
  private val function =
    Argon2Function.getInstance(
      memoryKib,
      iterations,
      parallelism,
      outputLength,
      Argon2.ID,
    )

  override fun hash(rawPassword: String): String {
    require(rawPassword.isNotBlank()) { "Raw password must not be blank" }
    return hashWithCurrentFunction(rawPassword)
  }

  override fun matches(
    rawPassword: String,
    passwordHash: String,
  ): Boolean = verify(rawPassword, passwordHash).verified

  override fun verify(
    rawPassword: String,
    passwordHash: String,
  ): PasswordVerification {
    if (rawPassword.isBlank() || passwordHash.isBlank()) {
      return PasswordVerification(verified = false)
    }
    return runCatching {
      when {
        passwordHash.startsWith(ARGON2_PREFIX) -> verifyArgon2(rawPassword, passwordHash)
        BCRYPT_PATTERN.matches(passwordHash) -> verifyBcrypt(rawPassword, passwordHash)
        else -> PasswordVerification(verified = false)
      }
    }.getOrDefault(PasswordVerification(verified = false))
  }

  private fun verifyArgon2(
    rawPassword: String,
    passwordHash: String,
  ): PasswordVerification {
    val storedFunction = Argon2Function.getInstanceFromHash(passwordHash)
    val verified = Password.check(rawPassword, passwordHash).with(storedFunction)
    return PasswordVerification(
      verified = verified,
      replacementHash =
        if (verified && storedFunction != function) {
          hashWithCurrentFunction(rawPassword)
        } else {
          null
        },
    )
  }

  private fun verifyBcrypt(
    rawPassword: String,
    passwordHash: String,
  ): PasswordVerification {
    val legacyFunction = BcryptFunction.getInstanceFromHash(passwordHash)
    val verified = Password.check(rawPassword, passwordHash).with(legacyFunction)
    return PasswordVerification(
      verified = verified,
      replacementHash = if (verified) hashWithCurrentFunction(rawPassword) else null,
    )
  }

  private fun hashWithCurrentFunction(rawPassword: String): String =
    Password.hash(rawPassword)
      .addRandomSalt(DEFAULT_SALT_LENGTH)
      .with(function)
      .result

  companion object {
    const val DEFAULT_SALT_LENGTH: Int = 16
    const val DEFAULT_MEMORY_KIB: Int = 19_456
    const val DEFAULT_ITERATIONS: Int = 2
    const val DEFAULT_PARALLELISM: Int = 1
    const val DEFAULT_OUTPUT_LENGTH: Int = 32

    private const val ARGON2_PREFIX = "\$argon2"
    private val BCRYPT_PATTERN = Regex("""^\$2[abxy]?\$\d{2}\$.+""")
  }
}
