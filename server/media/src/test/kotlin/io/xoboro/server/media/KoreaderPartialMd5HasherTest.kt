package io.xoboro.server.media

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class KoreaderPartialMd5HasherTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `matches the KOReader sparse hash vector`() {
    val path = tempDirectory.resolve("synthetic.bin")
    val bytes = ByteArray(1_100_123) { index -> ((index * 31 + 17) and 0xff).toByte() }
    Files.write(path, bytes)

    assertEquals(
      "08c145bd0b4198ac97aeed577f1c9ed4",
      KoreaderPartialMd5Hasher().hash(path),
    )
  }

  @Test
  fun `retains compatibility for files shorter than one block`() {
    val path = tempDirectory.resolve("short.bin")
    Files.write(path, byteArrayOf(1, 2, 3, 4, 5))

    assertEquals(
      "9cb5c28d7e0c995c2cef586582d6b248",
      KoreaderPartialMd5Hasher().hash(path),
    )
  }
}
