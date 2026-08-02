package io.xoboro.server.sources.webdav

import io.xoboro.core.application.SourceFile
import io.xoboro.core.application.SourceInventoryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavSourceInventoryTest {
  @Test
  fun `lists nested directories and reports their files`() {
    val tree =
      FixtureDirectory(
        "dav",
        mutableListOf(
          FixtureDirectory(
            "Alpha",
            mutableListOf(
              FixtureFile("one.cbz", ByteArray(10)),
              FixtureDirectory("Extras", mutableListOf(FixtureFile("bonus.cbz", ByteArray(5)))),
            ),
          ),
          FixtureFile("cover.jpg", ByteArray(3)),
        ),
      )
    FakeWebDavServer(tree).use { server ->
      val inventory = WebDavSourceInventory()
      val files = mutableListOf<SourceFile>()
      val summary = inventory.inventory(server.baseUrl, emptySet(), onFile = { files += it })

      assertEquals(3, files.size)
      val byRelativePath = files.associateBy(SourceFile::relativePath)
      assertTrue("Alpha/one.cbz" in byRelativePath)
      assertTrue("Alpha/Extras/bonus.cbz" in byRelativePath)
      assertTrue("cover.jpg" in byRelativePath)
      assertEquals("cbz", byRelativePath.getValue("Alpha/one.cbz").extension)
      assertEquals(10L, byRelativePath.getValue("Alpha/one.cbz").size)
      assertEquals(3L, summary.visitedDirectories) // dav root, Alpha, Alpha/Extras
      assertEquals(3L, summary.emittedFiles)
      assertEquals(0L, summary.failedEntries)
    }
  }

  @Test
  fun `percent-decodes non-ASCII directory and file names`() {
    val tree =
      FixtureDirectory(
        "dav",
        mutableListOf(
          FixtureDirectory(
            "Séries Café",
            mutableListOf(FixtureFile("Épisode один.cbz", ByteArray(4))),
          ),
        ),
      )
    FakeWebDavServer(tree).use { server ->
      val inventory = WebDavSourceInventory()
      val files = mutableListOf<SourceFile>()
      inventory.inventory(server.baseUrl, emptySet(), onFile = { files += it })

      assertEquals(1, files.size)
      val file = files.single()
      assertEquals("Épisode один.cbz", file.name)
      assertEquals("Séries Café/Épisode один.cbz", file.relativePath)
      assertTrue(file.itemId.startsWith(server.baseUrl))
    }
  }

  @Test
  fun `an entry omitting getcontentlength is reported with size zero rather than failing the listing`() {
    val tree =
      FixtureDirectory(
        "dav",
        mutableListOf(FixtureFile("mystery.cbz", ByteArray(42), omitContentLength = true)),
      )
    FakeWebDavServer(tree).use { server ->
      val inventory = WebDavSourceInventory()
      val files = mutableListOf<SourceFile>()
      inventory.inventory(server.baseUrl, emptySet(), onFile = { files += it })

      assertEquals(1, files.size)
      assertEquals(0L, files.single().size)
    }
  }

  @Test
  fun `skips hidden and excluded directory subtrees`() {
    val tree =
      FixtureDirectory(
        "dav",
        mutableListOf(
          FixtureDirectory(".hidden", mutableListOf(FixtureFile("secret.cbz", ByteArray(1)))),
          FixtureDirectory("Trash-Bin", mutableListOf(FixtureFile("junk.cbz", ByteArray(1)))),
          FixtureFile("keep.cbz", ByteArray(1)),
        ),
      )
    FakeWebDavServer(tree).use { server ->
      val inventory = WebDavSourceInventory()
      val files = mutableListOf<SourceFile>()
      val summary = inventory.inventory(server.baseUrl, setOf("trash"), onFile = { files += it })

      assertEquals(listOf("keep.cbz"), files.map(SourceFile::relativePath))
      assertEquals(2L, summary.skippedDirectories)
    }
  }

  @Test
  fun `reports a directory PROPFIND failure without aborting the rest of the walk`() {
    val tree =
      FixtureDirectory(
        "dav",
        mutableListOf(
          FixtureDirectory("Broken", mutableListOf(FixtureFile("unreachable.cbz", ByteArray(1)))),
          FixtureFile("keep.cbz", ByteArray(1)),
        ),
      )
    FakeWebDavServer(tree, failingPropfindPaths = setOf("Broken")).use { server ->
      val inventory = WebDavSourceInventory()
      val failures = mutableListOf<SourceInventoryFailure>()
      val files = mutableListOf<SourceFile>()
      val summary =
        inventory.inventory(
          server.baseUrl,
          emptySet(),
          onFile = { files += it },
          onFailure = { failures += it },
        )
      assertEquals(listOf("keep.cbz"), files.map(SourceFile::relativePath))
      assertEquals(1, failures.size)
      assertEquals(1L, summary.failedEntries)
    }
  }

  @Test
  fun `an unreadable root throws instead of returning an empty summary`() {
    FakeWebDavServer(FixtureDirectory("dav")).use { server ->
      val inventory = WebDavSourceInventory()
      assertFailsWith<WebDavInventoryUnavailableException> {
        inventory.inventory("${server.baseUrl}/does-not-exist", emptySet(), onFile = {})
      }
    }
  }
}
