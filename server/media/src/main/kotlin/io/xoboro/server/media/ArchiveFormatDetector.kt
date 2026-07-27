package io.xoboro.server.media

import java.nio.file.Files
import java.nio.file.Path

enum class ArchiveFormat(
  val canonicalExtension: String,
  val mediaType: String,
) {
  ZIP("cbz", ZipMediaAnalyzer.ZIP_MEDIA_TYPE),
  RAR4("cbr", RarMediaAnalyzer.RAR_MEDIA_TYPE),
  RAR5("cbr", RarMediaAnalyzer.RAR_MEDIA_TYPE),
  ;

  val isRar: Boolean
    get() = this == RAR4 || this == RAR5
}

class ArchiveFormatDetector {
  fun detect(path: Path): ArchiveFormat? {
    val signature = ByteArray(MAXIMUM_SIGNATURE_SIZE)
    val read =
      Files.newInputStream(path).buffered().use { input ->
        input.readNBytes(signature, 0, signature.size)
      }
    return when {
      signature.startsWith(ZIP_LOCAL_FILE, read) ||
        signature.startsWith(ZIP_EMPTY, read) ||
        signature.startsWith(ZIP_SPANNED, read) -> ArchiveFormat.ZIP
      signature.startsWith(RAR5, read) -> ArchiveFormat.RAR5
      signature.startsWith(RAR4, read) -> ArchiveFormat.RAR4
      else -> null
    }
  }

  private fun ByteArray.startsWith(
    expected: ByteArray,
    available: Int,
  ): Boolean =
    available >= expected.size &&
      expected.indices.all { index -> this[index] == expected[index] }

  private companion object {
    val ZIP_LOCAL_FILE = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    val ZIP_EMPTY = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
    val ZIP_SPANNED = byteArrayOf(0x50, 0x4b, 0x07, 0x08)
    val RAR4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)
    val RAR5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)
    const val MAXIMUM_SIGNATURE_SIZE = 8
  }
}
