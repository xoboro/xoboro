package io.xoboro.server.persistence

import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.AlternateTitle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqCatalogReadRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `pages searches sorts and resolves book siblings in the database`() {
    withCatalog("paging") { database ->
      val catalog = JooqCatalogReadRepository(database)

      val page =
        catalog.findBooks(
          query =
            BookCatalogQuery(
              seriesId = SeriesId("series-a"),
              fullTextSearch = "chapter",
            ),
          access = CatalogAccess(),
          page =
            CatalogPageRequest(
              size = 2,
              sorts = listOf(CatalogSort("numberSort", CatalogSortDirection.DESC)),
            ),
        )

      assertEquals(3, page.totalElements)
      assertEquals(listOf("book-3", "book-2"), page.content.map { it.book.id.value })
      assertEquals(
        "book-1",
        catalog
          .findPreviousBookOrNull(BookId("book-2"), CatalogAccess())
          ?.book
          ?.id
          ?.value,
      )
      assertEquals(
        "book-3",
        catalog
          .findNextBookOrNull(BookId("book-2"), CatalogAccess())
          ?.book
          ?.id
          ?.value,
      )
      assertNull(catalog.findPreviousBookOrNull(BookId("book-1"), CatalogAccess()))
    }
  }

  @Test
  fun `applies library age and sharing label restrictions before paging`() {
    withCatalog("restrictions") { database ->
      val otherLibraryId = LibraryId("library-2")
      JooqLibraryRepository(database).insert(
        Library(
          id = otherLibraryId,
          name = "Other synthetic library",
          root = SourceLocation("local", "file:///other-synthetic"),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SeriesId("series-c"),
          libraryId = otherLibraryId,
          name = "Other synthetic series",
          relativePath = "series-c",
          sourceItemId = "file:///other-synthetic/series-c",
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      val metadata = JooqSeriesMetadataRepository(database)
      metadata.upsert(
        metadata.findBySeriesIdOrNull(SeriesId("series-a"))!!.copy(
          ageRating = 10,
          sharingLabels = setOf("family"),
          updatedAtMillis = 2,
        ),
      )
      metadata.upsert(
        metadata.findBySeriesIdOrNull(SeriesId("series-b"))!!.copy(
          ageRating = 18,
          sharingLabels = setOf("blocked"),
          updatedAtMillis = 2,
        ),
      )
      val access =
        CatalogAccess(
          libraryIds = setOf(LibraryId("library-1")),
          restrictions =
            ContentRestrictions(
              ageRestriction = AgeRestriction(15, RestrictionMode.EXCLUDE),
              labelsExclude = setOf("blocked"),
            ),
        )
      val catalog = JooqCatalogReadRepository(database)

      val result =
        catalog.findSeries(
          query = SeriesCatalogQuery(deleted = false),
          access = access,
          page = CatalogPageRequest(size = 20),
        )

      assertEquals(1, result.totalElements)
      assertEquals(listOf("series-a"), result.content.map { it.series.id.value })
      assertNull(catalog.findSeriesByIdOrNull(SeriesId("series-b"), access))
    }
  }

  @Test
  fun `filters series metadata and groups sort titles`() {
    withCatalog("groups") { database ->
      val metadata = JooqSeriesMetadataRepository(database)
      metadata.upsert(
        metadata.findBySeriesIdOrNull(SeriesId("series-a"))!!.copy(
          titleSort = "Alpha synthetic",
          publisher = "Publisher A",
          genres = setOf("adventure"),
          updatedAtMillis = 2,
        ),
      )
      metadata.upsert(
        metadata.findBySeriesIdOrNull(SeriesId("series-b"))!!.copy(
          titleSort = "Beta synthetic",
          publisher = "Publisher B",
          genres = setOf("drama"),
          updatedAtMillis = 2,
        ),
      )
      val catalog = JooqCatalogReadRepository(database)

      val filtered =
        catalog.findSeries(
          query =
            SeriesCatalogQuery(
              publishers = setOf("Publisher A"),
              genres = setOf("Adventure"),
            ),
          access = CatalogAccess(),
          page = CatalogPageRequest(),
        )
      val groups =
        catalog.countSeriesByFirstCharacter(
          SeriesCatalogQuery(deleted = false),
          CatalogAccess(),
        )

      assertEquals(listOf("series-a"), filtered.content.map { it.series.id.value })
      assertEquals(listOf("A" to 1, "B" to 1), groups.map { it.group to it.count })
    }
  }

  @Test
  fun `finds only books sharing both file hash and size`() {
    withCatalog("duplicates") { database ->
      val books = JooqBookRepository(database)
      val first = requireNotNull(books.findByIdOrNull(BookId("book-1")))
      val second = requireNotNull(books.findByIdOrNull(BookId("book-2")))
      val differentSize = requireNotNull(books.findByIdOrNull(BookId("book-3")))
      books.update(first.copy(fileHash = "shared-file", fileSize = 100))
      books.update(second.copy(fileHash = "shared-file", fileSize = 100))
      books.update(differentSize.copy(fileHash = "shared-file", fileSize = 101))

      val duplicates =
        JooqCatalogReadRepository(database).findBooks(
          query = BookCatalogQuery(duplicatesOnly = true),
          access = CatalogAccess(),
          page = CatalogPageRequest(),
        )

      assertEquals(2, duplicates.totalElements)
      assertEquals(setOf("book-1", "book-2"), duplicates.content.map { it.book.id.value }.toSet())
    }
  }

  @Test
  fun `pages keep reading entirely in SQL beyond the former memory boundary`() {
    withCatalog("keep-reading") { database ->
      val userId = UserId("reader-1")
      JooqUserRepository(database).insert(
        User(
          id = userId,
          email = "reader@example.invalid",
          passwordHash = "synthetic-password-hash",
          createdAtMillis = 1,
        ),
      )
      database.transaction { transaction ->
        transaction.execute("CREATE TEMP TABLE synthetic_number (number INTEGER PRIMARY KEY)")
        transaction.execute(
          """
          INSERT INTO synthetic_number (number)
          WITH digits(value) AS (
            VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9)
          )
          SELECT
            ones.value + tens.value * 10 + hundreds.value * 100 +
              thousands.value * 1000 + ten_thousands.value * 10000 + 1
          FROM digits ones
          CROSS JOIN digits tens
          CROSS JOIN digits hundreds
          CROSS JOIN digits thousands
          CROSS JOIN digits ten_thousands
          WHERE ones.value + tens.value * 10 + hundreds.value * 100 +
            thousands.value * 1000 + ten_thousands.value * 10000 < 10001
          """.trimIndent(),
        )
        transaction.execute(
          """
          INSERT INTO book (
            id, library_id, series_id, relative_uri, source_item_id, source_identity,
            name, media_kind, media_item_type, file_size, file_modified_ms,
            file_hash, file_hash_koreader, number, deleted_at_ms, oneshot,
            created_at_ms, updated_at_ms
          )
          SELECT
            printf('keep-%05d', number), 'library-1', 'series-a',
            printf('series-a/keep-%05d.cbz', number),
            printf('file:///synthetic/series-a/keep-%05d.cbz', number),
            NULL, printf('Synthetic keep item %05d', number),
            'COMIC_ARCHIVE', 'COMIC', 1, number, '', '', number, NULL, 0, number, number
          FROM synthetic_number
          """.trimIndent(),
        )
        transaction.execute(
          """
          INSERT INTO media (book_id, status, page_count, created_at_ms, updated_at_ms)
          SELECT printf('keep-%05d', number), 'READY', 1, number, number
          FROM synthetic_number
          """.trimIndent(),
        )
        transaction.execute(
          """
          INSERT INTO read_progress (
            book_id, user_id, page, completed, read_at_ms,
            created_at_ms, updated_at_ms
          )
          SELECT printf('keep-%05d', number), 'reader-1', 1, 0, number, number, number
          FROM synthetic_number
          """.trimIndent(),
        )
        transaction.execute(
          """
          INSERT INTO media (book_id, status, page_count, created_at_ms, updated_at_ms)
          VALUES ('book-1', 'ERROR', 1, 1, 1), ('book-2', 'READY', 1, 1, 1)
          """.trimIndent(),
        )
        transaction.execute(
          """
          INSERT INTO read_progress (
            book_id, user_id, page, completed, read_at_ms,
            created_at_ms, updated_at_ms
          ) VALUES
            ('book-1', 'reader-1', 1, 0, 20000, 1, 1),
            ('book-2', 'reader-1', 1, 1, 20001, 1, 1)
          """.trimIndent(),
        )
      }
      val catalog = JooqCatalogReadRepository(database)
      val access = CatalogAccess(userId = userId)

      val first =
        catalog.findBooks(
          BookCatalogQuery(keepReading = true),
          access,
          CatalogPageRequest(size = 2),
        )
      val last =
        catalog.findBooks(
          BookCatalogQuery(keepReading = true),
          access,
          CatalogPageRequest(page = 5_000, size = 2),
        )
      val anonymous =
        catalog.findBooks(
          BookCatalogQuery(keepReading = true),
          CatalogAccess(),
          CatalogPageRequest(size = 2),
        )

      assertEquals(10_001, first.totalElements)
      assertEquals(listOf("keep-10001", "keep-10000"), first.content.map { it.book.id.value })
      assertEquals(listOf("keep-00001"), last.content.map { it.book.id.value })
      assertEquals(0, anonymous.totalElements)
    }
  }

  @Test
  fun `indexes normalized metadata with safe unicode prefix queries and atomic refresh`() {
    withCatalog("full-text") { database ->
      val bookId = BookId("book-1")
      val seriesId = SeriesId("series-a")
      val books = JooqBookMetadataRepository(database)
      val series = JooqSeriesMetadataRepository(database)
      books.upsert(
        requireNotNull(books.findByBookIdOrNull(bookId)).copy(
          summary = "An atlas of synthetic constellations",
          authors = listOf(Author("Morgan Example", "cartographer")),
          tags = setOf("reference"),
          isbn = "9780000000002",
          updatedAtMillis = 2,
        ),
      )
      series.upsert(
        requireNotNull(series.findBySeriesIdOrNull(seriesId)).copy(
          summary = "A navigational archive",
          publisher = "Synthetic Press",
          genres = setOf("astronomy"),
          alternateTitles = listOf(AlternateTitle("short", "Star Atlas")),
          updatedAtMillis = 2,
        ),
      )
      val catalog = JooqCatalogReadRepository(database)

      assertEquals(
        listOf("book-1"),
        catalog
          .findBooks(
            BookCatalogQuery(fullTextSearch = "constell cartogr 978000"),
            CatalogAccess(),
            CatalogPageRequest(),
          ).content
          .map { it.book.id.value },
      )
      assertEquals(
        listOf("series-a"),
        catalog
          .findSeries(
            SeriesCatalogQuery(fullTextSearch = "star astronomy cartogr"),
            CatalogAccess(),
            CatalogPageRequest(),
          ).content
          .map { it.series.id.value },
      )
      assertEquals(
        0,
        catalog
          .findBooks(
            BookCatalogQuery(fullTextSearch = "\" ) *"),
            CatalogAccess(),
            CatalogPageRequest(),
          ).totalElements,
      )

      books.upsert(
        requireNotNull(books.findByBookIdOrNull(bookId)).copy(
          summary = "A revised lunar index",
          authors = emptyList(),
          tags = emptySet(),
          isbn = "",
          updatedAtMillis = 3,
        ),
      )

      assertEquals(
        0,
        catalog
          .findBooks(
            BookCatalogQuery(fullTextSearch = "constell"),
            CatalogAccess(),
            CatalogPageRequest(),
          ).totalElements,
      )
      assertEquals(
        1,
        catalog
          .findBooks(
            BookCatalogQuery(fullTextSearch = "lunar"),
            CatalogAccess(),
            CatalogPageRequest(),
          ).totalElements,
      )
    }
  }

  @Test
  fun `evaluates recursive structured conditions before stable paging`() {
    withCatalog("structured") { database ->
      val metadata = JooqSeriesMetadataRepository(database)
      val seriesId = SeriesId("series-a")
      metadata.upsert(
        requireNotNull(metadata.findBySeriesIdOrNull(seriesId)).copy(
          publisher = "Synthetic Press",
          totalBookCount = 3,
          updatedAtMillis = 2,
        ),
      )
      val catalog = JooqCatalogReadRepository(database)
      val books =
        catalog.findBooks(
          query =
            BookCatalogQuery(
              deleted = null,
              condition =
                CatalogSearchCondition.AllOf(
                  listOf(
                    predicate(
                      CatalogSearchField.SERIES_ID,
                      CatalogSearchOperator.IS,
                      "series-a",
                    ),
                    CatalogSearchCondition.AnyOf(
                      listOf(
                        predicate(
                          CatalogSearchField.TITLE,
                          CatalogSearchOperator.CONTAINS,
                          "absent",
                        ),
                        predicate(
                          CatalogSearchField.NUMBER_SORT,
                          CatalogSearchOperator.GREATER_THAN,
                          "2",
                        ),
                      ),
                    ),
                    predicate(
                      CatalogSearchField.DELETED,
                      CatalogSearchOperator.IS_FALSE,
                    ),
                  ),
                ),
            ),
          access = CatalogAccess(),
          page = CatalogPageRequest(),
        )
      val series =
        catalog.findSeries(
          query =
            SeriesCatalogQuery(
              deleted = null,
              condition =
                CatalogSearchCondition.AllOf(
                  listOf(
                    predicate(
                      CatalogSearchField.PUBLISHER,
                      CatalogSearchOperator.IS,
                      "synthetic press",
                    ),
                    predicate(
                      CatalogSearchField.COMPLETE,
                      CatalogSearchOperator.IS_TRUE,
                    ),
                    predicate(
                      CatalogSearchField.ONE_SHOT,
                      CatalogSearchOperator.IS_FALSE,
                    ),
                  ),
                ),
            ),
          access = CatalogAccess(),
          page = CatalogPageRequest(),
        )

      assertEquals(listOf("book-3"), books.content.map { it.book.id.value })
      assertEquals(listOf("series-a"), series.content.map { it.series.id.value })
    }
  }

  private fun predicate(
    field: CatalogSearchField,
    operator: CatalogSearchOperator,
    value: String? = null,
  ): CatalogSearchCondition.Predicate =
    CatalogSearchCondition.Predicate(field, operator, value)

  private fun withCatalog(
    name: String,
    block: (XoboroDatabase) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$name.sqlite"))).use { database ->
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
      listOf("a", "b").forEachIndexed { seriesIndex, suffix ->
        val seriesId = SeriesId("series-$suffix")
        seriesRepository.insert(
          Series(
            id = seriesId,
            libraryId = libraryId,
            name = "Synthetic series $suffix",
            relativePath = "series-$suffix",
            sourceItemId = "file:///synthetic/series-$suffix",
            fileModifiedAtMillis = seriesIndex.toLong() + 1,
            bookCount = if (suffix == "a") 3 else 1,
            createdAtMillis = 1,
          ),
        )
        val bookCount = if (suffix == "a") 3 else 1
        repeat(bookCount) { index ->
          val number = index + 1
          bookRepository.insert(
            Book(
              id = BookId(if (suffix == "a") "book-$number" else "book-b-$number"),
              libraryId = libraryId,
              seriesId = seriesId,
              name = "Synthetic chapter $number",
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
      block(database)
    }
  }
}
