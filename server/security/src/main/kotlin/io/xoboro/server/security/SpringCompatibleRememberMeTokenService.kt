package io.xoboro.server.security

import io.xoboro.core.application.RememberMeTokenService
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class SpringCompatibleRememberMeTokenService(
  private val users: UserRepository,
  private val secretKey: String,
  private val currentTimeMillis: () -> Long,
  val tokenValidityMillis: Long,
) : RememberMeTokenService {
  init {
    require(secretKey.isNotBlank()) { "Remember-me secret must not be blank" }
    require(tokenValidityMillis > 0) { "Remember-me validity must be positive" }
  }

  override fun issue(user: User): String {
    val expiry = now() + tokenValidityMillis
    val signature = signature(user, expiry)
    return Base64.getEncoder().encodeToString(
      "${user.email}:$expiry:$ALGORITHM_NAME:$signature"
        .toByteArray(StandardCharsets.UTF_8),
    )
  }

  override fun authenticate(encodedToken: String): User? {
    if (encodedToken.isBlank()) return null
    val parts =
      runCatching {
        String(Base64.getDecoder().decode(encodedToken), StandardCharsets.UTF_8)
          .split(':')
      }.getOrNull() ?: return null
    if (parts.size != 4 || parts[2] != ALGORITHM_NAME) return null
    val expiry = parts[1].toLongOrNull() ?: return null
    if (expiry < now()) return null
    val user = users.findByEmailIgnoreCaseOrNull(parts[0]) ?: return null
    val expected = signature(user, expiry)
    return user.takeIf {
      MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.US_ASCII),
        parts[3].toByteArray(StandardCharsets.US_ASCII),
      )
    }
  }

  private fun signature(
    user: User,
    expiry: Long,
  ): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(
        "${user.email}:$expiry:${user.passwordHash}:$secretKey"
          .toByteArray(StandardCharsets.UTF_8),
      ).joinToString("") { byte -> "%02x".format(byte) }

  private fun now(): Long =
    currentTimeMillis().also {
      require(it >= 0) { "Remember-me timestamp must not be negative" }
      require(it <= Long.MAX_VALUE - tokenValidityMillis) {
        "Remember-me expiry timestamp overflow"
      }
    }

  companion object {
    const val ALGORITHM_NAME: String = "SHA256"
  }
}
