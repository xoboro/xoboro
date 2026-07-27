package io.xoboro.server.persistence

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.io.TempDir

class JooqCatalogRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `round trips complete series and book scan state`() {
    withCatalog("round-trip") { seriesRepository, bookRepository, _ ->
      val series = seriesFixture()
      val book = bookFixture()

      seriesRepository.insert(series)
      bookRepository.insert(book)

      assertEquals(series, seriesRepository.findByIdOrNull(series.id))
      assertEquals(
        series,
        seriesRepository.findByLibraryIdAndRelativePath(series.libraryId, series.relativePath),
      )
      assertEquals(book, bookRepository.findByIdOrNull(book.id))
      assertEquals(
        book,
        bookRepository.findByLibraryIdAndRelativePath(book.libraryId, book.relativePath),
      )
      assertEquals(listOf(book), bookRepository.findAllBySeriesId(series.id))
    }
  }

  @Test
  fun `batch inserts and deterministically orders catalog records`() {
    withCatalog("batch") { seriesRepository, bookRepository, _ ->
      val alpha = seriesFixture(id = "series-a", name = "Alpha", relativePath = "a")
      val zulu = seriesFixture(id = "series-z", name = "Zulu", relativePath = "z")
      seriesRepository.insertAll(listOf(zulu, alpha))
      val first = bookFixture(id = "book-1", seriesId = alpha.id, relativePath = "a/1.cbz", number = 1)
      val second =
        bookFixture(
          id = "book-2",
          seriesId = alpha.id,
          relativePath = "a/2.cbz",
          number = 2,
          sourceItemId = "file:///synthetic/a/2.cbz",
        )
      bookRepository.insertAll(listOf(second, first))

      assertEquals(listOf(alpha, zulu), seriesRepository.findAllByLibraryId(LIBRARY_ID))
      assertEquals(listOf(first, second), bookRepository.findAllBySeriesId(alpha.id))
      assertEquals(2L, seriesRepository.count())
      assertEquals(2L, bookRepository.count())
    }
  }

  @Test
  fun `updates every mutable scan field`() {
    withCatalog("update") { seriesRepository, bookRepository, _ ->
      val series = seriesFixture()
      val book = bookFixture()
      seriesRepository.insert(series)
      bookRepository.insert(book)
      val updatedSeries =
        series.copy(
          name = "Updated series",
          relativePath = "moved",
          sourceItemId = "file:///synthetic/moved",
          fileModifiedAtMillis = 1_700_000_010_000L,
          bookCount = 2,
          deletedAtMillis = 1_700_000_009_000L,
          oneshot = true,
          updatedAtMillis = 1_700_000_010_000L,
        )
      val updatedBook =
        book.copy(
          seriesId = updatedSeries.id,
          name = "Updated book",
          relativePath = "moved/book.pdf",
          sourceItemId = "file:///synthetic/moved/book.pdf",
          sourceIdentity = "identity-2",
          mediaKind = MediaKind.PDF,
          fileModifiedAtMillis = 1_700_000_010_000L,
          fileSize = 6_000_000_000L,
          fileHash = "file-hash-2",
          fileHashKoreader = "koreader-hash-2",
          number = 2,
          deletedAtMillis = 1_700_000_009_000L,
          oneshot = true,
          updatedAtMillis = 1_700_000_010_000L,
        )

      seriesRepository.update(updatedSeries)
      bookRepository.update(updatedBook)

      assertEquals(updatedSeries, seriesRepository.findByIdOrNull(series.id))
      assertEquals(updatedBook, bookRepository.findByIdOrNull(book.id))
    }
  }

  @Test
  fun `batch update rolls back when any catalog entity is missing`() {
    withCatalog("rollback") { seriesRepository, bookRepository, _ ->
      val series = seriesFixture()
      val book = bookFixture()
      seriesRepository.insert(series)
      bookRepository.insert(book)

      assertFailsWith<NoSuchElementException> {
        seriesRepository.updateAll(
          listOf(
            series.copy(name = "Must roll back", updatedAtMillis = 1_700_000_002_000L),
            seriesFixture(id = "missing-series"),
          ),
        )
      }
      assertFailsWith<NoSuchElementException> {
        bookRepository.updateAll(
          listOf(
            book.copy(name = "Must roll back", updatedAtMillis = 1_700_000_002_000L),
            bookFixture(id = "missing-book"),
          ),
        )
      }

      assertEquals(series, seriesRepository.findByIdOrNull(series.id))
      assertEquals(book, bookRepository.findByIdOrNull(book.id))
    }
  }

  @Test
  fun `enforces one catalog entry per library relative path`() {
    withCatalog("unique-path") { seriesRepository, bookRepository, _ ->
      val series = seriesFixture()
      seriesRepository.insert(series)
      assertFailsWith<DataAccessException> {
        seriesRepository.insert(series.copy(id = SeriesId("series-2")))
      }
      val book = bookFixture()
      bookRepository.insert(book)
      assertFailsWith<DataAccessException> {
        bookRepository.insert(book.copy(id = BookId("book-2")))
      }
    }
  }

  @Test
  fun `deleting a series cascades its books`() {
    withCatalog("cascade") { seriesRepository, bookRepository, _ ->
      val series = seriesFixture()
      seriesRepository.insert(series)
      bookRepository.insert(bookFixture())

      seriesRepository.delete(series.id)

      assertNull(seriesRepository.findByIdOrNull(series.id))
      assertNull(bookRepository.findByIdOrNull(BookId("book-1")))
    }
  }

  private fun withCatalog(
    databaseName: String,
    block: (JooqSeriesRepository, JooqBookRepository, XoboroDatabase) -> Unit,
  ) {
    XoboroDatabase.open(
      DatabaseConfig(tempDirectory.resolve("$databaseName.sqlite")),
    ).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1L,
        ),
      )
      block(JooqSeriesRepository(database), JooqBookRepository(database), database)
    }
  }

  private fun seriesFixture(
    id: String = "series-1",
    name: String = "Synthetic series",
    relativePath: String = "series",
  ): Series =
    Series(
      id = SeriesId(id),
      libraryId = LIBRARY_ID,
      name = name,
      relativePath = relativePath,
      sourceItemId = "file:///synthetic/$relativePath",
      fileModifiedAtMillis = 1_700_000_000_000L,
      bookCount = 1,
      deletedAtMillis = 1_700_000_001_000L,
      oneshot = true,
      createdAtMillis = 1_700_000_000_000L,
      updatedAtMillis = 1_700_000_001_000L,
    )

  private fun bookFixture(
    id: String = "book-1",
    seriesId: SeriesId = SeriesId("series-1"),
    relativePath: String = "series/book.cbz",
    sourceItemId: String = "file:///synthetic/series/book.cbz",
    number: Int = 1,
  ): Book =
    Book(
      id = BookId(id),
      libraryId = LIBRARY_ID,
      seriesId = seriesId,
      name = "Synthetic book",
      relativePath = relativePath,
      sourceItemId = sourceItemId,
      sourceIdentity = "identity-$id",
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1_700_000_000_000L,
      fileSize = 5_000_000_000L,
      fileHash = "file-hash",
      fileHashKoreader = "koreader-hash",
      number = number,
      deletedAtMillis = 1_700_000_001_000L,
      oneshot = true,
      createdAtMillis = 1_700_000_000_000L,
      updatedAtMillis = 1_700_000_001_000L,
    )

  companion object {
    private val LIBRARY_ID = LibraryId("library-1")
  }
}
