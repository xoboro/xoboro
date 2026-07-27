package io.xoboro.server.sources.local

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class LocalSourceArtworkAccessTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `finds Komga compatible book and series artwork deterministically`() {
    val root = Files.createDirectories(temporaryDirectory.resolve("library"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val book = Files.write(series.resolve("Volume(1).cbz"), byteArrayOf(0))
    val expectedBook =
      linkedMapOf(
        "volume(1)-2.PNG" to byteArrayOf(2),
        "Volume(1).jpg" to byteArrayOf(1),
      )
    val expectedSeries =
      linkedMapOf(
        "CoVeR.jpeg" to byteArrayOf(3),
        "folder.webp" to byteArrayOf(4),
      )
    (expectedBook + expectedSeries + mapOf("other.jpg" to byteArrayOf(5))).forEach { (name, bytes) ->
      Files.write(series.resolve(name), bytes)
    }
    val access = LocalSourceArtworkAccess()

    val bookArtwork =
      access.findBookArtwork(root.toUri().toString(), book.toUri().toString(), 100)
    val seriesArtwork =
      access.findSeriesArtwork(root.toUri().toString(), series.toUri().toString(), 100)

    assertEquals(listOf("volume(1)-2.PNG", "Volume(1).jpg"), bookArtwork.map { it.name })
    assertContentEquals(byteArrayOf(2), bookArtwork.first().bytes)
    assertEquals(listOf("CoVeR.jpeg", "folder.webp"), seriesArtwork.map { it.name })
  }

  @Test
  fun `rejects artwork outside its root and over the byte limit`() {
    val root = Files.createDirectories(temporaryDirectory.resolve("safe-library"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val book = Files.write(series.resolve("volume.cbz"), byteArrayOf(0))
    Files.write(series.resolve("volume.jpg"), byteArrayOf(1, 2))
    val outside = Files.write(temporaryDirectory.resolve("outside.cbz"), byteArrayOf(0))
    val access = LocalSourceArtworkAccess()

    assertFailsWith<IllegalArgumentException> {
      access.findBookArtwork(root.toUri().toString(), outside.toUri().toString(), 10)
    }
    assertFailsWith<IllegalArgumentException> {
      access.findBookArtwork(root.toUri().toString(), book.toUri().toString(), 1)
    }
  }
}
