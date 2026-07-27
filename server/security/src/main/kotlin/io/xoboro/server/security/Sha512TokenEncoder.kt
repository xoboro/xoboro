package io.xoboro.server.security

import io.xoboro.core.application.TokenEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class Sha512TokenEncoder : TokenEncoder {
  override fun encode(rawToken: String): String =
    MessageDigest
      .getInstance(ALGORITHM)
      .digest(rawToken.toByteArray(StandardCharsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }

  private companion object {
    const val ALGORITHM: String = "SHA-512"
  }
}
