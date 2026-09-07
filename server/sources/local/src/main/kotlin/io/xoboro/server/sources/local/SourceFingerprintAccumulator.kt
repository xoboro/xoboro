package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Constant-memory, iteration-order-independent fingerprint of inventory metadata. */
internal class SourceFingerprintAccumulator {
  private val aggregate = ByteArray(DIGEST_BYTES)
  private val itemDigest = MessageDigest.getInstance(ALGORITHM)
  private var count = 0L

  fun add(file: SourceFile) {
    itemDigest.reset()
    itemDigest.updateLengthPrefixed(file.relativePath)
    itemDigest.updateNullableLengthPrefixed(file.identity)
    itemDigest.updateLong(file.size)
    itemDigest.updateLong(file.modifiedAtMillis)
    itemDigest.digest().forEachIndexed { index, byte ->
      aggregate[index] = (aggregate[index].toInt() xor byte.toInt()).toByte()
    }
    count += 1
  }

  fun finish(): String {
    val digest = MessageDigest.getInstance(ALGORITHM)
    digest.update(aggregate)
    digest.updateLong(count)
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }

  private fun MessageDigest.updateLengthPrefixed(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    updateInt(bytes.size)
    update(bytes)
  }

  private fun MessageDigest.updateNullableLengthPrefixed(value: String?) {
    if (value == null) {
      updateInt(-1)
    } else {
      updateLengthPrefixed(value)
    }
  }

  private fun MessageDigest.updateInt(value: Int) {
    update((value ushr 24).toByte())
    update((value ushr 16).toByte())
    update((value ushr 8).toByte())
    update(value.toByte())
  }

  private fun MessageDigest.updateLong(value: Long) {
    for (shift in 56 downTo 0 step 8) update((value ushr shift).toByte())
  }

  private companion object {
    const val ALGORITHM = "SHA-256"
    const val DIGEST_BYTES = 32
  }
}
