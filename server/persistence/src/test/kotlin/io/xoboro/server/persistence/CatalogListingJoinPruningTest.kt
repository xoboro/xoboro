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
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * Rendering the FROM clause per query moves parameters around: a dropped join takes its bindings
 * with it, so every remaining `?` shifts. These sort by each joined alias in turn, including the
 * two whose ON clause carries the reader's id, so a misplaced parameter shows up as wrong rows
 * rather than as a silent pass.
 *
 * They page rather than ask for everything, and filter by library rather than not at all, so that
 * the statement actually carries parameters: an unpaged, unfiltered listing has no `?` for a
 * stray binding to displace, and jOOQ discards the extras without a word.
 */
class CatalogListingJoinPruningTest {
  @TempDir
  lateinit var tempDirectory: Path

  private val reader = UserId("reader-1")

  /**
   * Every sort the listing accepts, so each one exercises a different set of kept joins - and the
   * two progress sorts sit either side of each other, which is the case that would misalign the
   * reader's id if a dropped join left its binding behind.
   */
  private val everySort =
    listOf(
      "created",
      "title",
      "number",
      "seriesTitle",
      "url",
      "media.status",
      "media.pagesCount",
      "metadata.releaseDate",
      "readProgress.readDate",
      "readProgress.seriesReadDate",
    )

  @Test
  fun `every sort returns every book, whichever joins it keeps`() {
    withCatalog { catalog ->
      everySort.forEach { property ->
        listOf(CatalogSortDirection.ASC, CatalogSortDirection.DESC).forEach { direction ->
          val page =
            catalog.findBooks(
              BookCatalogQuery(libraryIds = setOf(LibraryId("library-1"))),
              CatalogAccess(userId = reader),
              CatalogPageRequest(
                size = 10,
                sorts = listOf(CatalogSort(property, direction)),
              ),
            )

          assertEquals(
            EVERY_BOOK,
            page.content.map { it.book.id.value }.sorted(),
            "sorting by $property $direction did not return every book",
          )
          assertEquals(
            EVERY_BOOK.size.toLong(),
            page.totalElements,
            "sorting by $property $direction gave a total that disagreed with its rows",
          )
        }
      }
    }
  }

  /**
   * The reader's own progress decides this order, so a listing that lost or misplaced the id
   * would come back in a different order rather than merely be slower.
   */
  @Test
  fun `a progress sort reads the requesting reader's progress`() {
    withCatalog { catalog ->
      val mine =
        catalog
          .findBooks(
            BookCatalogQuery(libraryIds = setOf(LibraryId("library-1"))),
            CatalogAccess(userId = reader),
            CatalogPageRequest(
              size = 10,
              sorts = listOf(CatalogSort("readProgress.readDate", CatalogSortDirection.DESC)),
            ),
          ).content
          .map { it.book.id.value }
      val strangers =
        catalog
          .findBooks(
            BookCatalogQuery(libraryIds = setOf(LibraryId("library-1"))),
            CatalogAccess(userId = UserId("reader-2")),
            CatalogPageRequest(
              size = 10,
              sorts = listOf(CatalogSort("readProgress.readDate", CatalogSortDirection.DESC)),
            ),
          ).content
          .map { it.book.id.value }

      assertEquals("book-a-2", mine.first(), "the reader's most recently read book was not first")
      assertEquals(EVERY_BOOK, strangers.sorted(), "another reader lost rows")
    }
  }

  private fun withCatalog(block: (JooqCatalogReadRepository) -> Unit) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("pruning.sqlite"))).use { database ->
      listOf(reader, UserId("reader-2")).forEach { id ->
        JooqUserRepository(database).insert(
          User(
            id = id,
            email = "${id.value}@example.invalid",
            passwordHash = "synthetic-password-hash",
            createdAtMillis = 1,
          ),
        )
      }
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
      database.dsl.execute(
        """
        INSERT INTO media (book_id, status, page_count, created_at_ms, updated_at_ms)
        VALUES ('book-a-1', 'READY', 3, 1, 1), ('book-a-2', 'READY', 4, 1, 1)
        """.trimIndent(),
      )
      // Only this reader has progress, and only on one book: a listing that read someone else's
      // progress, or none, would order these differently.
      database.dsl.execute(
        """
        INSERT INTO read_progress (
          book_id, user_id, page, completed, read_at_ms, created_at_ms, updated_at_ms
        ) VALUES ('book-a-2', 'reader-1', 1, 0, 9000, 1, 1)
        """.trimIndent(),
      )
      block(JooqCatalogReadRepository(database))
    }
  }

  private companion object {
    private val EVERY_BOOK =
      listOf("book-a-1", "book-a-2", "book-b-1", "book-b-2")
  }
}
