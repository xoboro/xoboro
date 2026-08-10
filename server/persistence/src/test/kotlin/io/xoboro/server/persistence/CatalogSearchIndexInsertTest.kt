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
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A new entity has to reach both search indexes exactly once, and it has to get there without the
 * triggers looking for a row that cannot exist.
 *
 * V35 removed the `DELETE ... WHERE entity_type = ? AND entity_id = ?` that every insert trigger ran
 * first. FTS5 cannot seek on those columns - they are UNINDEXED - so each delete scanned the whole
 * index, which made a first scan quadratic: 3,000 books cost 3,024 ms against 15,000 at 74,438 ms,
 * and 377 ms once the deletes were gone. The deletes were removing nothing, because the primary key
 * they searched for was created a moment earlier.
 *
 * Two things can go wrong with that removal and only one of them is loud. Indexing nothing is caught
 * by any search; indexing an entity *twice* is not, because a search still finds it - the duplicate
 * only shows up as a repeated result much later. So the row count per entity is asserted here rather
 * than the mere presence of a match, and both indexes are checked, since they are two tables with two
 * independent sets of triggers.
 */
class CatalogSearchIndexInsertTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `inserting a series indexes it exactly once in both indexes`() {
    withLibrary("series-insert") { database ->
      database.insertSeries(SERIES_ID, "Alpha series", "alpha")

      assertEquals(1, database.indexRowCount("catalog_search_fts", "SERIES", SERIES_ID.value))
      assertEquals(1, database.indexRowCount("catalog_title_substring", "SERIES", SERIES_ID.value))
      assertTrue(
        database.indexedTitle("catalog_search_fts", "SERIES", SERIES_ID.value).contains("Alpha series"),
        "a series' indexed title carries its name",
      )
      assertTrue(
        database
          .indexedTitle("catalog_title_substring", "SERIES", SERIES_ID.value)
          .contains("Alpha series"),
        "the interior-match index carries it too",
      )
    }
  }

  @Test
  fun `inserting a book indexes it exactly once in both indexes`() {
    withLibrary("book-insert") { database ->
      database.insertSeries(SERIES_ID, "Alpha series", "alpha")
      database.insertBook(BOOK_ID, SERIES_ID, "First book", "alpha/first.cbz")

      assertEquals(1, database.indexRowCount("catalog_search_fts", "BOOK", BOOK_ID.value))
      assertEquals(1, database.indexRowCount("catalog_title_substring", "BOOK", BOOK_ID.value))
      val indexed = database.indexedTitle("catalog_search_fts", "BOOK", BOOK_ID.value)
      assertTrue(indexed.contains("First book"), "a book's indexed title carries its own name")
      assertTrue(
        indexed.contains("Alpha series"),
        "and its series' name, which is what makes an interior series match reach its books",
      )
    }
  }

  /**
   * The failure this guards against is a trigger that only works on an empty index - which is exactly
   * what a scan is not. Indexing the second book while the first is already there is the smallest case
   * that distinguishes "inserts a row" from "inserts a row only when nothing is in the way".
   */
  @Test
  fun `a second book is indexed alongside the first rather than replacing it`() {
    withLibrary("second-book") { database ->
      database.insertSeries(SERIES_ID, "Alpha series", "alpha")
      database.insertBook(BOOK_ID, SERIES_ID, "First book", "alpha/first.cbz")
      database.insertBook(SECOND_BOOK_ID, SERIES_ID, "Second book", "alpha/second.cbz")

      assertEquals(1, database.indexRowCount("catalog_search_fts", "BOOK", BOOK_ID.value))
      assertEquals(1, database.indexRowCount("catalog_search_fts", "BOOK", SECOND_BOOK_ID.value))
      assertEquals(1, database.indexRowCount("catalog_title_substring", "BOOK", BOOK_ID.value))
      assertEquals(
        1,
        database.indexRowCount("catalog_title_substring", "BOOK", SECOND_BOOK_ID.value),
      )
    }
  }

  @Test
  fun `two series in one library are each indexed once`() {
    withLibrary("second-series") { database ->
      database.insertSeries(SERIES_ID, "Alpha series", "alpha")
      database.insertSeries(SECOND_SERIES_ID, "Beta series", "beta")

      assertEquals(1, database.indexRowCount("catalog_search_fts", "SERIES", SERIES_ID.value))
      assertEquals(1, database.indexRowCount("catalog_search_fts", "SERIES", SECOND_SERIES_ID.value))
      assertEquals(1, database.indexRowCount("catalog_title_substring", "SERIES", SERIES_ID.value))
      assertEquals(
        1,
        database.indexRowCount("catalog_title_substring", "SERIES", SECOND_SERIES_ID.value),
      )
    }
  }

  private fun XoboroDatabase.indexRowCount(
    table: String,
    entityType: String,
    entityId: String,
  ): Int =
    dsl
      .fetchOne(
        "SELECT count(*) FROM $table WHERE entity_type = ? AND entity_id = ?",
        entityType,
        entityId,
      )?.get(0, Int::class.java)
      ?: error("counting rows in $table returned nothing")

  private fun XoboroDatabase.indexedTitle(
    table: String,
    entityType: String,
    entityId: String,
  ): String =
    dsl
      .fetchOne(
        "SELECT title FROM $table WHERE entity_type = ? AND entity_id = ?",
        entityType,
        entityId,
      )?.get("title", String::class.java)
      ?: error("$entityType $entityId is not indexed in $table")

  private fun XoboroDatabase.insertSeries(
    seriesId: SeriesId,
    name: String,
    relativePath: String,
  ) {
    JooqSeriesRepository(this).insert(
      Series(
        id = seriesId,
        libraryId = LIBRARY_ID,
        name = name,
        relativePath = relativePath,
        sourceItemId = "file:///synthetic/$relativePath",
        fileModifiedAtMillis = TIMESTAMP,
        bookCount = 1,
        createdAtMillis = TIMESTAMP,
        updatedAtMillis = TIMESTAMP,
      ),
    )
  }

  private fun XoboroDatabase.insertBook(
    bookId: BookId,
    seriesId: SeriesId,
    name: String,
    relativePath: String,
  ) {
    JooqBookRepository(this).insert(
      Book(
        id = bookId,
        libraryId = LIBRARY_ID,
        seriesId = seriesId,
        name = name,
        relativePath = relativePath,
        sourceItemId = "file:///synthetic/$relativePath",
        sourceIdentity = "ino-${bookId.value}",
        mediaKind = MediaKind.COMIC_ARCHIVE,
        fileModifiedAtMillis = TIMESTAMP,
        fileSize = 100L,
        number = 1,
        createdAtMillis = TIMESTAMP,
        updatedAtMillis = TIMESTAMP,
      ),
    )
  }

  private fun withLibrary(
    databaseName: String,
    block: (XoboroDatabase) -> Unit,
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
      block(database)
    }
  }

  private companion object {
    private val LIBRARY_ID = LibraryId("library-1")
    private val SERIES_ID = SeriesId("series-1")
    private val SECOND_SERIES_ID = SeriesId("series-2")
    private val BOOK_ID = BookId("book-1")
    private val SECOND_BOOK_ID = BookId("book-2")
    private const val TIMESTAMP = 1_700_000_000_000L
  }
}
