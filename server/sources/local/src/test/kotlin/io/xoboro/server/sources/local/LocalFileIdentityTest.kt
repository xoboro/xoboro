package io.xoboro.server.sources.local

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalFileIdentityTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `keeps the inode and drops the device the mount owns`() {
    // The rendering a Unix JDK produces. Its device number belongs to the mount rather than to the
    // file, so remounting moves it and every file in a library looks new.
    assertEquals("1234567890123456789", identityOf("(dev=1000010,ino=1234567890123456789)"))
  }

  @Test
  fun `keeps a key it cannot parse rather than losing the identity`() {
    assertEquals("some-other-jdk-rendering", identityOf("some-other-jdk-rendering"))
  }

  @Test
  fun `has no identity when the filesystem reports no key`() {
    assertEquals(null, identityOf(null))
  }

  @Test
  fun `reads the same identity twice for one real file`() {
    val file = Files.createFile(tempDirectory.resolve("book.cbz"))

    val first = Files.readAttributes(file, BasicFileAttributes::class.java).durableIdentity()
    val second = Files.readAttributes(file, BasicFileAttributes::class.java).durableIdentity()

    assertNotNull(first, "a temp file on a real filesystem is expected to have a file key")
    assertEquals(first, second)
    assertTrue(first.none { it == '=' }, "a parsed identity should not still carry dev= or ino=")
  }

  private fun identityOf(renderedKey: String?): String? =
    object : BasicFileAttributes by Files.readAttributes(
      Files.createFile(tempDirectory.resolve("probe-${renderedKey.hashCode()}")),
      BasicFileAttributes::class.java,
    ) {
      override fun fileKey(): Any? = renderedKey
    }.durableIdentity()
}
