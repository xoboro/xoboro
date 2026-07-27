package io.xoboro.server.sources.local

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class LocalSourceMediaAccessTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `materializes a local file without copying it`() {
    val file = tempDirectory.resolve("book.cbz")
    Files.write(file, byteArrayOf(1, 2, 3))

    LocalSourceMediaAccess()
      .materialize(tempDirectory.toUri().toString(), file.toUri().toString())
      .use { materialized ->
      assertEquals(file.toRealPath(), materialized.path)
    }
  }

  @Test
  fun `rejects non-file and directory items`() {
    val access = LocalSourceMediaAccess()

    assertFailsWith<IllegalArgumentException> {
      access.materialize(
        tempDirectory.toUri().toString(),
        "https://example.invalid/book.cbz",
      )
    }
    assertFailsWith<IllegalArgumentException> {
      access.materialize(
        tempDirectory.toUri().toString(),
        tempDirectory.toUri().toString(),
      )
    }
  }

  @Test
  fun `rejects files outside the library root including symlink escapes`() {
    val root = Files.createDirectory(tempDirectory.resolve("root"))
    val outside = Files.write(tempDirectory.resolve("outside.cbz"), byteArrayOf(1))
    val access = LocalSourceMediaAccess()

    assertFailsWith<IllegalArgumentException> {
      access.materialize(root.toUri().toString(), outside.toUri().toString())
    }

    val symlink = root.resolve("escape.cbz")
    runCatching { Files.createSymbolicLink(symlink, outside) }
      .onSuccess {
        assertFailsWith<IllegalArgumentException> {
          access.materialize(root.toUri().toString(), symlink.toUri().toString())
        }
      }
  }
}
