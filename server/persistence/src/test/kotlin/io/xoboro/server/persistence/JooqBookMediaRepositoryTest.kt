package io.xoboro.server.persistence

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.Dimension
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaNavigationEntry
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqBookMediaRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `round trips complete media with 64-bit values`() {
    withRepository("round-trip") { repository, _ ->
      val expected = mediaFixture()

      repository.upsert(expected)

      assertEquals(expected, repository.findByBookIdOrNull(BOOK_ID))
    }
  }

  @Test
  fun `upsert atomically replaces pages and files`() {
    withRepository("replace") { repository, database ->
      repository.upsert(mediaFixture())
      val updated =
        mediaFixture().copy(
          pages =
            listOf(
              io.xoboro.core.domain.BookPage(
                number = 1,
                fileName = "cover.webp",
                mediaType = "image/webp",
                fileSize = 6_000_000_000L,
              ),
            ),
          files = emptyList(),
          comment = "updated",
          updatedAtMillis = 1_700_000_002_000L,
        )

      repository.upsert(updated)

      assertEquals(updated, repository.findByBookIdOrNull(BOOK_ID))
      assertEquals(1, database.dsl.fetchCount(org.jooq.impl.DSL.table("book_page")))
      assertEquals(0, database.dsl.fetchCount(org.jooq.impl.DSL.table("media_file")))
    }
  }

  @Test
  fun `batch hydration preserves each media subtree`() {
    withRepository("batch") { repository, database ->
      val secondId = BookId("book-2")
      JooqBookRepository(database).insert(
        Book(
          id = secondId,
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Second synthetic book",
          relativePath = "series/book-2.cbz",
          sourceItemId = "file:///synthetic/series/book-2.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 2,
          createdAtMillis = 2,
        ),
      )
      val first = mediaFixture()
      val second = mediaFixture().copy(bookId = secondId)
      repository.upsert(first)
      repository.upsert(second)

      assertEquals(
        mapOf(BOOK_ID to first, secondId to second),
        repository
          .findAllByBookIds(listOf(secondId, BookId("missing"), BOOK_ID))
          .associateBy(BookMedia::bookId),
      )
    }
  }

  @Test
  fun `book deletion cascades through media subtree`() {
    withRepository("cascade") { repository, database ->
      repository.upsert(mediaFixture())

      JooqBookRepository(database).delete(BOOK_ID)

      assertNull(repository.findByBookIdOrNull(BOOK_ID))
      assertEquals(0, database.dsl.fetchCount(org.jooq.impl.DSL.table("book_page")))
      assertEquals(0, database.dsl.fetchCount(org.jooq.impl.DSL.table("media_file")))
      assertEquals(0, database.dsl.fetchCount(org.jooq.impl.DSL.table("media_position")))
      assertEquals(
        0,
        database.dsl.fetchCount(org.jooq.impl.DSL.table("media_navigation_entry")),
      )
    }
  }

  private fun withRepository(
    name: String,
    block: (JooqBookMediaRepository, XoboroDatabase) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$name.sqlite"))).use { database ->
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
          createdAtMillis = 1,
        ),
      )
      block(JooqBookMediaRepository(database), database)
    }
  }

  private fun mediaFixture(): BookMedia =
    BookMedia(
      bookId = BOOK_ID,
      status = MediaStatus.READY,
      mediaType = "application/zip",
      profile = MediaProfile.DIVINA,
      pages =
        listOf(
          io.xoboro.core.domain.BookPage(
            number = 1,
            fileName = "001.jpg",
            mediaType = "image/jpeg",
            fileSize = 5_000_000_000L,
            dimension = Dimension(1200, 1800),
            fileHash = "page-hash",
          ),
        ),
      files =
        listOf(
          MediaFile(
            fileName = "metadata/10.xml",
            mediaType = "application/xml",
            fileSize = 4_000_000_000L,
            kind = MediaFileKind.EPUB_PAGE,
          ),
          MediaFile(
            fileName = "ComicInfo.xml",
            mediaType = "application/xml",
            fileSize = 20L,
          ),
        ),
      epubDivinaCompatible = true,
      epubIsKepub = true,
      epubIsFixedLayout = true,
      toc =
        listOf(
          MediaNavigationEntry(
            title = "Synthetic contents",
            href = "chapter.xhtml",
            children =
              listOf(
                MediaNavigationEntry(
                  title = "Synthetic child",
                  href = "chapter.xhtml#child",
                ),
              ),
          ),
        ),
      landmarks =
        listOf(MediaNavigationEntry(title = "Start", href = "chapter.xhtml")),
      pageList =
        listOf(MediaNavigationEntry(title = "Page one", href = "chapter.xhtml")),
      // Two positions rather than one. Under the Readium convention the first position's
      // `totalProgression` is 0 for every publication, so a single-position fixture would
      // round-trip a column that a read returning a constant zero could satisfy. The second
      // position gives the column a value only storage can supply.
      positions =
        listOf(
          MediaPosition(
            href = "chapter.xhtml",
            mediaType = "application/xhtml+xml",
            progression = 0F,
            position = 1,
            totalProgression = 0F,
            koboSpan = "kobo.1.1",
          ),
          MediaPosition(
            href = "chapter-2.xhtml",
            mediaType = "application/xhtml+xml",
            progression = 0F,
            position = 2,
            totalProgression = 0.5F,
            koboSpan = "kobo.2.1",
          ),
        ),
      createdAtMillis = 1_700_000_000_000L,
      updatedAtMillis = 1_700_000_001_000L,
    )

  companion object {
    private val LIBRARY_ID = LibraryId("library-1")
    private val SERIES_ID = SeriesId("series-1")
    private val BOOK_ID = BookId("book-1")
  }
}
