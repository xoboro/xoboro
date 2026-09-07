package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalSourceInventoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  private val inventory = LocalSourceInventory()

  @Test
  fun `streams file metadata with portable relative paths`() {
    val series = tempDirectory.resolve("Series").createDirectory()
    val book = series.resolve("Book.CBZ")
    book.writeText("synthetic")
    Files.setLastModifiedTime(book, FileTime.fromMillis(1_000L))
    val rootFile = tempDirectory.resolve("cover")
    rootFile.writeText("cover")
    val files = mutableListOf<SourceFile>()

    val summary = inventory.inventory(tempDirectory.uri(), onFile = files::add)

    assertEquals(listOf("Series/Book.CBZ", "cover"), files.map(SourceFile::relativePath).sorted())
    val scannedBook = files.single { it.name == "Book.CBZ" }
    assertEquals("cbz", scannedBook.extension)
    assertEquals(9L, scannedBook.size)
    assertEquals(1_000L, scannedBook.modifiedAtMillis)
    assertEquals(2L, summary.visitedDirectories)
    assertEquals(2L, summary.emittedFiles)
    assertEquals(0L, summary.failedEntries)
  }

  @Test
  fun `prunes hidden and case-insensitively excluded directory subtrees`() {
    tempDirectory.resolve(".hidden").createDirectory().resolve("hidden.cbz").createFile()
    tempDirectory.resolve("CacheFolder").createDirectory().resolve("cached.cbz").createFile()
    tempDirectory.resolve("Visible").createDirectory().resolve("visible.cbz").createFile()
    val files = mutableListOf<SourceFile>()

    val summary =
      inventory.inventory(
        rootItemId = tempDirectory.uri(),
        directoryExclusions = setOf("cache"),
        onFile = files::add,
      )

    assertEquals(listOf("Visible/visible.cbz"), files.map(SourceFile::relativePath))
    assertEquals(2L, summary.visitedDirectories)
    assertEquals(2L, summary.skippedDirectories)
  }

  @Test
  fun `skips hidden files without reading their content`() {
    tempDirectory.resolve(".metadata").writeText("private")
    tempDirectory.resolve("book.cbz").writeText("synthetic")
    val files = mutableListOf<SourceFile>()

    inventory.inventory(tempDirectory.uri(), onFile = files::add)

    assertEquals(listOf("book.cbz"), files.map(SourceFile::name))
  }

  @Test
  fun `follows a directory symbolic link using its library-relative alias`() {
    val external = tempDirectory.resolve("external").createDirectory()
    external.resolve("book.cbz").createFile()
    val root = tempDirectory.resolve("root").createDirectory()
    Files.createSymbolicLink(root.resolve("linked"), external)
    val files = mutableListOf<SourceFile>()

    inventory.inventory(root.uri(), onFile = files::add)

    assertEquals(listOf("linked/book.cbz"), files.map(SourceFile::relativePath))
    assertTrue(files.single().itemId.endsWith("/root/linked/book.cbz"))
  }

  @Test
  fun `rejects inaccessible roots invalid schemes and blank exclusions`() {
    assertFailsWith<LocalInventoryUnavailableException> {
      inventory.inventory(tempDirectory.resolve("missing").uri(), onFile = {})
    }
    assertFailsWith<InvalidLocalSourceItemException> {
      inventory.inventory("https://example.invalid/library", onFile = {})
    }
    assertFailsWith<IllegalArgumentException> {
      inventory.inventory(tempDirectory.uri(), directoryExclusions = setOf(""), onFile = {})
    }
  }

  @Test
  fun `fingerprint ignores hidden and excluded entries but changes with visible metadata`() {
    val visible = tempDirectory.resolve("Visible").createDirectory().resolve("book.cbz")
    visible.writeText("first")
    Files.setLastModifiedTime(visible, FileTime.fromMillis(1_000L))
    val hidden = tempDirectory.resolve(".hidden").createDirectory().resolve("ignored.cbz")
    hidden.writeText("hidden")
    val excluded = tempDirectory.resolve("Cache").createDirectory().resolve("ignored.cbz")
    excluded.writeText("cached")

    val baseline = inventory.fingerprint(tempDirectory.uri(), setOf("cache"))

    hidden.writeText("hidden changed")
    excluded.writeText("cached changed")
    assertEquals(baseline, inventory.fingerprint(tempDirectory.uri(), setOf("cache")))

    visible.writeText("visible changed")
    Files.setLastModifiedTime(visible, FileTime.fromMillis(2_000L))
    assertNotEquals(baseline, inventory.fingerprint(tempDirectory.uri(), setOf("cache")))
  }

  @Test
  fun `inventory summary carries the fingerprint from the same walk`() {
    tempDirectory.resolve("book.cbz").writeText("synthetic")

    val summary = inventory.inventory(tempDirectory.uri(), onFile = {})

    assertEquals(inventory.fingerprint(tempDirectory.uri()).value, summary.fingerprint)
  }

  private fun Path.uri(): String = toUri().toString()
}
