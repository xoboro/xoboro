package io.xoboro.server.media

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class ArchiveFormatDetectorTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `detects archive formats by signature instead of extension`() {
    val zip = temporaryDirectory.resolve("synthetic.cbr")
    ZipOutputStream(Files.newOutputStream(zip)).use {}
    val rar4 =
      writeSyntheticRar4(
        temporaryDirectory.resolve("synthetic.cbz"),
        mapOf("page.bin" to byteArrayOf(1)),
      )
    val rar5 = temporaryDirectory.resolve("synthetic-rar5.bin")
    Files.write(rar5, byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00))
    val unknown = temporaryDirectory.resolve("unknown.zip")
    Files.writeString(unknown, "not an archive")
    val detector = ArchiveFormatDetector()

    assertEquals(ArchiveFormat.ZIP, detector.detect(zip))
    assertEquals(ArchiveFormat.RAR4, detector.detect(rar4))
    assertEquals(ArchiveFormat.RAR5, detector.detect(rar5))
    assertNull(detector.detect(unknown))
  }
}
