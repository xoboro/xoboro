package io.xoboro.server.media

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class RarToCbzConverterTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `converts RAR entries into a bounded CBZ without extraction`() {
    val entries =
      linkedMapOf(
        "pages/001.bin" to byteArrayOf(1, 2, 3),
        "metadata.xml" to "<metadata/>".encodeToByteArray(),
      )
    val source =
      writeSyntheticRar4(
        temporaryDirectory.resolve("synthetic.cbr"),
        entries,
      )
    val destination = temporaryDirectory.resolve("synthetic.cbz")

    val result = RarToCbzConverter().convert(source, destination)

    assertEquals(entries.size, result.entryCount)
    assertEquals(entries.values.sumOf(ByteArray::size).toLong(), result.expandedBytes)
    ZipFile(destination.toFile()).use { archive ->
      assertEquals(entries.keys.toList(), archive.entries().asSequence().map { it.name }.toList())
      entries.forEach { (name, expected) ->
        assertContentEquals(
          expected,
          archive.getInputStream(requireNotNull(archive.getEntry(name))).use { it.readAllBytes() },
        )
      }
    }
  }

  @Test
  fun `deletes a partial destination when conversion limits fail`() {
    val source =
      writeSyntheticRar4(
        temporaryDirectory.resolve("oversized.cbr"),
        mapOf("page.bin" to byteArrayOf(1, 2)),
      )
    val destination = temporaryDirectory.resolve("partial.cbz")

    assertFailsWith<IllegalArgumentException> {
      RarToCbzConverter(maximumExpandedBytes = 1).convert(source, destination)
    }

    assertFalse(Files.exists(destination))
  }
}
