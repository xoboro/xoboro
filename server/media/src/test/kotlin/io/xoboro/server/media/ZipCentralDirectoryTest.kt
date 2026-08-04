package io.xoboro.server.media

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ZipCentralDirectoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  /**
   * Cross-checked against `ZipFile` rather than against hand-written expectations, because the risk
   * here is a wrong field offset and a wrong offset is exactly what a hand-written expectation would
   * be copied from.
   */
  @Test
  fun `reads the names and sizes ZipFile reports, for stored and deflated entries`() {
    val archive =
      writeJdkZip(
        "mixed.cbz",
        listOf(
          ZipFixture("pages/001.jpg", ByteArray(4_096) { it.toByte() }, ZipEntry.DEFLATED),
          ZipFixture("pages/002.jpg", ByteArray(2_048) { 0x42 }, ZipEntry.STORED),
          ZipFixture("ComicInfo.xml", "<ComicInfo/>".encodeToByteArray(), ZipEntry.DEFLATED),
          ZipFixture("nested/", ByteArray(0), ZipEntry.STORED),
        ),
      )
    val expected =
      ZipFile(archive.toFile()).use { zip ->
        zip.entries().asSequence().associate { it.name to it.size }
      }

    val media = ByteArrayRandomAccessMedia(Files.readAllBytes(archive))
    val entries = ZipCentralDirectory.read(media)

    assertEquals(expected, entries.associate { it.name to it.uncompressedSize })
    assertEquals(1, media.reads, "an ordinary archive's directory must arrive in one read")
    assertTrue(entries.none(ZipDirectoryEntry::encrypted))
    assertEquals(listOf("nested/"), entries.filter(ZipDirectoryEntry::isDirectory).map { it.name })
  }

  /**
   * The signature is four ordinary bytes, so one inside the archive comment sits *after* the real
   * end record and a backwards scan reaches it first. Only the declared comment length distinguishes
   * them.
   */
  @Test
  fun `ignores an end record signature planted inside the archive comment`() {
    val archive =
      writeJdkZip(
        "planted.cbz",
        listOf(ZipFixture("pages/001.jpg", ByteArray(64) { 0x11 }, ZipEntry.STORED)),
        comment = PLANTED_END_RECORD_COMMENT,
      )
    // Pins the fixture itself: if the comment ever stops carrying the signature, the test would pass
    // for the wrong reason.
    assertTrue(Files.readAllBytes(archive).containsEndRecordSignatureAfterIndex(END_RECORD_MINIMUM_INDEX))

    val entries = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(Files.readAllBytes(archive)))

    assertEquals(listOf("pages/001.jpg"), entries.map(ZipDirectoryEntry::name))
  }

  /** A directory too large for the prefetched trailer has to be fetched on its own. */
  @Test
  fun `reads a central directory that reaches back past the trailer window`() {
    val fixtures =
      (1..1_500).map { index ->
        ZipFixture(
          "pages/pageshavedeliberatelylongnamesinthisfixture-%05d.jpg".format(index),
          ByteArray(8) { 0x7 },
          ZipEntry.STORED,
        )
      }
    val archive = writeJdkZip("wide.cbz", fixtures)
    val media = ByteArrayRandomAccessMedia(Files.readAllBytes(archive))

    val entries = ZipCentralDirectory.read(media)

    assertEquals(1_500, entries.size)
    assertEquals("pages/pageshavedeliberatelylongnamesinthisfixture-00001.jpg", entries.first().name)
    assertEquals(2, media.reads, "a directory outside the trailer window costs one extra read")
  }

  @Test
  fun `reports entries the archive marks encrypted`() {
    val archive =
      writeSyntheticZip(
        tempDirectory.resolve("encrypted.cbz"),
        mapOf("pages/001.jpg" to ByteArray(32) { 0x5 }),
        encrypted = true,
      )

    val entries = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(Files.readAllBytes(archive)))

    assertTrue(entries.single().encrypted)
  }

  @Test
  fun `refuses content that is not a ZIP`() {
    val notAZip = ByteArray(4_096) { 0x2f }

    val failure =
      assertFailsWith<ZipDirectoryUnreadableException> {
        ZipCentralDirectory.read(ByteArrayRandomAccessMedia(notAZip))
      }

    assertTrue(failure.message!!.contains("end-of-central-directory"), failure.message)
  }

  @Test
  fun `refuses content shorter than an end record`() {
    assertFailsWith<ZipDirectoryUnreadableException> {
      ZipCentralDirectory.read(ByteArrayRandomAccessMedia(ByteArray(8)))
    }
  }

  /**
   * `ZipOutputStream` emits ZIP64 only past 4 GiB or 65,535 entries, neither of which a test can
   * afford to build, so the trailer is written by hand with the sentinels that select that branch and
   * small real sizes behind them.
   */
  @Test
  fun `reads sizes from the ZIP64 trailer when the 32-bit fields are sentinels`() {
    val archive =
      writeZip64Zip(
        tempDirectory.resolve("zip64.cbz"),
        mapOf(
          "pages/001.jpg" to ByteArray(1_234) { 0x21 },
          "pages/002.jpg" to ByteArray(5_678) { 0x22 },
        ),
      )

    val entries = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(Files.readAllBytes(archive)))

    assertEquals(
      mapOf("pages/001.jpg" to 1_234L, "pages/002.jpg" to 5_678L),
      entries.associate { it.name to it.uncompressedSize },
    )
  }

  @Test
  fun `refuses a truncated read of the trailer`() {
    val archive =
      writeJdkZip("short.cbz", listOf(ZipFixture("pages/001.jpg", ByteArray(64) { 0x11 }, ZipEntry.STORED)))
    val bytes = Files.readAllBytes(archive)

    val failure =
      assertFailsWith<ZipDirectoryUnreadableException> {
        ZipCentralDirectory.read(TruncatingRandomAccessMedia(bytes))
      }

    assertTrue(failure.message!!.contains("Short read"), failure.message)
  }

  private data class ZipFixture(
    val name: String,
    val content: ByteArray,
    val method: Int,
  )

  private fun writeJdkZip(
    fileName: String,
    fixtures: List<ZipFixture>,
    comment: String? = null,
  ): Path {
    val path = tempDirectory.resolve(fileName)
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      comment?.let(output::setComment)
      fixtures.forEach { fixture ->
        val entry = ZipEntry(fixture.name)
        entry.method = fixture.method
        if (fixture.method == ZipEntry.STORED) {
          entry.size = fixture.content.size.toLong()
          entry.compressedSize = fixture.content.size.toLong()
          entry.crc = CRC32().apply { update(fixture.content) }.value
        }
        output.putNextEntry(entry)
        output.write(fixture.content)
        output.closeEntry()
      }
    }
    return path
  }

  /** Returns one byte less than asked for, which is how a dropped transfer looks to the parser. */
  private class TruncatingRandomAccessMedia(
    private val bytes: ByteArray,
  ) : RandomAccessMedia {
    override val size: Long = bytes.size.toLong()

    override fun read(
      offset: Long,
      length: Int,
    ): ByteArray {
      val end = minOf(offset + length, size).toInt()
      return bytes.copyOfRange(offset.toInt(), (end - 1).coerceAtLeast(offset.toInt()))
    }

    override fun close() = Unit
  }
}

/**
 * A comment carrying the end-of-central-directory signature.
 *
 * Built from character codes rather than written as escapes so the bytes are unambiguous: all four
 * are below 0x80 and so encode to one byte each in UTF-8, which is what makes the planted sequence
 * really appear in the file.
 */
private val PLANTED_END_RECORD_COMMENT: String =
  String(charArrayOf('P', 'K', 5.toChar(), 6.toChar())) + " planted, with bytes following it"

/** Past the shortest possible real end record, so a hit here can only be the planted one. */
private const val END_RECORD_MINIMUM_INDEX = 22

private fun ByteArray.containsEndRecordSignatureAfterIndex(index: Int): Boolean {
  for (candidate in index until size - 3) {
    if (this[candidate] == 0x50.toByte() &&
      this[candidate + 1] == 0x4b.toByte() &&
      this[candidate + 2] == 0x05.toByte() &&
      this[candidate + 3] == 0x06.toByte()
    ) {
      return true
    }
  }
  return false
}

/**
 * Writes a stored-entry ZIP whose 32-bit trailer fields are all ZIP64 sentinels, with the real
 * counts and sizes in a ZIP64 end record, locator, and per-entry extra field.
 */
private fun writeZip64Zip(
  path: Path,
  entries: Map<String, ByteArray>,
): Path {
  require(entries.isNotEmpty()) { "ZIP64 fixture must contain at least one entry" }
  val local = ByteArrayOutputStream()
  val central = ByteArrayOutputStream()
  entries.forEach { (name, content) ->
    val encodedName = name.encodeToByteArray()
    val crc = CRC32().apply { update(content) }.value
    val offset = local.size().toLong()
    local.putLittleEndian(LOCAL_HEADER_SIGNATURE, 4)
    local.putLittleEndian(ZIP64_EXTRACT_VERSION, 2)
    local.putLittleEndian(0, 2)
    local.putLittleEndian(0, 2)
    local.putLittleEndian(0, 2)
    local.putLittleEndian(0, 2)
    local.putLittleEndian(crc, 4)
    local.putLittleEndian(content.size.toLong(), 4)
    local.putLittleEndian(content.size.toLong(), 4)
    local.putLittleEndian(encodedName.size.toLong(), 2)
    local.putLittleEndian(0, 2)
    local.write(encodedName)
    local.write(content)

    central.putLittleEndian(CENTRAL_HEADER_SIGNATURE, 4)
    central.putLittleEndian(ZIP64_EXTRACT_VERSION, 2)
    central.putLittleEndian(ZIP64_EXTRACT_VERSION, 2)
    central.putLittleEndian(0, 2)
    central.putLittleEndian(0, 2)
    central.putLittleEndian(0, 2)
    central.putLittleEndian(0, 2)
    central.putLittleEndian(crc, 4)
    central.putLittleEndian(SIZE_SENTINEL, 4)
    central.putLittleEndian(SIZE_SENTINEL, 4)
    central.putLittleEndian(encodedName.size.toLong(), 2)
    central.putLittleEndian(ZIP64_EXTRA_BYTES, 2)
    central.putLittleEndian(0, 2)
    central.putLittleEndian(0, 2)
    central.putLittleEndian(0, 2)
    central.putLittleEndian(0, 4)
    central.putLittleEndian(offset, 4)
    central.write(encodedName)
    // Only the two size fields overflowed above, so only those two appear here, in this order.
    central.putLittleEndian(ZIP64_EXTRA_ID, 2)
    central.putLittleEndian(ZIP64_EXTRA_BYTES - 4, 2)
    central.putLittleEndian(content.size.toLong(), 8)
    central.putLittleEndian(content.size.toLong(), 8)
  }
  ByteArrayOutputStream().use { archive ->
    archive.write(local.toByteArray())
    val directoryOffset = archive.size().toLong()
    val directorySize = central.size().toLong()
    archive.write(central.toByteArray())
    val zip64EndOffset = archive.size().toLong()
    archive.putLittleEndian(ZIP64_END_RECORD_SIGNATURE, 4)
    archive.putLittleEndian(ZIP64_END_RECORD_REMAINDER, 8)
    archive.putLittleEndian(ZIP64_EXTRACT_VERSION, 2)
    archive.putLittleEndian(ZIP64_EXTRACT_VERSION, 2)
    archive.putLittleEndian(0, 4)
    archive.putLittleEndian(0, 4)
    archive.putLittleEndian(entries.size.toLong(), 8)
    archive.putLittleEndian(entries.size.toLong(), 8)
    archive.putLittleEndian(directorySize, 8)
    archive.putLittleEndian(directoryOffset, 8)
    archive.putLittleEndian(ZIP64_LOCATOR_SIGNATURE, 4)
    archive.putLittleEndian(0, 4)
    archive.putLittleEndian(zip64EndOffset, 8)
    archive.putLittleEndian(1, 4)
    archive.putLittleEndian(END_RECORD_SIGNATURE, 4)
    archive.putLittleEndian(0, 2)
    archive.putLittleEndian(0, 2)
    archive.putLittleEndian(COUNT_SENTINEL, 2)
    archive.putLittleEndian(COUNT_SENTINEL, 2)
    archive.putLittleEndian(SIZE_SENTINEL, 4)
    archive.putLittleEndian(SIZE_SENTINEL, 4)
    archive.putLittleEndian(0, 2)
    Files.write(path, archive.toByteArray())
  }
  return path
}

private fun ByteArrayOutputStream.putLittleEndian(
  value: Long,
  bytes: Int,
) {
  repeat(bytes) { index ->
    write((value ushr (index * 8) and 0xff).toInt())
  }
}

private const val ZIP64_EXTRACT_VERSION = 45L
private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
private const val END_RECORD_SIGNATURE = 0x06054b50L
private const val ZIP64_END_RECORD_SIGNATURE = 0x06064b50L
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
private const val ZIP64_END_RECORD_REMAINDER = 44L
private const val ZIP64_EXTRA_ID = 0x0001L
private const val ZIP64_EXTRA_BYTES = 20L
private const val SIZE_SENTINEL = 0xFFFFFFFFL
private const val COUNT_SENTINEL = 0xFFFFL
