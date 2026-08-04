package io.xoboro.server.sources.webdav

import io.xoboro.server.media.ZipCentralDirectory
import io.xoboro.server.media.ZipDirectoryEntry
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavSourceRandomAccessTest {
  /**
   * The end-to-end claim, over a real HTTP round trip: an archive's page list costs one request and
   * the archive itself is never transferred.
   */
  @Test
  fun `reads a real archive's page list with a single range request`() {
    val archive = cbz(mapOf("pages/001.jpg" to ByteArray(4_096) { 0x1 }, "pages/002.jpg" to ByteArray(4_096) { 0x2 }))
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", archive, etag = "v1")))
    FakeWebDavServer(tree).use { server ->
      val itemUrl = "${server.baseUrl}/book.cbz"

      val entries =
        WebDavSourceRandomAccess().open(server.baseUrl, itemUrl).use { media ->
          assertEquals(archive.size.toLong(), media.size)
          ZipCentralDirectory.read(media)
        }

      assertEquals(
        listOf("pages/001.jpg", "pages/002.jpg"),
        entries.map(ZipDirectoryEntry::name).sorted(),
      )
      assertEquals(1, server.rangeRequestCount("/dav/book.cbz"))
      assertEquals(0, server.getRequestCount("/dav/book.cbz"), "the whole archive must never be fetched")
    }
  }

  @Test
  fun `serves the trailer from the opening response without a further request`() {
    val bytes = ByteArray(1_024) { it.toByte() }
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", bytes)))
    FakeWebDavServer(tree).use { server ->
      WebDavSourceRandomAccess().open(server.baseUrl, "${server.baseUrl}/book.cbz").use { media ->
        // The whole item is inside the prefetched trailer for a file this small.
        assertContentEquals(bytes.copyOfRange(1_000, 1_024), media.read(1_000, 24))
        assertContentEquals(bytes.copyOfRange(0, 16), media.read(0, 16))
      }

      assertEquals(1, server.rangeRequestCount("/dav/book.cbz"))
    }
  }

  @Test
  fun `fetches a range that falls before the prefetched trailer`() {
    // Larger than the trailer window, so an early offset cannot be answered from the opening read.
    val bytes = ByteArray(ZipCentralDirectory.TRAILER_SEARCH_BYTES + 4_096) { (it % 251).toByte() }
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", bytes)))
    FakeWebDavServer(tree).use { server ->
      WebDavSourceRandomAccess().open(server.baseUrl, "${server.baseUrl}/book.cbz").use { media ->
        assertEquals(bytes.size.toLong(), media.size)
        assertContentEquals(bytes.copyOfRange(100, 132), media.read(100, 32))
      }

      assertEquals(2, server.rangeRequestCount("/dav/book.cbz"))
    }
  }

  @Test
  fun `reports a server that ignores Range rather than treating the whole body as a range`() {
    val bytes = ByteArray(4_096) { 0x7 }
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", bytes)))
    FakeWebDavServer(tree, ignoreRangeRequests = true).use { server ->
      val failure =
        assertFailsWith<WebDavRangeUnsupportedException> {
          WebDavSourceRandomAccess().open(server.baseUrl, "${server.baseUrl}/book.cbz")
        }

      assertTrue(failure.message!!.contains("ignored the Range header"), failure.message)
    }
  }

  @Test
  fun `refuses an item URL outside the library root`() {
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", ByteArray(64))))
    FakeWebDavServer(tree).use { server ->
      val outsideUrl = server.baseUrl.substringBeforeLast("/dav") + "/other/book.cbz"

      assertFailsWith<IllegalArgumentException> {
        WebDavSourceRandomAccess().open(server.baseUrl, outsideUrl)
      }
    }
  }

  /**
   * Same origin check the materializing adapter carries: a matching path prefix on another host must
   * not be fetched with this library's credentials.
   */
  @Test
  fun `refuses an item on a different host even when its path looks like the root`() {
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", ByteArray(64))))
    FakeWebDavServer(tree).use { server ->
      val access =
        WebDavSourceRandomAccess(
          environment =
            mapOf(
              "XOBORO_WEBDAV_USERNAME" to "alice",
              "XOBORO_WEBDAV_PASSWORD" to "s3cr3t-password",
            ),
        )
      val elsewhere = server.baseUrl.replace("127.0.0.1", "localhost") + "/book.cbz"

      assertFailsWith<IllegalArgumentException> { access.open(server.baseUrl, elsewhere) }
    }
  }

  @Test
  fun `sends the configured credentials on a range request`() {
    val credentials = WebDavCredentials("alice", "s3cr3t-password")
    val bytes = ByteArray(512) { 0x9 }
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", bytes)))
    FakeWebDavServer(tree, credentials = credentials).use { server ->
      val environment =
        mapOf(
          "XOBORO_WEBDAV_USERNAME" to credentials.username,
          "XOBORO_WEBDAV_PASSWORD" to credentials.password,
        )

      WebDavSourceRandomAccess(environment = environment)
        .open(server.baseUrl, "${server.baseUrl}/book.cbz")
        .use { media -> assertEquals(bytes.size.toLong(), media.size) }

      assertEquals(1, server.rangeRequestCount("/dav/book.cbz"))
    }
  }

  @Test
  fun `fails without credentials the server requires`() {
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", ByteArray(64))))
    FakeWebDavServer(tree, credentials = WebDavCredentials("alice", "s3cr3t-password")).use { server ->
      assertFailsWith<WebDavAuthenticationException> {
        WebDavSourceRandomAccess(environment = emptyMap())
          .open(server.baseUrl, "${server.baseUrl}/book.cbz")
      }
    }
  }

  private fun cbz(entries: Map<String, ByteArray>): ByteArray =
    ByteArrayOutputStream()
      .also { buffer ->
        ZipOutputStream(buffer).use { output ->
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
      }.toByteArray()
}
