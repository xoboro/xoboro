package io.xoboro.server.persistence

import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogPageRequest
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
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

/**
 * `book.series_title_sort` and `book.number_sort` are copies. What makes them safe is that the
 * database maintains them, so these exercise every write that can move the original and check the
 * copy went with it - and check that the copy is what buys the index-served order.
 */
class BookListingOrderColumnsTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `the default media-item order is served by an index, not by a temp sort`() {
    withCatalog { database, _ ->
      val plan =
        database.dsl
          .fetch(
            """
            EXPLAIN QUERY PLAN
            SELECT b.id FROM book b
            WHERE b.deleted_at_ms IS NULL
            ORDER BY b.series_title_sort COLLATE NOCASE ASC, b.number_sort ASC,
                     b.relative_uri ASC, b.id ASC
            LIMIT 24
            """.trimIndent(),
          ).joinToString("\n") { it.get("detail", String::class.java).orEmpty() }

      assertFalse(plan.contains("TEMP B-TREE"), "the default order sorted the table: $plan")
    }
  }

  @Test
  fun `inserting a book copies its series title sort`() {
    withCatalog { database, _ ->
      assertEquals(emptyList(), database.mismatchedTitleSorts())
    }
  }

  @Test
  fun `renaming a series carries the copy to every book in it`() {
    withCatalog { database, _ ->
      database.dsl.execute(
        "UPDATE series_metadata SET title_sort = ? WHERE series_id = ?",
        "Zzz renamed",
        "series-a",
      )

      assertEquals(emptyList(), database.mismatchedTitleSorts())
      assertEquals(
        listOf("Zzz renamed", "Zzz renamed"),
        database.dsl
          .fetch("SELECT series_title_sort FROM book WHERE series_id = 'series-a' ORDER BY id")
          .map { it.get("series_title_sort", String::class.java) },
      )
    }
  }

  @Test
  fun `changing a book's number sort carries the copy`() {
    withCatalog { database, _ ->
      database.dsl.execute(
        "UPDATE book_metadata SET number_sort = ? WHERE book_id = ?",
        42.0,
        "book-a-1",
      )

      assertEquals(emptyList(), database.mismatchedNumberSorts())
      assertEquals(
        42.0,
        database.dsl
          .fetchOne("SELECT number_sort FROM book WHERE id = 'book-a-1'")
          ?.get("number_sort", Double::class.java),
      )
    }
  }

  /**
   * The path production actually takes. Both metadata repositories write with
   * `ON CONFLICT DO UPDATE`, and the scanner reaches the columns through them rather than through
   * a bare UPDATE, so the trigger has to fire for the conflict branch of an upsert too.
   */
  @Test
  fun `upserting metadata through the repositories carries both copies`() {
    withCatalog { database, _ ->
      val seriesMetadata = JooqSeriesMetadataRepository(database)
      val existing = requireNotNull(seriesMetadata.findBySeriesIdOrNull(SeriesId("series-a")))
      seriesMetadata.upsert(existing.copy(titleSort = "Upserted sort"))

      val bookMetadata = JooqBookMetadataRepository(database)
      val existingBook = requireNotNull(bookMetadata.findByBookIdOrNull(BookId("book-a-1")))
      bookMetadata.upsert(existingBook.copy(numberSort = 99.5F))

      assertEquals(emptyList(), database.mismatchedTitleSorts())
      assertEquals(emptyList(), database.mismatchedNumberSorts())
      assertEquals(
        "Upserted sort",
        database.dsl
          .fetchOne("SELECT series_title_sort FROM book WHERE id = 'book-a-1'")
          ?.get("series_title_sort", String::class.java),
      )
      assertEquals(
        99.5,
        database.dsl
          .fetchOne("SELECT number_sort FROM book WHERE id = 'book-a-1'")
          ?.get("number_sort", Double::class.java),
      )
    }
  }

  @Test
  fun `moving a book to another series carries the copy`() {
    withCatalog { database, _ ->
      database.dsl.execute("UPDATE book SET series_id = ? WHERE id = ?", "series-b", "book-a-1")

      assertEquals(emptyList(), database.mismatchedTitleSorts())
    }
  }

  /**
   * The copies exist to be sorted on, so the order they produce has to be the order the originals
   * produced. Read through the repository rather than through SQL, since that is what changed.
   */
  @Test
  fun `the listing order matches the order the joined columns would give`() {
    withCatalog { database, catalog ->
      val throughCopies =
        catalog
          .findBooks(BookCatalogQuery(), CatalogAccess(), CatalogPageRequest(unpaged = true))
          .content
          .map { it.book.id.value }
      val throughOriginals =
        database.dsl
          .fetch(
            """
            SELECT b.id
            FROM book b
            JOIN book_metadata bm ON bm.book_id = b.id
            JOIN series_metadata sm ON sm.series_id = b.series_id
            WHERE b.deleted_at_ms IS NULL
            ORDER BY sm.title_sort COLLATE NOCASE ASC, bm.number_sort ASC,
                     b.relative_uri ASC, b.id ASC
            """.trimIndent(),
          ).map { it.get("id", String::class.java) }

      assertEquals(throughOriginals, throughCopies)
    }
  }

  private fun XoboroDatabase.mismatchedTitleSorts(): List<String?> =
    dsl
      .fetch(
        """
        SELECT b.id
        FROM book b
        JOIN series_metadata sm ON sm.series_id = b.series_id
        WHERE b.series_title_sort <> sm.title_sort
        """.trimIndent(),
      ).map { it.get("id", String::class.java) }

  private fun XoboroDatabase.mismatchedNumberSorts(): List<String?> =
    dsl
      .fetch(
        """
        SELECT b.id
        FROM book b
        JOIN book_metadata bm ON bm.book_id = b.id
        WHERE b.number_sort <> bm.number_sort
        """.trimIndent(),
      ).map { it.get("id", String::class.java) }

  private fun withCatalog(block: (XoboroDatabase, JooqCatalogReadRepository) -> Unit) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("order-columns.sqlite"))).use { database ->
      val libraryId = LibraryId("library-1")
      JooqLibraryRepository(database).insert(
        Library(
          id = libraryId,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      val seriesRepository = JooqSeriesRepository(database)
      val bookRepository = JooqBookRepository(database)
      listOf("a" to "Andromeda", "b" to "Boötes").forEach { (suffix, title) ->
        seriesRepository.insert(
          Series(
            id = SeriesId("series-$suffix"),
            libraryId = libraryId,
            name = title,
            relativePath = "series-$suffix",
            sourceItemId = "file:///synthetic/series-$suffix",
            fileModifiedAtMillis = 1,
            bookCount = 2,
            createdAtMillis = 1,
          ),
        )
        repeat(2) { index ->
          val number = index + 1
          bookRepository.insert(
            Book(
              id = BookId("book-$suffix-$number"),
              libraryId = libraryId,
              seriesId = SeriesId("series-$suffix"),
              name = "$title chapter $number",
              relativePath = "series-$suffix/chapter-$number.cbz",
              sourceItemId = "file:///synthetic/series-$suffix/chapter-$number.cbz",
              mediaKind = MediaKind.COMIC_ARCHIVE,
              fileModifiedAtMillis = number.toLong(),
              fileSize = number * 1_024L,
              number = number,
              createdAtMillis = number.toLong(),
            ),
          )
        }
      }
      block(database, JooqCatalogReadRepository(database))
    }
  }
}
