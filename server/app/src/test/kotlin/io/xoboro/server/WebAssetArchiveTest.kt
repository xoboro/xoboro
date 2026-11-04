package io.xoboro.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class WebAssetArchiveTest {
  @TempDir
  lateinit var temporary: Path

  @Test
  fun `keeps older hashed chunks across upgrades without retaining a stale shell`() {
    val web = Files.createDirectories(temporary.resolve("web"))
    val assets = Files.createDirectories(web.resolve("assets"))
    Files.writeString(web.resolve("index.html"), "old-shell")
    val old = Files.writeString(assets.resolve("Reader-previous1.js"), "old-reader")
    Files.writeString(assets.resolve("private.txt"), "not-a-bundle")
    val directory = temporary.resolve("archive")
    val archive = WebAssetArchive(directory)
    archive.capture(web)
    Files.delete(old)
    Files.writeString(web.resolve("index.html"), "new-shell")
    Files.writeString(assets.resolve("Reader-current12.js"), "new-reader")
    archive.capture(web)

    assertEquals("old-reader", Files.readString(assertNotNull(archive.find(listOf("Reader-previous1.js")))))
    assertEquals("new-reader", Files.readString(assertNotNull(archive.find(listOf("Reader-current12.js")))))
    assertFalse(Files.exists(directory.resolve("index.html")))
    assertFalse(Files.exists(directory.resolve("private.txt")))
    assertNull(archive.find(listOf("..", "private.txt")))
  }
}
