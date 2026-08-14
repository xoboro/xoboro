package io.xoboro.server.persistence

import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
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
 * The book-local listing orders have to be served by an index rather than by sorting the table.
 *
 * These check the plan for the ORDER BY shapes the listing emits - `<column> <direction>, id
 * <same direction>` - and, separately, that the listing really does return rows in that order in
 * both directions. What they do not do is read the SQL out of the repository, so a change that
 * altered the emitted order without touching these strings would leave them passing; the ordering
 * assertions below are the guard against that.
 */
class CatalogListingOrderPlanTest {
  @TempDir
  lateinit var tempDirectory: Path

  /** The book-local listing sorts, as the repository names them and as the table stores them. */
  private val bookLocalOrders =
    listOf(
      "created" to "created_at_ms",
      "lastModified" to "updated_at_ms",
      "fileLastModified" to "file_modified_ms",
      "fileSize" to "file_size",
    )

  @Test
  fun `a book-local listing order is served by an index, not by a temp sort`() {
    withCatalog { database, _ ->
      bookLocalOrders.forEach { (_, column) ->
        listOf("ASC", "DESC").forEach { direction ->
          val plan = database.planFor(column, direction, direction)

          assertFalse(
            plan.contains("TEMP B-TREE"),
            "ORDER BY b.$column $direction, b.id $direction sorted the table: $plan",
          )
        }
      }
    }
  }

  @Test
  fun `every book-local sort the listing accepts is one of the indexed ones`() {
    withCatalog { _, catalog ->
      bookLocalOrders.forEach { (property, _) ->
        val page =
          catalog.findBooks(
            BookCatalogQuery(),
            CatalogAccess(),
            CatalogPageRequest(size = 10, sorts = listOf(CatalogSort(property))),
          )

        assertEquals(3, page.content.size, "the listing refused to sort by $property")
      }
    }
  }

  /**
   * A mixed order is what the index cannot serve, and is what the listing used to emit. Kept as a
   * live demonstration that the plan assertion above is measuring something real.
   */
  @Test
  fun `a mixed-direction order still falls back to a temp sort`() {
    withCatalog { database, _ ->
      val plan = database.planFor("created_at_ms", "DESC", "ASC")

      assertEquals(true, plan.contains("TEMP B-TREE"), plan)
    }
  }

  @Test
  fun `the listing orders by the requested column in both directions`() {
    withCatalog { _, catalog ->
      listOf(
        CatalogSortDirection.ASC to listOf("book-1", "book-2", "book-3"),
        CatalogSortDirection.DESC to listOf("book-3", "book-2", "book-1"),
      ).forEach { (direction, expected) ->
        val page =
          catalog.findBooks(
            BookCatalogQuery(),
            CatalogAccess(),
            CatalogPageRequest(size = 10, sorts = listOf(CatalogSort("created", direction))),
          )

        assertEquals(expected, page.content.map { it.book.id.value }, "order was wrong for $direction")
      }
    }
  }

  private fun XoboroDatabase.planFor(
    column: String,
    direction: String,
    tieDirection: String,
  ): String =
    dsl
      .fetch(
        """
        EXPLAIN QUERY PLAN
        SELECT b.id FROM book b
        WHERE b.deleted_at_ms IS NULL
        ORDER BY b.$column $direction, b.id $tieDirection
        LIMIT 24
        """.trimIndent(),
      ).joinToString("\n") { it.get("detail", String::class.java).orEmpty() }

  private fun withCatalog(block: (XoboroDatabase, JooqCatalogReadRepository) -> Unit) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("order-plan.sqlite"))).use { database ->
      val libraryId = LibraryId("library-1")
      JooqLibraryRepository(database).insert(
        Library(
          id = libraryId,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SeriesId("series-a"),
          libraryId = libraryId,
          name = "Synthetic series",
          relativePath = "series-a",
          sourceItemId = "file:///synthetic/series-a",
          fileModifiedAtMillis = 1,
          bookCount = 3,
          createdAtMillis = 1,
        ),
      )
      val bookRepository = JooqBookRepository(database)
      repeat(3) { index ->
        val number = index + 1
        bookRepository.insert(
          Book(
            id = BookId("book-$number"),
            libraryId = libraryId,
            seriesId = SeriesId("series-a"),
            name = "Synthetic chapter $number",
            relativePath = "series-a/chapter-$number.cbz",
            sourceItemId = "file:///synthetic/series-a/chapter-$number.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = number.toLong(),
            fileSize = number * 1_024L,
            number = number,
            createdAtMillis = number.toLong(),
          ),
        )
      }
      block(database, JooqCatalogReadRepository(database))
    }
  }
}
