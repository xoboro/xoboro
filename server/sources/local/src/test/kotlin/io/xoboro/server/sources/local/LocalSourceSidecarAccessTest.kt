package io.xoboro.server.sources.local

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class LocalSourceSidecarAccessTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `reads a bounded sidecar inside the library root`() {
    val root = Files.createDirectory(tempDirectory.resolve("library"))
    val series = Files.createDirectory(root.resolve("Synthetic series"))
    val content = """{"metadata":{"name":"Synthetic series"}}""".encodeToByteArray()
    Files.write(series.resolve("series.json"), content)

    val result =
      LocalSourceSidecarAccess()
        .readSeriesSidecar(
          rootItemId = root.toUri().toString(),
          seriesItemId = series.toUri().toString(),
          fileName = "series.json",
          maximumBytes = 1_024,
        )

    assertContentEquals(content, result)
    assertNull(
      LocalSourceSidecarAccess()
        .readSeriesSidecar(
          rootItemId = root.toUri().toString(),
          seriesItemId = series.toUri().toString(),
          fileName = "missing.json",
          maximumBytes = 1_024,
        ),
    )
  }

  @Test
  fun `rejects traversal roots and oversized sidecars`() {
    val root = Files.createDirectory(tempDirectory.resolve("root"))
    val inside = Files.createDirectory(root.resolve("inside"))
    val outside = Files.createDirectory(tempDirectory.resolve("outside"))
    Files.write(inside.resolve("series.json"), byteArrayOf(1, 2))

    val access = LocalSourceSidecarAccess()
    assertFailsWith<IllegalArgumentException> {
      access.readSeriesSidecar(
        rootItemId = root.toUri().toString(),
        seriesItemId = outside.toUri().toString(),
        fileName = "series.json",
        maximumBytes = 1_024,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      access.readSeriesSidecar(
        rootItemId = root.toUri().toString(),
        seriesItemId = inside.toUri().toString(),
        fileName = "../series.json",
        maximumBytes = 1_024,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      access.readSeriesSidecar(
        rootItemId = root.toUri().toString(),
        seriesItemId = inside.toUri().toString(),
        fileName = "series.json",
        maximumBytes = 1,
      )
    }
  }
}
