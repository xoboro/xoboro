package io.xoboro.server.sources.local

import io.xoboro.server.media.ZipCentralDirectory
import io.xoboro.server.media.ZipDirectoryEntry
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalSourceRandomAccessTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `reads a range and reports the item size`() {
    val bytes = ByteArray(1_024) { (it % 251).toByte() }
    val file = tempDirectory.resolve("book.cbz").also { Files.write(it, bytes) }

    LocalSourceRandomAccess().open(tempDirectory.toUri().toString(), file.toUri().toString()).use { media ->
      assertEquals(1_024L, media.size)
      assertContentEquals(bytes.copyOfRange(100, 132), media.read(100, 32))
      assertContentEquals(bytes.copyOfRange(1_000, 1_024), media.read(1_000, 64), "a read past the end is truncated")
      assertTrue(media.read(1_024, 16).isEmpty(), "a read starting at the end is empty")
    }
  }

  @Test
  fun `reads a real archive's page list through the shared directory parser`() {
    val archive = cbz("book.cbz", mapOf("pages/001.jpg" to ByteArray(64) { 0x1 }, "pages/002.jpg" to ByteArray(64) { 0x2 }))

    val entries =
      LocalSourceRandomAccess()
        .open(tempDirectory.toUri().toString(), archive.toUri().toString())
        .use(ZipCentralDirectory::read)

    assertEquals(listOf("pages/001.jpg", "pages/002.jpg"), entries.map(ZipDirectoryEntry::name).sorted())
  }

  /** Same containment rule the materializing adapter enforces, since both resolve the same URIs. */
  @Test
  fun `refuses an item outside the library root`() {
    val outside = Files.createTempDirectory("outside-root")
    val file = outside.resolve("book.cbz").also { Files.write(it, ByteArray(16)) }

    assertFailsWith<IllegalArgumentException> {
      LocalSourceRandomAccess().open(tempDirectory.toUri().toString(), file.toUri().toString())
    }
  }

  @Test
  fun `refuses an item URI that is not a file URI`() {
    assertFailsWith<IllegalArgumentException> {
      LocalSourceRandomAccess().open(tempDirectory.toUri().toString(), "http://example.invalid/book.cbz")
    }
  }

  @Test
  fun `refuses a directory as an item`() {
    val directory = tempDirectory.resolve("nested").also(Files::createDirectory)

    assertFailsWith<IllegalArgumentException> {
      LocalSourceRandomAccess().open(tempDirectory.toUri().toString(), directory.toUri().toString())
    }
  }

  private fun cbz(
    fileName: String,
    entries: Map<String, ByteArray>,
  ): Path {
    val path = tempDirectory.resolve(fileName)
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      entries.forEach { (name, content) ->
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = content.size.toLong()
        entry.compressedSize = content.size.toLong()
        entry.crc = CRC32().apply { update(content) }.value
        output.putNextEntry(entry)
        output.write(content)
        output.closeEntry()
      }
    }
    return path
  }
}
