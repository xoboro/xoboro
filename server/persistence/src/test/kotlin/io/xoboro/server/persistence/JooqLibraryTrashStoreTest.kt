package io.xoboro.server.persistence

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqLibraryTrashStoreTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `atomically deletes only trashed catalog rows and dependent media`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("trash.sqlite"))).use { database ->
      val libraries = JooqLibraryRepository(database)
      val series = JooqSeriesRepository(database)
      val books = JooqBookRepository(database)
      val media = JooqBookMediaRepository(database)
      libraries.insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      val activeSeries = seriesFixture("series-active", deletedAtMillis = null)
      val deletedSeries = seriesFixture("series-deleted", deletedAtMillis = 20)
      series.insertAll(listOf(activeSeries, deletedSeries))
      val activeBook = bookFixture("book-active", activeSeries.id, deletedAtMillis = null)
      val deletedBook = bookFixture("book-deleted", deletedSeries.id, deletedAtMillis = 20)
      books.insertAll(listOf(activeBook, deletedBook))
      media.upsert(BookMedia(bookId = deletedBook.id, createdAtMillis = 1))

      val result = JooqLibraryTrashStore(database).emptyTrash(LIBRARY_ID)

      assertEquals(1, result.deletedBooks)
      assertEquals(1, result.deletedSeries)
      assertNotNull(books.findByIdOrNull(activeBook.id))
      assertNotNull(series.findByIdOrNull(activeSeries.id))
      assertNull(books.findByIdOrNull(deletedBook.id))
      assertNull(series.findByIdOrNull(deletedSeries.id))
      assertNull(media.findByBookIdOrNull(deletedBook.id))
    }
  }

  private fun seriesFixture(
    id: String,
    deletedAtMillis: Long?,
  ): Series =
    Series(
      id = SeriesId(id),
      libraryId = LIBRARY_ID,
      name = "Synthetic $id",
      relativePath = id,
      sourceItemId = "file:///synthetic/$id",
      fileModifiedAtMillis = 1,
      bookCount = 1,
      deletedAtMillis = deletedAtMillis,
      createdAtMillis = 1,
      updatedAtMillis = deletedAtMillis ?: 1,
    )

  private fun bookFixture(
    id: String,
    seriesId: SeriesId,
    deletedAtMillis: Long?,
  ): Book =
    Book(
      id = BookId(id),
      libraryId = LIBRARY_ID,
      seriesId = seriesId,
      name = "Synthetic $id",
      relativePath = "${seriesId.value}/$id.cbz",
      sourceItemId = "file:///synthetic/${seriesId.value}/$id.cbz",
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1,
      deletedAtMillis = deletedAtMillis,
      createdAtMillis = 1,
      updatedAtMillis = deletedAtMillis ?: 1,
    )

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
  }
}
