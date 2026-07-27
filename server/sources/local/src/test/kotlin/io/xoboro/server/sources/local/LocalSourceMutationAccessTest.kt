package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceCopyMode
import io.xoboro.core.application.SourceImportRequest
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalSourceMutationAccessTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `copies moves and hard links imports inside the library root`() {
    val root = Files.createDirectories(tempDirectory.resolve("library"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val sourceCopy = Files.writeString(tempDirectory.resolve("copy.cbz"), "copy")
    val sourceMove = Files.writeString(tempDirectory.resolve("move.cbz"), "move")
    val sourceLink = Files.writeString(tempDirectory.resolve("link.cbz"), "link")
    val access = LocalSourceMutationAccess()

    access.import(
      root.toUri().toString(),
      series.toUri().toString(),
      SourceImportRequest(sourceCopy.toString(), "copied.cbz", SourceCopyMode.COPY),
    )
    access.import(
      root.toUri().toString(),
      series.toUri().toString(),
      SourceImportRequest(sourceMove.toString(), "moved.cbz", SourceCopyMode.MOVE),
    )
    access.import(
      root.toUri().toString(),
      series.toUri().toString(),
      SourceImportRequest(sourceLink.toString(), "linked.cbz", SourceCopyMode.HARDLINK),
    )

    assertEquals("copy", Files.readString(series.resolve("copied.cbz")))
    assertEquals("move", Files.readString(series.resolve("moved.cbz")))
    assertTrue(!Files.exists(sourceMove))
    assertTrue(Files.isSameFile(sourceLink, series.resolve("linked.cbz")))
  }

  @Test
  fun `atomically replaces an upgrade and deletes contained media`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-upgrade"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val destination = Files.writeString(series.resolve("chapter.cbz"), "old")
    val source = Files.writeString(tempDirectory.resolve("upgrade.cbz"), "new")
    val access = LocalSourceMutationAccess()

    val imported =
      access.import(
        root.toUri().toString(),
        series.toUri().toString(),
        SourceImportRequest(
          sourceFile = source.toString(),
          destinationName = "chapter.cbz",
          copyMode = SourceCopyMode.COPY,
          replaceExisting = true,
        ),
      )

    assertEquals(destination.toRealPath(), Path.of(URI(imported)).toRealPath())
    assertEquals("new", Files.readString(destination))
    assertTrue(access.delete(root.toUri().toString(), destination.toUri().toString()))
    assertTrue(!Files.exists(destination))
  }

  @Test
  fun `rejects destination traversal and deletion outside the root`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-safe"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val source = Files.writeString(tempDirectory.resolve("outside.cbz"), "outside")
    val access = LocalSourceMutationAccess()

    assertFailsWith<IllegalArgumentException> {
      access.import(
        root.toUri().toString(),
        series.toUri().toString(),
        SourceImportRequest(source.toString(), "../escape.cbz", SourceCopyMode.COPY),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      access.delete(root.toUri().toString(), source.toUri().toString())
    }
    assertTrue(Files.exists(source))
  }
}
