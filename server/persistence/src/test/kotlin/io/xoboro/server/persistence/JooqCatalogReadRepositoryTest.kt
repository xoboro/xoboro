package io.xoboro.server.persistence

import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.domain.AgeRestriction
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
