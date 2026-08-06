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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Rebuilding a full-text row is the most expensive thing a write to `book` or `series` can trigger:
 * the source view carries three joins and five correlated `group_concat` subqueries before FTS5
 * re-tokenises the result, and renaming one series rebuilds a row for every book under it. SQLite
 * fires `AFTER UPDATE OF <column>` on the SET clause rather than on a value actually changing, so a
 * bulk update that rewrites columns to the values they already hold used to pay all of it. Against a
 * library of 120,723 books that came to 43 minutes of CPU inside one write transaction, which is long
 * enough to starve every other writer in the process out of SQLite's single write lock.
 *
 * Whether a rebuild ran is read off the indexed text itself. A book's indexed title includes its
 * series' metadata title, and no trigger reindexes on a metadata write, so writing a marker there
 * leaves the index stale on purpose: the marker can only appear if something rebuilt the row
 * afterwards. That is a fact about the index's content rather than about SQLite's rowid allocation,
 * which reuses the number it just freed and so cannot tell a rebuild from a no-op here.
 */
class CatalogSearchIndexTriggerTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `rewriting a book's columns to the values they already hold does not rebuild its index row`() {
    withCatalog("book-noop") { database ->
      database.staleTheIndex()

      // Exactly the shape a library re-scan writes: every matched book, every column, same values.
      database.dsl.execute(
        "UPDATE book SET name = name, series_id = series_id WHERE id = ?",
        BOOK_ID.value,
      )

      assertFalse(database.indexedTitleOf("BOOK", BOOK_ID.value).contains(MARKER))
    }
  }

  @Test
  fun `renaming a book still rebuilds its index row`() {
    withCatalog("book-renamed") { database ->
      database.staleTheIndex()

      database.dsl.execute("UPDATE book SET name = ? WHERE id = ?", "Renamed", BOOK_ID.value)

      assertTrue(database.indexedTitleOf("BOOK", BOOK_ID.value).contains(MARKER))
    }
  }

  @Test
  fun `rewriting a series' name to the value it already holds does not rebuild its books`() {
    withCatalog("series-noop") { database ->
      database.staleTheIndex()

      database.dsl.execute("UPDATE series SET name = name WHERE id = ?", SERIES_ID.value)

      assertFalse(database.indexedTitleOf("SERIES", SERIES_ID.value).contains(MARKER))
      assertFalse(
        database.indexedTitleOf("BOOK", BOOK_ID.value).contains(MARKER),
        "a no-op series write must not cascade into its books",
      )
    }
  }

  @Test
  fun `renaming a series still rebuilds itself and its books`() {
    withCatalog("series-renamed") { database ->
      database.staleTheIndex()

      database.dsl.execute("UPDATE series SET name = ? WHERE id = ?", "Renamed", SERIES_ID.value)

      assertTrue(database.indexedTitleOf("SERIES", SERIES_ID.value).contains(MARKER))
      assertTrue(
        database.indexedTitleOf("BOOK", BOOK_ID.value).contains(MARKER),
        "a book's index row carries its series' title",
      )
    }
  }

  /**
   * The interior-match index is a second FTS table with a second set of triggers, so it needs the same
   * two facts established independently: that a real rename reaches it, and that a no-op write does
   * not. Its triggers carry V31's guard for the same reason the first index's do - a library re-scan
   * writes every matched book - and a guard that is present but wrong is indistinguishable from one
   * that is absent until something reads the index.
   */
  @Test
  fun `renaming a series rebuilds the interior-match index for itself and its books`() {
    withCatalog("series-substring-renamed") { database ->
      database.staleTheIndex()

      database.dsl.execute("UPDATE series SET name = ? WHERE id = ?", "Renamed", SERIES_ID.value)

      assertTrue(database.substringTitleOf("SERIES", SERIES_ID.value).contains(MARKER))
      assertTrue(
        database.substringTitleOf("BOOK", BOOK_ID.value).contains(MARKER),
        "a book's interior-match row carries its series' title",
      )
    }
  }

  @Test
  fun `rewriting a series' name to the value it already holds leaves the interior-match index alone`() {
    withCatalog("series-substring-noop") { database ->
      database.staleTheIndex()

      database.dsl.execute("UPDATE series SET name = name WHERE id = ?", SERIES_ID.value)

      assertFalse(database.substringTitleOf("SERIES", SERIES_ID.value).contains(MARKER))
      assertFalse(
        database.substringTitleOf("BOOK", BOOK_ID.value).contains(MARKER),
        "a no-op series write must not cascade into its books here either",
      )
    }
  }

  /**
   * Puts a marker into text the index derives from but is not reindexed on, so the index is now known
   * to be stale and the marker's later presence means a rebuild happened.
   */
  private fun XoboroDatabase.staleTheIndex() {
    dsl.execute("UPDATE series_metadata SET title = ? WHERE series_id = ?", MARKER, SERIES_ID.value)
    require(!indexedTitleOf("SERIES", SERIES_ID.value).contains(MARKER)) {
      "series_metadata writes are expected to leave the index stale; this test's premise is gone"
    }
    require(!indexedTitleOf("BOOK", BOOK_ID.value).contains(MARKER)) {
      "series_metadata writes are expected to leave the index stale; this test's premise is gone"
    }
    require(!substringTitleOf("SERIES", SERIES_ID.value).contains(MARKER)) {
      "series_metadata writes are expected to leave the index stale; this test's premise is gone"
    }
    require(!substringTitleOf("BOOK", BOOK_ID.value).contains(MARKER)) {
      "series_metadata writes are expected to leave the index stale; this test's premise is gone"
    }
  }

  private fun XoboroDatabase.indexedTitleOf(
    entityType: String,
    entityId: String,
  ): String = titleFrom("catalog_search_fts", entityType, entityId)

  private fun XoboroDatabase.substringTitleOf(
    entityType: String,
    entityId: String,
  ): String = titleFrom("catalog_title_substring", entityType, entityId)

  private fun XoboroDatabase.titleFrom(
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

  private fun withCatalog(
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
      JooqSeriesRepository(database).insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "series",
          sourceItemId = "file:///synthetic/series",
          fileModifiedAtMillis = 1_700_000_000_000L,
          bookCount = 1,
          createdAtMillis = 1_700_000_000_000L,
          updatedAtMillis = 1_700_000_000_000L,
        ),
      )
      JooqBookRepository(database).insert(
        Book(
          id = BOOK_ID,
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic book",
          relativePath = "series/book.cbz",
          sourceItemId = "file:///synthetic/series/book.cbz",
          sourceIdentity = "ino-1",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1_700_000_000_000L,
          fileSize = 100L,
          number = 1,
          createdAtMillis = 1_700_000_000_000L,
          updatedAtMillis = 1_700_000_000_000L,
        ),
      )
      block(database)
    }
  }

  private companion object {
    private val LIBRARY_ID = LibraryId("library-1")
    private val SERIES_ID = SeriesId("series-1")
    private val BOOK_ID = BookId("book-1")
    private const val MARKER = "zzmarkerzz"
  }
}
