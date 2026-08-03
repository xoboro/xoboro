package io.xoboro.server.sources.local

import io.xoboro.core.application.RootType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalLibraryRootInspectorTest {
  @TempDir
  lateinit var tempDirectory: Path

  private val inspector = LocalLibraryRootInspector()

  @Test
  fun `classifies directories regular files and missing items`() {
    val directory = tempDirectory.resolve("library").createDirectory()
    val file = tempDirectory.resolve("book.cbz").createFile()
    val missing = tempDirectory.resolve("missing")

    assertEquals(RootType.DIRECTORY, inspector.typeOf(directory.toUri().toString()))
    assertEquals(RootType.FILE, inspector.typeOf(file.toUri().toString()))
    assertEquals(RootType.MISSING, inspector.typeOf(missing.toUri().toString()))
  }

  @Test
  fun `compares canonical parent child and sibling paths`() {
    val parent = tempDirectory.resolve("library").createDirectory()
    val child = parent.resolve("series").createDirectory()
    val sibling = tempDirectory.resolve("other").createDirectory()

    assertTrue(inspector.isSameOrAncestor(parent.uri(), parent.uri()))
    assertTrue(inspector.isSameOrAncestor(parent.uri(), child.uri()))
    assertFalse(inspector.isSameOrAncestor(child.uri(), parent.uri()))
    assertFalse(inspector.isSameOrAncestor(parent.uri(), sibling.uri()))
  }

  @Test
  fun `resolves symbolic links before checking overlap`() {
    val real = tempDirectory.resolve("real").createDirectory()
    val child = real.resolve("child").createDirectory()
    val alias = tempDirectory.resolve("alias")
    Files.createSymbolicLink(alias, real)

    assertTrue(inspector.isSameOrAncestor(alias.uri(), child.uri()))
    assertTrue(inspector.isSameOrAncestor(real.uri(), alias.uri()))
  }

  @Test
  fun `requires file URIs and existing paths for ancestry checks`() {
    assertFailsWith<InvalidLocalSourceItemException> {
      inspector.typeOf("https://example.invalid/library")
    }
    assertFailsWith<InvalidLocalSourceItemException> {
      inspector.typeOf(tempDirectory.toString())
    }
    assertFailsWith<InvalidLocalSourceItemException> {
      inspector.isSameOrAncestor(
        tempDirectory.resolve("missing").uri(),
        tempDirectory.uri(),
      )
    }
  }

  private fun Path.uri(): String = toUri().toString()

  @Test
  fun `reports an unparseable location as an invalid item rather than letting it escape`() {
    // An administrator creating a library posts this string, and the route turns
    // `IllegalArgumentException` into a 400. `URI("file:///tmp/has a space")` throws
    // `URISyntaxException`, which is neither caught here nor an `IllegalArgumentException`, so
    // it escaped as a 500 - observed against a running server for a path containing a space,
    // which is an ordinary thing for a series directory to contain.
    val inspector = LocalLibraryRootInspector()

    for (location in listOf("file:///tmp/has a space", "::::not-a-uri", "file:///tmp/bad%zz")) {
      assertFailsWith<InvalidLocalSourceItemException>(location) { inspector.typeOf(location) }
    }
  }
}
