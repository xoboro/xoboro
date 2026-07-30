package io.xoboro.server.media

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

/**
 * Writes a stored-entry ZIP container byte by byte, so a test can set the general-purpose flags it
 * needs. `ZipOutputStream` cannot mark an entry encrypted, and no ZIP library in the dependency set
 * can either, so the encrypted fixtures below are built from the format instead.
 *
 * The data of an "encrypted" entry is left as plain bytes. Nothing reads it: the JDK rejects such an
 * archive when it is opened, and [ZipEncryptionProbe] only looks at the central directory. Producing
 * real ZipCrypto ciphertext would add a cipher to the test with nothing depending on its output.
 */
internal fun writeSyntheticZip(
  path: Path,
  entries: Map<String, ByteArray>,
  encrypted: Boolean = false,
): Path {
  require(entries.isNotEmpty()) { "Synthetic ZIP must contain at least one entry" }
  val flags = if (encrypted) ENCRYPTED_FLAG else 0
  val local = ByteArrayOutputStream()
  val central = ByteArrayOutputStream()
  entries.forEach { (name, content) ->
    val encodedName = name.encodeToByteArray()
    val crc = CRC32().apply { update(content) }.value
    val offset = local.size().toLong()
    local.writeLocalHeader(encodedName, content, crc, flags)
    local.write(content)
    central.writeCentralHeader(encodedName, content, crc, flags, offset)
  }
  ByteArrayOutputStream().use { archive ->
    archive.write(local.toByteArray())
    val directoryOffset = archive.size().toLong()
    archive.write(central.toByteArray())
    archive.writeEndRecord(entries.size, central.size().toLong(), directoryOffset)
    Files.write(path, archive.toByteArray())
  }
  return path
}

private fun ByteArrayOutputStream.writeLocalHeader(
  encodedName: ByteArray,
  content: ByteArray,
  crc: Long,
  flags: Int,
) {
  writeLittleEndian(LOCAL_HEADER_SIGNATURE, 4)
  writeLittleEndian(EXTRACT_VERSION, 2)
  writeLittleEndian(flags.toLong(), 2)
  writeLittleEndian(STORED_METHOD, 2)
  writeLittleEndian(0, 2)
  writeLittleEndian(0, 2)
  writeLittleEndian(crc, 4)
  writeLittleEndian(content.size.toLong(), 4)
  writeLittleEndian(content.size.toLong(), 4)
  writeLittleEndian(encodedName.size.toLong(), 2)
  writeLittleEndian(0, 2)
  write(encodedName)
}

private fun ByteArrayOutputStream.writeCentralHeader(
  encodedName: ByteArray,
  content: ByteArray,
  crc: Long,
  flags: Int,
  offset: Long,
) {
  writeLittleEndian(CENTRAL_HEADER_SIGNATURE, 4)
  writeLittleEndian(EXTRACT_VERSION, 2)
  writeLittleEndian(EXTRACT_VERSION, 2)
  writeLittleEndian(flags.toLong(), 2)
  writeLittleEndian(STORED_METHOD, 2)
  writeLittleEndian(0, 2)
  writeLittleEndian(0, 2)
  writeLittleEndian(crc, 4)
  writeLittleEndian(content.size.toLong(), 4)
  writeLittleEndian(content.size.toLong(), 4)
  writeLittleEndian(encodedName.size.toLong(), 2)
  writeLittleEndian(0, 2)
  writeLittleEndian(0, 2)
  writeLittleEndian(0, 2)
  writeLittleEndian(0, 2)
  writeLittleEndian(0, 4)
  writeLittleEndian(offset, 4)
  write(encodedName)
}

private fun ByteArrayOutputStream.writeEndRecord(
  entryCount: Int,
  directorySize: Long,
  directoryOffset: Long,
) {
  writeLittleEndian(END_RECORD_SIGNATURE, 4)
  writeLittleEndian(0, 2)
  writeLittleEndian(0, 2)
  writeLittleEndian(entryCount.toLong(), 2)
  writeLittleEndian(entryCount.toLong(), 2)
  writeLittleEndian(directorySize, 4)
  writeLittleEndian(directoryOffset, 4)
  writeLittleEndian(0, 2)
}

private fun ByteArrayOutputStream.writeLittleEndian(
  value: Long,
  bytes: Int,
) {
  repeat(bytes) { index ->
    write((value ushr (index * 8) and 0xff).toInt())
  }
}

private const val ENCRYPTED_FLAG = 0x0001
private const val EXTRACT_VERSION = 20L
private const val STORED_METHOD = 0L
private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
private const val END_RECORD_SIGNATURE = 0x06054b50L
