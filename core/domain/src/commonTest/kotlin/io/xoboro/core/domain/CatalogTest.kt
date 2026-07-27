package io.xoboro.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogTest {
  @Test
  fun `catalog identities and defaults are portable`() {
    val series = seriesFixture()
    val book = bookFixture()

    assertEquals(0, series.bookCount)
    assertEquals("", book.fileHash)
    assertEquals(MediaKind.COMIC_ARCHIVE, book.mediaKind)
  }

  @Test
  fun `catalog invariants reject invalid persisted state`() {
    assertFailsWith<IllegalArgumentException> {
      SeriesId("")
    }
    assertFailsWith<IllegalArgumentException> {
      seriesFixture(bookCount = -1)
    }
    assertFailsWith<IllegalArgumentException> {
      bookFixture(fileSize = -1)
    }
    assertFailsWith<IllegalArgumentException> {
      bookFixture(createdAtMillis = 2, updatedAtMillis = 1)
    }
  }

  private fun seriesFixture(
    bookCount: Int = 0,
  ): Series =
    Series(
      id = SeriesId("series-1"),
      libraryId = LibraryId("library-1"),
      name = "Synthetic series",
      relativePath = "series",
      sourceItemId = "file:///synthetic/series",
      fileModifiedAtMillis = 1,
      bookCount = bookCount,
      createdAtMillis = 1,
    )

  private fun bookFixture(
    fileSize: Long = 1,
    createdAtMillis: Long = 1,
    updatedAtMillis: Long = createdAtMillis,
  ): Book =
    Book(
      id = BookId("book-1"),
      libraryId = LibraryId("library-1"),
      seriesId = SeriesId("series-1"),
      name = "Synthetic book",
      relativePath = "series/book.cbz",
      sourceItemId = "file:///synthetic/series/book.cbz",
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1,
      fileSize = fileSize,
      createdAtMillis = createdAtMillis,
      updatedAtMillis = updatedAtMillis,
    )
}
