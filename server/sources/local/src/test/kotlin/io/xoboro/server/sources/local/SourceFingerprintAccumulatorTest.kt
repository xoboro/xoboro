package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SourceFingerprintAccumulatorTest {
  @Test
  fun `fingerprint is independent of filesystem iteration order`() {
    val first = sourceFile("First/001.cbz", identity = "inode-1", size = 100, modified = 1_000)
    val second = sourceFile("Second/002.cbz", identity = "inode-2", size = 200, modified = 2_000)

    assertEquals(fingerprint(first, second), fingerprint(second, first))
  }

  @Test
  fun `every reconciliation metadata field changes the fingerprint`() {
    val original = sourceFile("Series/001.cbz", identity = "inode-1", size = 100, modified = 1_000)
    val baseline = fingerprint(original)

    assertNotEquals(baseline, fingerprint(original.copy(relativePath = "Series/renamed.cbz")))
    assertNotEquals(baseline, fingerprint(original.copy(identity = "inode-2")))
    assertNotEquals(baseline, fingerprint(original.copy(size = 101)))
    assertNotEquals(baseline, fingerprint(original.copy(modifiedAtMillis = 1_001)))
  }

  @Test
  fun `file count participates even when no files are emitted`() {
    assertEquals(64, fingerprint().length)
    assertNotEquals(fingerprint(), fingerprint(sourceFile("Series/001.cbz")))
  }

  private fun fingerprint(vararg files: SourceFile): String {
    val accumulator = SourceFingerprintAccumulator()
    files.forEach(accumulator::add)
    return accumulator.finish()
  }

  private fun sourceFile(
    relativePath: String,
    identity: String? = "inode-1",
    size: Long = 100,
    modified: Long = 1_000,
  ): SourceFile =
    SourceFile(
      itemId = "file:///synthetic/$relativePath",
      parentItemId = "file:///synthetic/${relativePath.substringBeforeLast('/')}",
      identity = identity,
      relativePath = relativePath,
      name = relativePath.substringAfterLast('/'),
      extension = relativePath.substringAfterLast('.').lowercase(),
      size = size,
      modifiedAtMillis = modified,
    )
}
