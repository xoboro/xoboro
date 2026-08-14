package io.xoboro.server.persistence

import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import io.xoboro.core.application.SeriesCatalogQuery
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
 * A listing counts its rows through a narrower FROM clause than it pages them through, because a
 * count reads none of the metadata columns. These pin the two together: whatever a query counts is
 * what it would return unpaged, whether or not the filter reaches into a joined table.
 */
class JooqCatalogListingTotalsTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `a book listing counts exactly the rows it would return`() {
    withCatalog { catalog ->
      listOf(
        BookCatalogQuery(),
        BookCatalogQuery(libraryIds = setOf(LibraryId("library-1"))),
        BookCatalogQuery(seriesId = SeriesId("series-a")),
        BookCatalogQuery(fullTextSearch = "Andromeda"),
        BookCatalogQuery(
          condition =
            CatalogSearchCondition.Predicate(
              field = CatalogSearchField.TITLE,
              operator = CatalogSearchOperator.CONTAINS,
              value = "chapter",
            ),
        ),
      ).forEach { query ->
        val everything = catalog.findBooks(query, CatalogAccess(), CatalogPageRequest(unpaged = true))

        assertEquals(
          everything.content.size.toLong(),
          everything.totalElements,
          "total did not match the rows returned for $query",
        )
      }
    }
  }

  @Test
  fun `a series listing counts exactly the rows it would return`() {
    withCatalog { catalog ->
      listOf(
        SeriesCatalogQuery(),
        SeriesCatalogQuery(libraryIds = setOf(LibraryId("library-1"))),
        SeriesCatalogQuery(fullTextSearch = "Andromeda"),
      ).forEach { query ->
        val everything =
          catalog.findSeries(query, CatalogAccess(), CatalogPageRequest(unpaged = true))

        assertEquals(
          everything.content.size.toLong(),
          everything.totalElements,
          "total did not match the rows returned for $query",
        )
      }
    }
  }

  /**
   * The count for a filter that reaches into series metadata has to keep that join, or it counts
   * the whole catalog instead of the part the reader asked for.
   */
  @Test
  fun `a filter on a joined table still narrows the total`() {
    withCatalog { catalog ->
      val narrowed =
        catalog.findSeries(
          SeriesCatalogQuery(
            condition =
              CatalogSearchCondition.Predicate(
                field = CatalogSearchField.TITLE,
                operator = CatalogSearchOperator.CONTAINS,
                value = "Andromeda",
              ),
          ),
          CatalogAccess(),
          CatalogPageRequest(unpaged = true),
        )
      val everything =
        catalog.findSeries(SeriesCatalogQuery(), CatalogAccess(), CatalogPageRequest(unpaged = true))

      assertEquals(1, narrowed.totalElements)
      assertTrue(everything.totalElements > narrowed.totalElements, "the filter narrowed nothing")
    }
  }

  private fun withCatalog(block: (JooqCatalogReadRepository) -> Unit) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("totals.sqlite"))).use { database ->
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
      listOf("a" to "Andromeda", "b" to "Boötes").forEachIndexed { index, (suffix, title) ->
        val seriesId = SeriesId("series-$suffix")
        seriesRepository.insert(
          Series(
            id = seriesId,
            libraryId = libraryId,
            name = title,
            relativePath = "series-$suffix",
            sourceItemId = "file:///synthetic/series-$suffix",
            fileModifiedAtMillis = index.toLong() + 1,
            bookCount = 2,
            createdAtMillis = 1,
          ),
        )
        repeat(2) { chapter ->
          val number = chapter + 1
          bookRepository.insert(
            Book(
              id = BookId("book-$suffix-$number"),
              libraryId = libraryId,
              seriesId = seriesId,
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
      block(JooqCatalogReadRepository(database))
    }
  }
}
