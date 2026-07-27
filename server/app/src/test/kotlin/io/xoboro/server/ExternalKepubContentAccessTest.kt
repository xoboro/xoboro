package io.xoboro.server

import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookPage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class ExternalKepubContentAccessTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `converts caches and invalidates KEPUB content by revision`() {
    val executable = temporaryDirectory.resolve("synthetic-kepubify")
    val invocationLog = temporaryDirectory.resolve("invocations.txt")
    Files.writeString(
      executable,
      """
      #!/bin/sh
      test "${'$'}1" = "--output" || exit 2
      cp "${'$'}3" "${'$'}2"
      printf '%s' '-kepub' >> "${'$'}2"
      printf '%s\n' invoked >> "$invocationLog"
      """.trimIndent(),
    )
    executable.toFile().setExecutable(true)
    val source = MutableBookContent("first".encodeToByteArray())
    val access =
      ExternalKepubContentAccess(
        books = source,
        executablePath = { executable.toString() },
        cacheDirectory = temporaryDirectory.resolve("cache"),
        timeoutMillis = 5_000,
      )

    assertContentEquals("first-kepub".encodeToByteArray(), access.openKepub(BOOK_ID, "one")!!.readAll())
    source.bytes = "second".encodeToByteArray()
    assertContentEquals("first-kepub".encodeToByteArray(), access.openKepub(BOOK_ID, "one")!!.readAll())
    assertContentEquals("second-kepub".encodeToByteArray(), access.openKepub(BOOK_ID, "two")!!.readAll())
    assertEquals(2, Files.readAllLines(invocationLog).size)
    assertEquals(1, Files.list(temporaryDirectory.resolve("cache")).use { it.count() })
  }

  private class MutableBookContent(
    var bytes: ByteArray,
  ) : BookContentAccess {
    override fun pages(bookId: BookId): List<BookPage> = emptyList()

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream? = null

    override fun openBook(bookId: BookId): MediaContentStream =
      ByteStream(bytes.copyOf())
  }

  private class ByteStream(
    private val bytes: ByteArray,
  ) : MediaContentStream {
    private var cursor = 0
    override val mediaType: String = "application/epub+zip"
    override val contentLength: Long = bytes.size.toLong()

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (cursor >= bytes.size) return -1
      val count = minOf(length, bytes.size - cursor)
      bytes.copyInto(buffer, offset, cursor, cursor + count)
      cursor += count
      return count
    }

    override fun close() = Unit
  }

  private fun MediaContentStream.readAll(): ByteArray =
    useContent {
      buildList {
        val buffer = ByteArray(32)
        while (true) {
          val count = read(buffer)
          if (count < 0) break
          repeat(count) { add(buffer[it]) }
        }
      }.toByteArray()
    }

  private inline fun <T> MediaContentStream.useContent(block: MediaContentStream.() -> T): T =
    try {
      block()
    } finally {
      close()
    }

  private companion object {
    val BOOK_ID = BookId("book-1")
  }
}
