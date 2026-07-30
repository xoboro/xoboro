package io.xoboro.server.media

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

/**
 * Header flags a synthetic RAR4 archive can declare.
 *
 * [passwordProtected] sets `LHD_PASSWORD` on every entry - an archive shape no library in the
 * dependency set can write, which is why it is built from the format here.
 */
internal data class SyntheticRar4Flags(
  val passwordProtected: Boolean = false,
)

internal fun writeSyntheticRar4(
  path: Path,
  entries: Map<String, ByteArray>,
  flags: SyntheticRar4Flags = SyntheticRar4Flags(),
): Path {
  require(entries.isNotEmpty()) { "Synthetic RAR must contain at least one entry" }
  ByteArrayOutputStream().use { archive ->
    archive.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00))
    archive.writeRarHeader(
      byteArrayOf(
        0x73,
        0x00,
        0x00,
        0x0d,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
      ),
    )
    entries.forEach { (name, content) ->
      val encodedName = name.encodeToByteArray()
      require(encodedName.size <= UShort.MAX_VALUE.toInt()) { "RAR entry name is too long" }
      val fileCrc = CRC32().apply { update(content) }.value
      val entryFlags = FILE_LONG_BLOCK or if (flags.passwordProtected) FILE_PASSWORD else 0
      val declaredSize = content.size
      val header =
        ByteArrayOutputStream().use { output ->
          output.write(0x74)
          output.writeLittleEndian(entryFlags.toLong(), 2)
          output.writeLittleEndian((32 + encodedName.size).toLong(), 2)
          output.writeLittleEndian(declaredSize.toLong(), 4)
          output.writeLittleEndian(declaredSize.toLong(), 4)
          output.write(0x03)
          output.writeLittleEndian(fileCrc, 4)
          output.writeLittleEndian(0, 4)
          output.write(0x14)
          output.write(0x30)
          output.writeLittleEndian(encodedName.size.toLong(), 2)
          output.writeLittleEndian(0x20, 4)
          output.write(encodedName)
          output.toByteArray()
        }
      archive.writeRarHeader(header)
      archive.write(content)
    }
    archive.writeRarHeader(
      byteArrayOf(
        0x7b,
        0x00,
        0x40,
        0x07,
        0x00,
      ),
    )
    Files.write(path, archive.toByteArray())
  }
  return path
}

private const val FILE_LONG_BLOCK = 0x8000
private const val FILE_PASSWORD = 0x0004

private fun ByteArrayOutputStream.writeRarHeader(header: ByteArray) {
  val checksum = CRC32().apply { update(header) }.value and 0xffff
  writeLittleEndian(checksum, 2)
  write(header)
}

private fun ByteArrayOutputStream.writeLittleEndian(
  value: Long,
  bytes: Int,
) {
  repeat(bytes) { index ->
    write((value ushr (index * 8) and 0xff).toInt())
  }
}
