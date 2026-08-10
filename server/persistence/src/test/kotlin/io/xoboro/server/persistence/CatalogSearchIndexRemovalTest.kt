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
import org.junit.jupiter.api.io.TempDir

/**
 * Removing an entity has to take both of its index rows and the key that addresses them, together.
 *
 * V36 made every index write address its row by `catalog_search_key.index_rowid`, which turns the
 * lifetime of that key into a correctness property rather than bookkeeping. A key removed while an
 * index row still points at it strands the row: nothing can name it again, so it answers searches for
 * a book that no longer exists and no later delete can reach it. A key kept after both rows are gone
 * is merely litter, but it is the same defect seen from the other side, so the count is asserted in
 * both directions.
 *
 * This is why the two delete triggers were merged into one body. SQLite does not define the order of
 * two `AFTER DELETE` triggers on one table, so with the key removal in one of them the other would
 * resolve `rowid = NULL` and leave its row behind - a failure that depends on trigger order and would
 * therefore appear only sometimes.
 */
class CatalogSearchIndexRemovalTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `deleting a book takes both index rows and its key`() {
    withCatalog("book-delete") { database ->
      assertEquals(1, database.indexRowCount("catalog_search_fts", "BOOK", BOOK_ID.value))
      assertEquals(1, database.indexRowCount("catalog_title_substring", "BOOK", BOOK_ID.value))
      assertEquals(1, database.keyCount("BOOK", BOOK_ID.value))

      database.dsl.execute("DELETE FROM book WHERE id = ?", BOOK_ID.value)

      assertEquals(0, database.indexRowCount("catalog_search_fts", "BOOK", BOOK_ID.value))
      assertEquals(0, database.indexRowCount("catalog_title_substring", "BOOK", BOOK_ID.value))
      assertEquals(0, database.keyCount("BOOK", BOOK_ID.value))
    }
  }

  @Test
  fun `deleting a series takes both of its index rows and its key`() {
    withCatalog("series-delete") { database ->
      database.dsl.execute("DELETE FROM book WHERE id = ?", BOOK_ID.value)

      database.dsl.execute("DELETE FROM series WHERE id = ?", SERIES_ID.value)

      assertEquals(0, database.indexRowCount("catalog_search_fts", "SERIES", SERIES_ID.value))
      assertEquals(0, database.indexRowCount("catalog_title_substring", "SERIES", SERIES_ID.value))
      assertEquals(0, database.keyCount("SERIES", SERIES_ID.value))
    }
  }

  /**
   * A rename rewrites the row in place. Under a rowid the entity already owns, writing it twice is a
   * constraint failure rather than a duplicate, so this pins the count as well as the survival.
   */
  @Test
  fun `renaming a book leaves exactly one row in each index`() {
    withCatalog("book-rename") { database ->
      database.dsl.execute("UPDATE book SET name = ? WHERE id = ?", "Renamed", BOOK_ID.value)

      assertEquals(1, database.indexRowCount("catalog_search_fts", "BOOK", BOOK_ID.value))
      assertEquals(1, database.indexRowCount("catalog_title_substring", "BOOK", BOOK_ID.value))
      assertEquals(1, database.keyCount("BOOK", BOOK_ID.value))
    }
  }

  @Test
  fun `renaming a series leaves exactly one row for it and for each of its books`() {
    withCatalog("series-rename") { database ->
      database.dsl.execute("UPDATE series SET name = ? WHERE id = ?", "Renamed", SERIES_ID.value)

      assertEquals(1, database.indexRowCount("catalog_search_fts", "SERIES", SERIES_ID.value))
      assertEquals(1, database.indexRowCount("catalog_title_substring", "SERIES", SERIES_ID.value))
      assertEquals(1, database.indexRowCount("catalog_search_fts", "BOOK", BOOK_ID.value))
      assertEquals(1, database.indexRowCount("catalog_title_substring", "BOOK", BOOK_ID.value))
    }
  }

  /**
   * Metadata edits go through Kotlin rather than a trigger, and they are the path that used to read
   * the whole index for one book. They have to agree with the triggers about which rowid a book's row
   * lives on, or the next trigger-driven write would fail to find it.
   *
   * The two books after it are not scenery. A rebuild deletes the row and writes it again, and SQLite
   * hands an omitted rowid `max(rowid) + 1` - which, when the row just deleted held the highest number
   * in the table, is the number it just freed. With one book in the catalogue a rebuild that ignored
   * the key landed on the right rowid by accident and this test passed while the code was wrong.
   * Rebuilding a book that is not the last one indexed removes the coincidence.
   */
  @Test
  fun `rebuilding a book's document from the metadata path keeps one row under its key`() {
    withCatalog("metadata-rebuild") { database ->
      database.insertBook(BookId("book-2"), "series/second.cbz")
      database.insertBook(BookId("book-3"), "series/third.cbz")

      database.dsl.rebuildBookSearchDocument(BOOK_ID)

      assertEquals(1, database.indexRowCount("catalog_search_fts", "BOOK", BOOK_ID.value))
      assertEquals(1, database.indexRowCount("catalog_title_substring", "BOOK", BOOK_ID.value))
      assertEquals(
        database.keyRowidOf("BOOK", BOOK_ID.value),
        database.indexRowidOf("catalog_search_fts", "BOOK", BOOK_ID.value),
        "the metadata path must store a book's row under the same key the triggers use",
      )
      assertEquals(
        database.keyRowidOf("BOOK", BOOK_ID.value),
        database.indexRowidOf("catalog_title_substring", "BOOK", BOOK_ID.value),
      )
    }
  }

  private fun XoboroDatabase.insertBook(
    bookId: BookId,
    relativePath: String,
  ) {
    JooqBookRepository(this).insert(
      Book(
        id = bookId,
        libraryId = LIBRARY_ID,
        seriesId = SERIES_ID,
        name = "Book ${bookId.value}",
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

  private fun XoboroDatabase.keyCount(
    entityType: String,
    entityId: String,
  ): Int =
    dsl
      .fetchOne(
        "SELECT count(*) FROM catalog_search_key WHERE entity_type = ? AND entity_id = ?",
        entityType,
        entityId,
      )?.get(0, Int::class.java)
      ?: error("counting keys returned nothing")

  private fun XoboroDatabase.keyRowidOf(
    entityType: String,
    entityId: String,
  ): Long =
    dsl
      .fetchOne(
        "SELECT index_rowid FROM catalog_search_key WHERE entity_type = ? AND entity_id = ?",
        entityType,
        entityId,
      )?.get(0, Long::class.java)
      ?: error("$entityType $entityId has no key")

  private fun XoboroDatabase.indexRowidOf(
    table: String,
    entityType: String,
    entityId: String,
  ): Long =
    dsl
      .fetchOne(
        "SELECT rowid FROM $table WHERE entity_type = ? AND entity_id = ?",
        entityType,
        entityId,
      )?.get(0, Long::class.java)
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
          fileModifiedAtMillis = TIMESTAMP,
          bookCount = 1,
          createdAtMillis = TIMESTAMP,
          updatedAtMillis = TIMESTAMP,
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
          fileModifiedAtMillis = TIMESTAMP,
          fileSize = 100L,
          number = 1,
          createdAtMillis = TIMESTAMP,
          updatedAtMillis = TIMESTAMP,
        ),
      )
      block(database)
    }
  }

  private companion object {
    private val LIBRARY_ID = LibraryId("library-1")
    private val SERIES_ID = SeriesId("series-1")
    private val BOOK_ID = BookId("book-1")
    private const val TIMESTAMP = 1_700_000_000_000L
  }
}
