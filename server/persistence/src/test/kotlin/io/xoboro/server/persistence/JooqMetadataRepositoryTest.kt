package io.xoboro.server.persistence

import io.xoboro.core.domain.AlternateTitle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.WebLink
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class JooqMetadataRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `catalog inserts create usable default metadata`() {
    withCatalog("defaults") { database ->
      val bookMetadata = assertNotNull(JooqBookMetadataRepository(database).findByBookIdOrNull(BOOK_ID))
      assertEquals("Synthetic book", bookMetadata.title)
      assertEquals("7", bookMetadata.number)
      assertEquals(7F, bookMetadata.numberSort)

      val seriesMetadata =
        assertNotNull(
          JooqSeriesMetadataRepository(database).findBySeriesIdOrNull(SERIES_ID),
        )
      assertEquals("Synthetic series", seriesMetadata.title)
      assertEquals("Synthetic series", seriesMetadata.titleSort)
      assertEquals(SeriesStatus.ONGOING, seriesMetadata.status)
    }
  }

  @Test
  fun `round trips every book and series metadata field and relation`() {
    withCatalog("round-trip") { database ->
      val books = JooqBookMetadataRepository(database)
      val series = JooqSeriesMetadataRepository(database)
      val bookMetadata =
        BookMetadata(
          bookId = BOOK_ID,
          title = "  Synthetic title  ",
          summary = "  Synthetic summary  ",
          number = "  7.5  ",
          numberSort = 7.5F,
          releaseDate = "2026-07-27",
          authors =
            listOf(
              Author(" Synthetic Writer ", " Writer "),
              Author("Synthetic Artist", "Penciller"),
            ),
          tags = setOf(" Action ", "COLLECTION"),
          isbn = "synthetic-isbn",
          links = listOf(WebLink("Reference", "https://example.invalid/book")),
          titleLock = true,
          summaryLock = true,
          numberLock = true,
          numberSortLock = true,
          releaseDateLock = true,
          authorsLock = true,
          tagsLock = true,
          isbnLock = true,
          linksLock = true,
          createdAtMillis = 10,
          updatedAtMillis = 20,
        )
      val seriesMetadata =
        SeriesMetadata(
          seriesId = SERIES_ID,
          status = SeriesStatus.HIATUS,
          title = "  Synthetic series title  ",
          titleSort = "  Series title synthetic  ",
          summary = "  Series summary  ",
          readingDirection = ReadingDirection.WEBTOON,
          publisher = "  Synthetic publisher  ",
          ageRating = 15,
          language = "  en-US  ",
          genres = setOf(" Action ", "Drama"),
          tags = setOf(" Tag A ", "TAG B"),
          totalBookCount = 12,
          sharingLabels = setOf(" Family "),
          links = listOf(WebLink("Reference", "https://example.invalid/series")),
          alternateTitles = listOf(AlternateTitle("Short", "Synthetic alternate")),
          statusLock = true,
          titleLock = true,
          titleSortLock = true,
          summaryLock = true,
          readingDirectionLock = true,
          publisherLock = true,
          ageRatingLock = true,
          languageLock = true,
          genresLock = true,
          tagsLock = true,
          totalBookCountLock = true,
          sharingLabelsLock = true,
          linksLock = true,
          alternateTitlesLock = true,
          createdAtMillis = 10,
          updatedAtMillis = 20,
        )

      books.upsert(bookMetadata)
      series.upsert(seriesMetadata)

      val storedBook = assertNotNull(books.findByBookIdOrNull(BOOK_ID))
      assertEquals("Synthetic title", storedBook.title)
      assertEquals("Synthetic summary", storedBook.summary)
      assertEquals("7.5", storedBook.number)
      assertEquals(setOf("action", "collection"), storedBook.tags)
      assertEquals(
        listOf(
          Author("Synthetic Writer", "writer"),
          Author("Synthetic Artist", "penciller"),
        ).map { it.normalizedName to it.normalizedRole },
        storedBook.authors.map { it.normalizedName to it.normalizedRole },
      )
      assertEquals(bookMetadata.copy(title = "Synthetic title", summary = "Synthetic summary", number = "7.5", tags = setOf("action", "collection"), authors = storedBook.authors), storedBook)

      val storedSeries = assertNotNull(series.findBySeriesIdOrNull(SERIES_ID))
      assertEquals("Synthetic series title", storedSeries.title)
      assertEquals("Series title synthetic", storedSeries.titleSort)
      assertEquals("Series summary", storedSeries.summary)
      assertEquals("Synthetic publisher", storedSeries.publisher)
      assertEquals("en-US", storedSeries.language)
      assertEquals(setOf("action", "drama"), storedSeries.genres)
      assertEquals(setOf("tag a", "tag b"), storedSeries.tags)
      assertEquals(setOf("family"), storedSeries.sharingLabels)
      assertEquals(seriesMetadata.links, storedSeries.links)
      assertEquals(seriesMetadata.alternateTitles, storedSeries.alternateTitles)
      assertEquals(true, storedSeries.alternateTitlesLock)
    }
  }

  private fun withCatalog(
    databaseName: String,
    block: (XoboroDatabase) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$databaseName.sqlite"))).use {
        database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "series",
          sourceItemId = "file:///synthetic/series",
          fileModifiedAtMillis = 1,
          bookCount = 1,
          createdAtMillis = 1,
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
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          number = 7,
          createdAtMillis = 1,
        ),
      )
      block(database)
    }
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
  }
}
