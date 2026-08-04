package io.xoboro.server.media

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ZipRangedEntryReaderTest {
  @TempDir
  lateinit var tempDirectory: Path

  /**
   * Cross-checked against `ZipFile`'s own decompression of the same entry, so a mistake in the
   * offset arithmetic or the raw-inflate setup shows up as different bytes rather than as bytes that
   * merely look plausible.
   */
  @Test
  fun `reads stored and deflated entries byte for byte as ZipFile does`() {
    val deflatable = ByteArray(8_192) { (it % 17).toByte() }
    val stored = ByteArray(2_048) { (it % 251).toByte() }
    val archive =
      writeZip(
        "mixed.cbz",
        listOf(
          Fixture("pages/001.jpg", deflatable, ZipEntry.DEFLATED),
          Fixture("pages/002.jpg", stored, ZipEntry.STORED),
          Fixture("ComicInfo.xml", "<ComicInfo/>".encodeToByteArray(), ZipEntry.DEFLATED),
        ),
      )
    val bytes = Files.readAllBytes(archive)
    val entries = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(bytes)).associateBy { it.name }

    ZipFile(archive.toFile()).use { reference ->
      listOf("pages/001.jpg", "pages/002.jpg", "ComicInfo.xml").forEach { name ->
        val expected =
          reference.getInputStream(requireNotNull(reference.getEntry(name))).use { it.readBytes() }
        val media = ByteArrayRandomAccessMedia(bytes)

        val actual = ZipRangedEntryReader.read(media, requireNotNull(entries[name]))

        assertContentEquals(expected, actual, name)
        assertEquals(2, media.reads, "$name must cost one header read and one data read")
      }
    }
    assertTrue(requireNotNull(entries["pages/001.jpg"]).isDeflated)
    assertTrue(requireNotNull(entries["pages/002.jpg"]).isStored)
  }

  @Test
  fun `reads an entry whose local header carries a longer extra field than the central one`() {
    // `ZipOutputStream` writes an extended-timestamp extra field into the local header that the
    // central directory does not repeat, so the two lengths already differ here. Trusting the
    // central directory's length would start reading a few bytes into the image.
    val content = ByteArray(4_096) { (it % 29).toByte() }
    val archive = writeZip("timestamps.cbz", listOf(Fixture("pages/001.jpg", content, ZipEntry.STORED)))
    val bytes = Files.readAllBytes(archive)
    val entry = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(bytes)).single()

    val actual = ZipRangedEntryReader.read(ByteArrayRandomAccessMedia(bytes), entry)

    assertContentEquals(content, actual)
  }

  @Test
  fun `refuses an entry the archive marks encrypted`() {
    val archive =
      writeSyntheticZip(
        tempDirectory.resolve("encrypted.cbz"),
        mapOf("pages/001.jpg" to ByteArray(32) { 0x5 }),
        encrypted = true,
      )
    val bytes = Files.readAllBytes(archive)
    val entry = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(bytes)).single()

    val failure =
      assertFailsWith<ZipDirectoryUnreadableException> {
        ZipRangedEntryReader.read(ByteArrayRandomAccessMedia(bytes), entry)
      }

    assertTrue(failure.message!!.contains("encrypted"), failure.message)
  }

  @Test
  fun `refuses an entry whose recorded offset holds no local header`() {
    val archive = writeZip("shifted.cbz", listOf(Fixture("pages/001.jpg", ByteArray(64) { 0x3 }, ZipEntry.STORED)))
    val bytes = Files.readAllBytes(archive)
    val entry = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(bytes)).single()

    val failure =
      assertFailsWith<ZipDirectoryUnreadableException> {
        ZipRangedEntryReader.read(
          ByteArrayRandomAccessMedia(bytes),
          entry.copy(localHeaderOffset = entry.localHeaderOffset + 8),
        )
      }

    assertTrue(failure.message!!.contains("No ZIP local header"), failure.message)
  }

  @Test
  fun `refuses an entry using a compression method it cannot decode`() {
    val archive = writeZip("stored.cbz", listOf(Fixture("pages/001.jpg", ByteArray(64) { 0x3 }, ZipEntry.STORED)))
    val bytes = Files.readAllBytes(archive)
    val entry = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(bytes)).single()

    val failure =
      assertFailsWith<ZipDirectoryUnreadableException> {
        // 14 is LZMA: a real method this reader has no decoder for.
        ZipRangedEntryReader.read(ByteArrayRandomAccessMedia(bytes), entry.copy(compressionMethod = 14))
      }

    assertTrue(failure.message!!.contains("unsupported compression method"), failure.message)
  }

  @Test
  fun `refuses a directory entry`() {
    val archive = writeZip("dir.cbz", listOf(Fixture("nested/", ByteArray(0), ZipEntry.STORED)))
    val bytes = Files.readAllBytes(archive)
    val entry = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(bytes)).single()

    assertFailsWith<IllegalArgumentException> {
      ZipRangedEntryReader.read(ByteArrayRandomAccessMedia(bytes), entry)
    }
  }

  @Test
  fun `refuses an entry whose data runs past the end of the archive`() {
    val archive = writeZip("truncated.cbz", listOf(Fixture("pages/001.jpg", ByteArray(64) { 0x3 }, ZipEntry.STORED)))
    val bytes = Files.readAllBytes(archive)
    val entry = ZipCentralDirectory.read(ByteArrayRandomAccessMedia(bytes)).single()

    val failure =
      assertFailsWith<ZipDirectoryUnreadableException> {
        ZipRangedEntryReader.read(
          ByteArrayRandomAccessMedia(bytes),
          entry.copy(compressedSize = bytes.size.toLong()),
        )
      }

    assertTrue(failure.message!!.contains("falls outside"), failure.message)
  }

  private data class Fixture(
    val name: String,
    val content: ByteArray,
    val method: Int,
  )

  private fun writeZip(
    fileName: String,
    fixtures: List<Fixture>,
  ): Path {
    val path = tempDirectory.resolve(fileName)
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
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
}
