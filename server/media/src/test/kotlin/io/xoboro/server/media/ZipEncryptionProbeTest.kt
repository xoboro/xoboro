package io.xoboro.server.media

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ZipEncryptionProbeTest {
  @TempDir
  lateinit var tempDirectory: Path

  /**
   * Anchors the fixture writer itself. Without this the encrypted assertions below could pass for
   * the wrong reason: a malformed container would make `ZipFile` throw and the probe report
   * encryption, and the pair would look like a working diagnosis.
   */
  @Test
  fun `writes containers the JDK accepts when no entry is marked encrypted`() {
    val archive =
      writeSyntheticZip(
        tempDirectory.resolve("plain.cbz"),
        mapOf("001.txt" to "first".encodeToByteArray(), "002.txt" to "second".encodeToByteArray()),
      )

    ZipFile(archive.toFile()).use { opened ->
      assertEquals(
        listOf("001.txt", "002.txt"),
        opened.entries().asSequence().map { it.name }.toList(),
      )
      assertEquals(
        "first",
        opened.getInputStream(opened.getEntry("001.txt")).use { it.readBytes().decodeToString() },
      )
    }
    assertFalse(ZipEncryptionProbe().declaresEncryptedEntries(archive))
  }

  @Test
  fun `reports encryption declared in the central directory`() {
    val archive =
      writeSyntheticZip(
        tempDirectory.resolve("encrypted.cbz"),
        mapOf("001.jpg" to "ciphertext".encodeToByteArray()),
        encrypted = true,
      )

    // The reason the probe exists: the JDK will not open the archive, so the flag has to be read
    // from the file rather than from an opened archive.
    assertFailsWithZipException { ZipFile(archive.toFile()).close() }
    assertTrue(ZipEncryptionProbe().declaresEncryptedEntries(archive))
  }

  @Test
  fun `reports no encryption for containers it cannot walk`() {
    val truncated = tempDirectory.resolve("truncated.cbz")
    val complete =
      writeSyntheticZip(
        tempDirectory.resolve("complete.cbz"),
        mapOf("001.jpg" to "ciphertext".encodeToByteArray()),
        encrypted = true,
      )
    Files.write(truncated, Files.readAllBytes(complete).copyOfRange(0, 24))
    val garbage = tempDirectory.resolve("garbage.cbz")
    Files.writeString(garbage, "not a zip at all")
    val empty = tempDirectory.resolve("empty.cbz")
    Files.write(empty, ByteArray(0))

    // An unreadable container is unreadable; claiming encryption would misdiagnose a damaged file.
    assertFalse(ZipEncryptionProbe().declaresEncryptedEntries(truncated))
    assertFalse(ZipEncryptionProbe().declaresEncryptedEntries(garbage))
    assertFalse(ZipEncryptionProbe().declaresEncryptedEntries(empty))
    assertFalse(ZipEncryptionProbe().declaresEncryptedEntries(tempDirectory.resolve("absent.cbz")))
  }

  @Test
  fun `refuses to read a central directory beyond its limit`() {
    val archive =
      writeSyntheticZip(
        tempDirectory.resolve("limited.cbz"),
        mapOf("001.jpg" to "ciphertext".encodeToByteArray()),
        encrypted = true,
      )

    assertFalse(ZipEncryptionProbe(maximumDirectorySize = 1).declaresEncryptedEntries(archive))
  }

  private fun assertFailsWithZipException(block: () -> Unit) {
    val failure = runCatching(block).exceptionOrNull()
    assertTrue(
      failure is ZipException,
      "expected the JDK to reject the encrypted archive, got $failure",
    )
  }
}
