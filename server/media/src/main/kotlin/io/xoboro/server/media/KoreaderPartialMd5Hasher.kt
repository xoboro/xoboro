package io.xoboro.server.media

import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Computes the sparse MD5 fingerprint used by KOReader.
 *
 * The offsets and full-buffer updates intentionally match KOReader and Komga,
 * including the retained bytes in a short final read.
 */
class KoreaderPartialMd5Hasher {
  fun hash(path: Path): String {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(BLOCK_SIZE)
    RandomAccessFile(path.toFile(), "r").use { file ->
      SAMPLE_EXPONENTS.forEach { exponent ->
        file.seek(STEP shl (2 * exponent))
        if (file.read(buffer) > 0) {
          digest.update(buffer)
        }
      }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private companion object {
    const val STEP: Long = 1_024
    const val BLOCK_SIZE: Int = 1_024
    val SAMPLE_EXPONENTS: IntRange = -1..10
  }
}
