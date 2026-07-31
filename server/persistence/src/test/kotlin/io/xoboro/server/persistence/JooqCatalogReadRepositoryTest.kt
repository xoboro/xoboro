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
import io.xoboro.core.application.SeriesRegexSearch
import io.xoboro.core.application.SeriesRegexSearchField
import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.AlternateTitle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesCollection
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
  fun `supports Komga book and series sort aliases with user progress`() {
    withCatalog("sort-aliases") { database ->
      val userId = UserId("sort-reader")
      JooqUserRepository(database).insert(
        User(
          id = userId,
          email = "sort-reader@example.invalid",
          passwordHash = "synthetic-password-hash",
          createdAtMillis = 1,
        ),
      )
      database.transaction { transaction ->
        listOf(
          Triple("book-1", "ERROR", 1),
          Triple("book-2", "READY", 9),
          Triple("book-3", "UNKNOWN", 4),
        ).forEach { (bookId, status, pages) ->
          transaction.execute(
            """
            INSERT INTO media (
              book_id, status, media_type, profile, page_count, comment,
              created_at_ms, updated_at_ms
            ) VALUES (?, ?, 'application/zip', 'DIVINA', ?, ?, 1, 1)
            """.trimIndent(),
            bookId,
            status,
            pages,
            "Synthetic $status",
          )
        }
      }
      val progresses = JooqReadProgressRepository(database)
      progresses.upsertAll(
        listOf(
          ReadProgress(
            bookId = BookId("book-1"),
            userId = userId,
            page = 1,
            completed = false,
            readAtMillis = 30,
            updatedAtMillis = 40,
          ),
          ReadProgress(
            bookId = BookId("book-2"),
            userId = userId,
            page = 1,
            completed = false,
            readAtMillis = 20,
            updatedAtMillis = 50,
          ),
          ReadProgress(
            bookId = BookId("book-b-1"),
            userId = userId,
            page = 1,
            completed = false,
            readAtMillis = 100,
          ),
        ),
      )
      val metadata = JooqBookMetadataRepository(database)
      listOf(
        "book-1" to "2020-01-01",
        "book-2" to "2022-01-01",
        "book-3" to "2021-01-01",
        "book-b-1" to "2018-01-01",
      ).forEachIndexed { index, (bookId, date) ->
        metadata.upsert(
          requireNotNull(metadata.findByBookIdOrNull(BookId(bookId))).copy(
            releaseDate = date,
            updatedAtMillis = 101 + index.toLong(),
          ),
        )
      }
      JooqReadListRepository(database).insert(
        ReadList(
          id = ReadListId("sort-read-list"),
          name = "Synthetic sort order",
          bookIds = listOf(BookId("book-3"), BookId("book-1"), BookId("book-2")),
          createdAtMillis = 1,
        ),
      )
      JooqReadListRepository(database).insert(
        ReadList(
          id = ReadListId("other-sort-read-list"),
          name = "Conflicting synthetic sort order",
          bookIds = listOf(BookId("book-2"), BookId("book-1"), BookId("book-3")),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesCollectionRepository(database).insert(
        SeriesCollection(
          id = CollectionId("sort-collection"),
          name = "Synthetic sort collection",
          ordered = true,
          seriesIds = listOf(SeriesId("series-b"), SeriesId("series-a")),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesCollectionRepository(database).insert(
        SeriesCollection(
          id = CollectionId("other-sort-collection"),
          name = "Conflicting synthetic sort collection",
          ordered = true,
          seriesIds = listOf(SeriesId("series-a"), SeriesId("series-b")),
          createdAtMillis = 1,
        ),
      )
      val catalog = JooqCatalogReadRepository(database)
      val access = CatalogAccess(userId = userId)

      fun sortedBooks(
        property: String,
        direction: CatalogSortDirection,
      ): List<String> =
        catalog
          .findBooks(
            query =
              BookCatalogQuery(
                seriesId = SeriesId("series-a"),
                condition =
                  CatalogSearchCondition.Predicate(
                    CatalogSearchField.READ_LIST_ID,
                    CatalogSearchOperator.IS,
                    "sort-read-list",
                  ),
              ),
            access = access,
            page = CatalogPageRequest(sorts = listOf(CatalogSort(property, direction))),
          ).content
          .map { it.book.id.value }

      assertEquals(
        listOf("book-2", "book-3", "book-1"),
        sortedBooks("media.pagesCount", CatalogSortDirection.DESC),
      )
      assertEquals(
        listOf("book-2", "book-3", "book-1"),
        sortedBooks("metadata.releaseDate", CatalogSortDirection.DESC),
      )
      assertEquals(
        listOf("book-1", "book-2", "book-3"),
        sortedBooks("readProgress.readDate", CatalogSortDirection.DESC),
      )
      assertEquals(
        listOf("book-2", "book-1", "book-3"),
        sortedBooks("readProgress.lastModified", CatalogSortDirection.DESC),
      )
      assertEquals(
        listOf("book-3", "book-1", "book-2"),
        sortedBooks("readList.number", CatalogSortDirection.ASC),
      )
      listOf(
        "createdDate",
        "lastModifiedDate",
        "fileSize",
        "size",
        "url",
        "media.status",
        "media.comment",
        "media.mediaType",
        "metadata.title",
        "metadata.numberSort",
        "series",
      ).forEach { property ->
        assertEquals(3, sortedBooks(property, CatalogSortDirection.ASC).size)
      }

      fun sortedSeries(
        property: String,
        direction: CatalogSortDirection,
      ): List<String> =
        catalog
          .findSeries(
            query =
              SeriesCatalogQuery(
                condition =
                  CatalogSearchCondition.Predicate(
                    CatalogSearchField.COLLECTION_ID,
                    CatalogSearchOperator.IS,
                    "sort-collection",
                  ),
              ),
            access = access,
            page = CatalogPageRequest(sorts = listOf(CatalogSort(property, direction))),
          ).content
          .map { it.series.id.value }

      assertEquals(
        listOf("series-b", "series-a"),
        sortedSeries("readDate", CatalogSortDirection.DESC),
      )
      assertEquals(
        listOf("series-a", "series-b"),
        sortedSeries("booksMetadata.releaseDate", CatalogSortDirection.DESC),
      )
      assertEquals(
        listOf("series-b", "series-a"),
        sortedSeries("collection.number", CatalogSortDirection.ASC),
      )
      assertEquals(2, sortedSeries("random", CatalogSortDirection.ASC).size)
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
      assertEquals(listOf("a" to 1, "b" to 1), groups.map { it.group to it.count })
    }
  }

  @Test
  fun `filters and pages series by case insensitive title regular expressions`() {
    withCatalog("regular-expression") { database ->
      val metadata = JooqSeriesMetadataRepository(database)
      metadata.upsert(
        requireNotNull(metadata.findBySeriesIdOrNull(SeriesId("series-a"))).copy(
          title = "TheAlpha",
          titleSort = "Alpha, The",
          updatedAtMillis = 2,
        ),
      )
      metadata.upsert(
        requireNotNull(metadata.findBySeriesIdOrNull(SeriesId("series-b"))).copy(
          title = "TheBeta",
          titleSort = "TheBeta",
          updatedAtMillis = 2,
        ),
      )
      val catalog = JooqCatalogReadRepository(database)

      val secondPage =
        catalog.findSeries(
          query =
            SeriesCatalogQuery(
              regexSearch =
                SeriesRegexSearch(
                  pattern = "^the",
                  field = SeriesRegexSearchField.TITLE,
                ),
            ),
          access = CatalogAccess(),
          page = CatalogPageRequest(page = 1, size = 1),
        )
      val titleSortMatch =
        catalog.findSeries(
          query =
            SeriesCatalogQuery(
              regexSearch =
                SeriesRegexSearch(
                  pattern = "a$",
                  field = SeriesRegexSearchField.TITLE_SORT,
                ),
            ),
          access = CatalogAccess(),
          page = CatalogPageRequest(),
        )

      assertEquals(2, secondPage.totalElements)
      assertEquals(listOf("series-b"), secondPage.content.map { it.series.id.value })
      assertEquals(listOf("series-b"), titleSortMatch.content.map { it.series.id.value })
    }
  }

  @Test
  fun `aggregates the first nonblank summary earliest release authors and tags`() {
    withCatalog("aggregation") { database ->
      val metadata = JooqBookMetadataRepository(database)
      metadata.upsert(
        requireNotNull(metadata.findByBookIdOrNull(BookId("book-1"))).copy(
          summary = "",
          releaseDate = "2022-03-01",
          authors = listOf(Author("Shared Author", "writer")),
          tags = setOf("first"),
          updatedAtMillis = 10,
        ),
      )
      metadata.upsert(
        requireNotNull(metadata.findByBookIdOrNull(BookId("book-2"))).copy(
          summary = "First nonblank synthetic summary",
          releaseDate = "2020-02-01",
          authors =
            listOf(
              Author("Shared Author", "writer"),
              Author("Second Author", "artist"),
            ),
          tags = setOf("second"),
          updatedAtMillis = 11,
        ),
      )
      metadata.upsert(
        requireNotNull(metadata.findByBookIdOrNull(BookId("book-3"))).copy(
          summary = "Later synthetic summary",
          releaseDate = "2021-01-01",
          authors = listOf(Author("Third Author", "writer")),
          tags = setOf("first", "third"),
          updatedAtMillis = 12,
        ),
      )

      val result =
        requireNotNull(
          JooqCatalogReadRepository(database)
            .findSeriesByIdOrNull(SeriesId("series-a"), CatalogAccess()),
        ).booksMetadata

      assertEquals("First nonblank synthetic summary", result.summary)
      assertEquals("2", result.summaryNumber)
      assertEquals("2020-02-01", result.releaseDate)
      assertEquals(
        listOf(
          Author("Shared Author", "writer"),
          Author("Second Author", "artist"),
          Author("Third Author", "writer"),
        ),
        result.authors,
      )
      assertEquals(setOf("first", "second", "third"), result.tags)
      assertEquals(1, result.createdAtMillis)
      assertEquals(12, result.updatedAtMillis)
      assertEquals(
        0,
        database.dsl.fetchValue(
          """
          SELECT count(*)
          FROM series_book_metadata_aggregation_dirty
          WHERE series_id = 'series-a'
          """.trimIndent(),
          Int::class.java,
        ),
      )
      assertEquals(
        "First nonblank synthetic summary",
        database.dsl.fetchValue(
          """
          SELECT summary
          FROM series_book_metadata_aggregation
          WHERE series_id = 'series-a'
          """.trimIndent(),
          String::class.java,
        ),
      )

      val matching =
        JooqCatalogReadRepository(database).findSeries(
          query =
            SeriesCatalogQuery(
              condition =
                CatalogSearchCondition.AllOf(
                  listOf(
                    predicate(
                      CatalogSearchField.RELEASE_DATE,
                      CatalogSearchOperator.BEFORE,
                      "2020-06-01T00:00:00Z",
                    ),
                    predicate(
                      CatalogSearchField.TAG,
                      CatalogSearchOperator.IS,
                      "third",
                    ),
                    CatalogSearchCondition.Predicate(
                      field = CatalogSearchField.AUTHOR,
                      operator = CatalogSearchOperator.IS,
                      attributes = mapOf("name" to "Third Author", "role" to "writer"),
                    ),
                  ),
                ),
            ),
          access = CatalogAccess(),
          page = CatalogPageRequest(),
        )
      assertEquals(listOf("series-a"), matching.content.map { it.series.id.value })

      metadata.upsert(
        requireNotNull(metadata.findByBookIdOrNull(BookId("book-2"))).copy(
          summary = "Refreshed synthetic summary",
          releaseDate = "2019-01-01",
          updatedAtMillis = 13,
        ),
      )
      assertEquals(
        1,
        database.dsl.fetchValue(
          """
          SELECT count(*)
          FROM series_book_metadata_aggregation_dirty
          WHERE series_id = 'series-a'
          """.trimIndent(),
          Int::class.java,
        ),
      )
      val refreshed =
        requireNotNull(
          JooqCatalogReadRepository(database)
            .findSeriesByIdOrNull(SeriesId("series-a"), CatalogAccess()),
        ).booksMetadata
      assertEquals("Refreshed synthetic summary", refreshed.summary)
      assertEquals("2019-01-01", refreshed.releaseDate)
      assertEquals(
        0,
        database.dsl.fetchValue(
          "SELECT count(*) FROM series_book_metadata_aggregation_dirty",
          Int::class.java,
        ),
      )

      val books = JooqBookRepository(database)
      books.update(
        requireNotNull(books.findByIdOrNull(BookId("book-2"))).copy(
          seriesId = SeriesId("series-b"),
          relativePath = "series-b/moved-chapter.cbz",
          updatedAtMillis = 14,
        ),
      )
      assertEquals(
        2,
        database.dsl.fetchValue(
          "SELECT count(*) FROM series_book_metadata_aggregation_dirty",
          Int::class.java,
        ),
      )
      val catalog = JooqCatalogReadRepository(database)
      val oldParent =
        requireNotNull(catalog.findSeriesByIdOrNull(SeriesId("series-a"), CatalogAccess()))
      val newParent =
        requireNotNull(catalog.findSeriesByIdOrNull(SeriesId("series-b"), CatalogAccess()))
      assertEquals("Later synthetic summary", oldParent.booksMetadata.summary)
      assertEquals("Refreshed synthetic summary", newParent.booksMetadata.summary)
      assertEquals(
        0,
        database.dsl.fetchValue(
          "SELECT count(*) FROM series_book_metadata_aggregation_dirty",
          Int::class.java,
        ),
      )
    }
  }

  @Test
  fun `preserves 64 bit epoch milliseconds while rebuilding series aggregation`() {
    withCatalog("aggregation-64-bit") { database ->
      val metadata = JooqBookMetadataRepository(database)
      val firstTimestamp = 1_785_171_313_787L
      listOf("book-1", "book-2", "book-3").forEachIndexed { index, id ->
        val timestamp = firstTimestamp + index
        metadata.upsert(
          requireNotNull(metadata.findByBookIdOrNull(BookId(id))).copy(
            createdAtMillis = timestamp,
            updatedAtMillis = timestamp,
          ),
        )
      }

      val aggregate =
        requireNotNull(
          JooqCatalogReadRepository(database)
            .findSeriesByIdOrNull(SeriesId("series-a"), CatalogAccess()),
        ).booksMetadata

      assertEquals(firstTimestamp, aggregate.createdAtMillis)
      assertEquals(firstTimestamp + 2, aggregate.updatedAtMillis)
    }
  }

  @Test
  fun `repairs a dirty persisted aggregation after restart`() {
    val path = tempDirectory.resolve("aggregation-restart.sqlite")
    val seriesId = SeriesId("restart-series")
    val bookId = BookId("restart-book")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val libraryId = LibraryId("restart-library")
      JooqLibraryRepository(database).insert(
        Library(
          id = libraryId,
          name = "Synthetic restart library",
          root = SourceLocation("local", "file:///synthetic/restart"),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = seriesId,
          libraryId = libraryId,
          name = "Synthetic restart series",
          relativePath = "restart-series",
          sourceItemId = "file:///synthetic/restart/series",
          fileModifiedAtMillis = 1,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      )
      JooqBookRepository(database).insert(
        Book(
          id = bookId,
          libraryId = libraryId,
          seriesId = seriesId,
          name = "Synthetic restart book",
          relativePath = "restart-series/book.cbz",
          sourceItemId = "file:///synthetic/restart/series/book.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      val metadata = JooqBookMetadataRepository(database)
      metadata.upsert(
        requireNotNull(metadata.findByBookIdOrNull(bookId)).copy(
          summary = "Initial persisted summary",
          updatedAtMillis = 2,
        ),
      )
      JooqCatalogReadRepository(database).findSeriesByIdOrNull(seriesId, CatalogAccess())
      metadata.upsert(
        requireNotNull(metadata.findByBookIdOrNull(bookId)).copy(
          summary = "Restart repaired summary",
          updatedAtMillis = 3,
        ),
      )
      assertEquals(
        1,
        database.dsl.fetchValue(
          "SELECT count(*) FROM series_book_metadata_aggregation_dirty",
          Int::class.java,
        ),
      )
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repaired =
        requireNotNull(
          JooqCatalogReadRepository(database)
            .findSeriesByIdOrNull(seriesId, CatalogAccess()),
        )
      assertEquals("Restart repaired summary", repaired.booksMetadata.summary)
      assertEquals(
        0,
        database.dsl.fetchValue(
          "SELECT count(*) FROM series_book_metadata_aggregation_dirty",
          Int::class.java,
        ),
      )
    }
  }

  @Test
  fun `hydrates series across bounded query batches`() {
    withCatalog("series-batches") { database ->
      val libraryId = LibraryId("library-1")
      JooqSeriesRepository(database).insertAll(
        (1..501).map { number ->
          Series(
            id = SeriesId("batch-series-$number"),
            libraryId = libraryId,
            name = "Synthetic batch series $number",
            relativePath = "batch-series-$number",
            sourceItemId = "file:///synthetic/batch-series-$number",
            fileModifiedAtMillis = number.toLong(),
            createdAtMillis = number.toLong(),
          )
        },
      )

      val result =
        JooqCatalogReadRepository(database).findSeries(
          query = SeriesCatalogQuery(deleted = false),
          access = CatalogAccess(),
          page = CatalogPageRequest(unpaged = true),
        )

      assertEquals(503, result.totalElements)
      assertEquals(503, result.content.size)
      val empty = requireNotNull(result.content.firstOrNull { it.series.id.value == "batch-series-1" })
      assertEquals("", empty.booksMetadata.summary)
      assertEquals(1, empty.booksMetadata.createdAtMillis)
    }
  }

  @Test
  fun `hydrates books across bounded query batches`() {
    withCatalog("book-batches") { database ->
      val libraryId = LibraryId("library-1")
      val seriesId = SeriesId("series-a")
      JooqBookRepository(database).insertAll(
        (1..501).map { number ->
          Book(
            id = BookId("batch-book-$number"),
            libraryId = libraryId,
            seriesId = seriesId,
            name = "Synthetic batch book $number",
            relativePath = "series-a/batch-book-$number.cbz",
            sourceItemId = "file:///synthetic/series-a/batch-book-$number.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = number.toLong(),
            number = number + 10,
            createdAtMillis = number.toLong(),
          )
        },
      )

      val result =
        JooqCatalogReadRepository(database).findBooks(
          query = BookCatalogQuery(seriesId = seriesId, deleted = false),
          access = CatalogAccess(),
          page = CatalogPageRequest(unpaged = true),
        )

      assertEquals(504, result.totalElements)
      assertEquals(504, result.content.size)
      assertEquals(
        "Synthetic series a",
        result.content.first { it.book.id.value == "batch-book-1" }.seriesTitle,
      )
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
  fun `orders on deck by when the reader last read anything in that series`() {
    withCatalog("on-deck-order") { database ->
      val userId = UserId("on-deck-reader")
      JooqUserRepository(database).insert(
        User(
          id = userId,
          email = "on-deck-reader@example.invalid",
          passwordHash = "synthetic-password-hash",
          createdAtMillis = 1,
        ),
      )
      val libraryId = LibraryId("library-1")
      val seriesRepository = JooqSeriesRepository(database)
      val bookRepository = JooqBookRepository(database)

      // Three series, each with one already-read book and one unread candidate - the one on-deck
      // can return. Recency, id, and the candidate's own number/title are all arranged to
      // disagree with one another, the same discrimination the discovery-feed test applies to
      // `new`: the expected answer here is not what id order, number order, or title order (in
      // either direction) would also produce.
      //
      // That matters because the candidate book is unread by definition, so its own
      // `readProgress.readDate` is null for every row on-deck returns. Before the fix, ordering
      // by that always-null column collapsed to the SQL tie-breaker, `b.id ASC`. A fixture whose
      // ids already happened to sort into the expected order would pass whether or not the
      // series-recency join was there; this one only passes with it.
      data class SeriesFixture(
        val id: String,
        val candidateId: String,
        val candidateTitle: String,
        val candidateNumber: Int,
        val lastReadAtMillis: Long,
      )
      val fixtures =
        listOf(
          SeriesFixture(
            id = "on-deck-alpha",
            candidateId = "on-deck-alpha-2",
            candidateTitle = "Synthetic mango chapter",
            candidateNumber = 20,
            lastReadAtMillis = 100,
          ),
          SeriesFixture(
            id = "on-deck-beta",
            candidateId = "on-deck-beta-2",
            candidateTitle = "Synthetic cherry chapter",
            candidateNumber = 30,
            lastReadAtMillis = 300,
          ),
          SeriesFixture(
            id = "on-deck-gamma",
            candidateId = "on-deck-gamma-2",
            candidateTitle = "Synthetic apple chapter",
            candidateNumber = 10,
            lastReadAtMillis = 200,
          ),
        )

      fixtures.forEach { fixture ->
        val seriesId = SeriesId(fixture.id)
        seriesRepository.insert(
          Series(
            id = seriesId,
            libraryId = libraryId,
            name = "Synthetic ${fixture.id}",
            relativePath = fixture.id,
            sourceItemId = "file:///synthetic/${fixture.id}",
            fileModifiedAtMillis = 1,
            bookCount = 2,
            createdAtMillis = 1,
          ),
        )
        bookRepository.insert(
          Book(
            id = BookId("${fixture.id}-1"),
            libraryId = libraryId,
            seriesId = seriesId,
            name = "Synthetic ${fixture.id} chapter 1",
            relativePath = "${fixture.id}/${fixture.id}-1.cbz",
            sourceItemId = "file:///synthetic/${fixture.id}-1.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 1,
            number = 1,
            createdAtMillis = 1,
          ),
        )
        bookRepository.insert(
          Book(
            id = BookId(fixture.candidateId),
            libraryId = libraryId,
            seriesId = seriesId,
            name = fixture.candidateTitle,
            relativePath = "${fixture.id}/${fixture.candidateId}.cbz",
            sourceItemId = "file:///synthetic/${fixture.candidateId}.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 1,
            number = fixture.candidateNumber,
            createdAtMillis = 1,
          ),
        )
      }

      // Marking the first book of each series completed is what makes that series eligible for
      // on-deck (books_read_count > 0, books_in_progress_count = 0) and it is what seeds
      // read_progress_series.last_read_at_ms, via the repository's own recomputation.
      JooqReadProgressRepository(database).upsertAll(
        fixtures.map { fixture ->
          ReadProgress(
            bookId = BookId("${fixture.id}-1"),
            userId = userId,
            page = 1,
            completed = true,
            readAtMillis = fixture.lastReadAtMillis,
          )
        },
      )

      val catalog = JooqCatalogReadRepository(database)
      val onDeck =
        catalog.findBooks(
          query = BookCatalogQuery(onDeck = true),
          access = CatalogAccess(userId = userId),
          page =
            CatalogPageRequest(
              sorts =
                listOf(CatalogSort("readProgress.seriesReadDate", CatalogSortDirection.DESC)),
            ),
        )
      val anonymous =
        catalog.findBooks(
          BookCatalogQuery(onDeck = true),
          CatalogAccess(),
          CatalogPageRequest(),
        )

      assertEquals(3, onDeck.totalElements)
      assertEquals(
        listOf("on-deck-beta-2", "on-deck-gamma-2", "on-deck-alpha-2"),
        onDeck.content.map { it.book.id.value },
      )
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
