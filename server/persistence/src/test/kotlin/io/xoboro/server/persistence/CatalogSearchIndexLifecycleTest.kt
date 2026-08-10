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
 * One invariant, checked after each thing that can move an entity through the search indexes:
 *
 * > every live entity has exactly one row in each index, both on the rowid its key names, and no
 * > index row exists that no key names.
 *
 * The other tests here each pin one trigger. This pins the property those triggers exist to hold, so
 * a future change that keeps every individual assertion true while breaking the whole still fails.
 * It covers the three paths nothing else does: reusing a freed rowid, moving a book between series,
 * and emptying the catalogue.
 */
class CatalogSearchIndexLifecycleTest {
  @TempDir
  lateinit var tempDirectory: Path

  /**
   * Deleting the entity whose row holds the highest rowid frees that number, and SQLite hands an
   * omitted rowid `max(rowid) + 1` - so the next insert is offered exactly the number just vacated.
   * Anything that let the two indexes disagree about who owns it would show up here and nowhere else.
   */
  @Test
  fun `an entity indexed into a freed rowid holds the invariant`() {
    withCatalog("freed-rowid") { database ->
      database.insertBook(BookId("book-2"), SERIES_ID, "second.cbz")
      database.assertIndexInvariant("two books")

      database.dsl.execute("DELETE FROM book WHERE id = ?", "book-2")
      database.assertIndexInvariant("highest rowid freed")

      database.insertBook(BookId("book-3"), SERIES_ID, "third.cbz")
      database.assertIndexInvariant("reused the freed rowid")
    }
  }

  /**
   * A book's indexed title carries its series' name, so moving it rewrites its row - through a
   * different trigger than the one that wrote it.
   */
  @Test
  fun `moving a book to another series holds the invariant`() {
    withCatalog("moved-book") { database ->
      database.insertSeries(SeriesId("series-2"), "Beta series", "beta")
      database.assertIndexInvariant("second series added")

      database.dsl.execute("UPDATE book SET series_id = ? WHERE id = ?", "series-2", BOOK_ID.value)

      database.assertIndexInvariant("book moved")
      assertEquals(
        database.keyRowid("BOOK", BOOK_ID.value),
        database.indexRowid("catalog_search_fts", "BOOK", BOOK_ID.value),
        "a move must rewrite the row in place rather than re-keying it",
      )
    }
  }

  /**
   * Emptying the catalogue is the only way to see a key that outlived what it addressed. One left
   * behind is invisible until an id is reused, and then it points at the wrong thing.
   */
  @Test
  fun `deleting everything leaves no index row and no key`() {
    withCatalog("emptied") { database ->
      database.insertBook(BookId("book-2"), SERIES_ID, "second.cbz")

      database.dsl.execute("DELETE FROM book")
      database.dsl.execute("DELETE FROM series")

      assertEquals(0, database.count("catalog_search_fts"))
      assertEquals(0, database.count("catalog_title_substring"))
      assertEquals(0, database.count("catalog_search_key"))
    }
  }

  private fun XoboroDatabase.assertIndexInvariant(stage: String) {
    assertEquals(
      0,
      countOf(
        """
        SELECT count(*) FROM catalog_search_fts f
        LEFT JOIN catalog_search_key k ON k.index_rowid = f.rowid
        WHERE k.entity_id IS NULL OR k.entity_id <> f.entity_id OR k.entity_type <> f.entity_type
        """.trimIndent(),
      ),
      "$stage: a word-index row sits on a rowid no key names",
    )
    assertEquals(
      0,
      countOf(
        """
        SELECT count(*) FROM catalog_title_substring t
        LEFT JOIN catalog_search_key k ON k.index_rowid = t.rowid
        WHERE k.entity_id IS NULL OR k.entity_id <> t.entity_id OR k.entity_type <> t.entity_type
        """.trimIndent(),
      ),
      "$stage: an interior-match row sits on a rowid no key names",
    )
    listOf("catalog_search_fts", "catalog_title_substring").forEach { table ->
      assertEquals(
        0,
        countOf(
          """
          SELECT count(*) FROM book WHERE deleted_at_ms IS NULL
            AND (SELECT count(*) FROM $table
                 WHERE entity_type = 'BOOK' AND entity_id = book.id) <> 1
          """.trimIndent(),
        ),
        "$stage: a live book is not indexed exactly once in $table",
      )
      assertEquals(
        0,
        countOf(
          """
          SELECT count(*) FROM series WHERE deleted_at_ms IS NULL
            AND (SELECT count(*) FROM $table
                 WHERE entity_type = 'SERIES' AND entity_id = series.id) <> 1
          """.trimIndent(),
        ),
        "$stage: a live series is not indexed exactly once in $table",
      )
    }
  }

  private fun XoboroDatabase.countOf(sql: String): Int =
    dsl.fetchOne(sql)?.get(0, Int::class.java) ?: error("count query returned nothing")

  private fun XoboroDatabase.count(table: String): Int = countOf("SELECT count(*) FROM $table")

  private fun XoboroDatabase.keyRowid(
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

  private fun XoboroDatabase.indexRowid(
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
    fileName: String,
  ) {
    JooqBookRepository(this).insert(
      Book(
        id = bookId,
        libraryId = LIBRARY_ID,
        seriesId = seriesId,
        name = "Book ${bookId.value}",
        relativePath = "series/$fileName",
        sourceItemId = "file:///synthetic/series/$fileName",
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
      database.insertSeries(SERIES_ID, "Alpha series", "series")
      database.insertBook(BOOK_ID, SERIES_ID, "book.cbz")
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
