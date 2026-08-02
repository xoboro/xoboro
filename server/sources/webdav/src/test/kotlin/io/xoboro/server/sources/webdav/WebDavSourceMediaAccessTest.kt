package io.xoboro.server.sources.webdav

import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavSourceMediaAccessTest {
  @Test
  fun `materializes a book to a real local file with matching bytes`() {
    val bytes = "fake-cbz-content".toByteArray()
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", bytes, etag = "v1")))
    FakeWebDavServer(tree).use { server ->
      val cacheDirectory = Files.createTempDirectory("webdav-media-cache")
      val access = WebDavSourceMediaAccess(cacheDirectory)
      access.materialize(server.baseUrl, "${server.baseUrl}/book.cbz").use { media ->
        assertTrue(Files.isRegularFile(media.path))
        assertContentEquals(bytes, media.path.readBytes())
      }
    }
  }

  @Test
  fun `rejects an item URL outside the library root`() {
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", ByteArray(4))))
    FakeWebDavServer(tree).use { server ->
      val access = WebDavSourceMediaAccess(Files.createTempDirectory("webdav-media-cache"))
      val outsideUrl = server.baseUrl.substringBeforeLast("/dav") + "/other/book.cbz"
      assertFailsWith<IllegalArgumentException> {
        access.materialize(server.baseUrl, outsideUrl)
      }
    }
  }

  @Test
  fun `refuses an item on a different host even when its path looks like the root`() {
    // The path check alone lets this through: `https://elsewhere/dav/book.cbz` is under `/dav`
    // just as the real root is. What stops it is the origin comparison, and disabling that
    // comparison left every other test in this file passing - so it was possible to hand the
    // adapter another host's URL and have it send the library's credentials there.
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", ByteArray(4))))
    FakeWebDavServer(tree).use { server ->
      val access =
        WebDavSourceMediaAccess(
          Files.createTempDirectory("webdav-media-cache"),
          environment =
            mapOf(
              "XOBORO_WEBDAV_USERNAME" to "alice",
              "XOBORO_WEBDAV_PASSWORD" to "s3cr3t-password",
            ),
        )
      val elsewhere = server.baseUrl.replace("127.0.0.1", "localhost") + "/book.cbz"

      // Same scheme, same path prefix, different host.
      assertFailsWith<IllegalArgumentException> { access.materialize(server.baseUrl, elsewhere) }

      // And a different port on the same host is just as much a different server.
      val otherPort = server.baseUrl.replace(Regex(":(\\d+)")) { ":" + (it.groupValues[1].toInt() + 1) }
      assertFailsWith<IllegalArgumentException> {
        access.materialize(server.baseUrl, "$otherPort/book.cbz")
      }
    }
  }

  @Test
  fun `a 401 surfaces as a clear failure that never mentions the password`() {
    // The client must be holding the secret while it fails, or the assertion below cannot
    // fail either: with no credentials configured there is nothing to leak, and the test
    // passes against an implementation that prints every header it sent. So the server wants
    // one password and the client is given a different, known one.
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", ByteArray(4))))
    FakeWebDavServer(tree, WebDavCredentials("alice", "server-side-password")).use { server ->
      val access =
        WebDavSourceMediaAccess(
          Files.createTempDirectory("webdav-media-cache"),
          environment =
            mapOf(
              "XOBORO_WEBDAV_USERNAME" to "alice",
              "XOBORO_WEBDAV_PASSWORD" to "s3cr3t-password",
            ),
        )
      val failure =
        assertFailsWith<WebDavAuthenticationException> {
          access.materialize(server.baseUrl, "${server.baseUrl}/book.cbz")
        }
      assertEquals(401, failure.statusCode)
      assertTrue("s3cr3t-password" !in failure.message.orEmpty())
      assertTrue("s3cr3t-password" !in failure.toString())
    }
  }

  @Test
  fun `a cache hit for an unchanged item avoids a second GET`() {
    val tree = FixtureDirectory("dav", mutableListOf(FixtureFile("book.cbz", ByteArray(8), etag = "same-etag")))
    FakeWebDavServer(tree).use { server ->
      val access = WebDavSourceMediaAccess(Files.createTempDirectory("webdav-media-cache"))
      access.materialize(server.baseUrl, "${server.baseUrl}/book.cbz").close()
      access.materialize(server.baseUrl, "${server.baseUrl}/book.cbz").close()

      assertEquals(1, server.getRequestCount("/dav/book.cbz"))
    }
  }

  @Test
  fun `evicts the least recently used entry once the cache exceeds its byte bound`() {
    val tree =
      FixtureDirectory(
        "dav",
        mutableListOf(
          FixtureFile("x.cbz", ByteArray(10), etag = "x1"),
          FixtureFile("y.cbz", ByteArray(10), etag = "y1"),
          FixtureFile("z.cbz", ByteArray(10), etag = "z1"),
        ),
      )
    FakeWebDavServer(tree).use { server ->
      // Room for two of the three 10-byte files at once.
      val access = WebDavSourceMediaAccess(Files.createTempDirectory("webdav-media-cache"), maxCacheBytes = 25)
      fun materialize(name: String) = access.materialize(server.baseUrl, "${server.baseUrl}/$name")

      materialize("x.cbz").close()
      materialize("y.cbz").close()
      // Touching "x" again makes "y" the least recently used entry once "z" needs room.
      materialize("x.cbz").close()
      materialize("z.cbz").close()

      // "x" and "z" both survived: revalidating either is a cache hit (a 304, not a fresh GET).
      // Checked before re-touching "y" below, since a hit never triggers eviction but storing a
      // third distinct file would.
      materialize("x.cbz").close()
      assertEquals(1, server.getRequestCount("/dav/x.cbz"))
      materialize("z.cbz").close()
      assertEquals(1, server.getRequestCount("/dav/z.cbz"))

      // "y" was pushed out by "z"; re-fetching it is a real download.
      materialize("y.cbz").close()
      assertEquals(2, server.getRequestCount("/dav/y.cbz"))
    }
  }
}
