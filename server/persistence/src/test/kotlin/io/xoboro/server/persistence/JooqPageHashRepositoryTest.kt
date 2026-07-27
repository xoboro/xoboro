package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqPageHashRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `groups unknown hashes pages matches and known actions in SQL`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("page-hashes.sqlite"))).use {
        database ->
      seed(database)
      val repository = JooqPageHashRepository(database)

      val unknown = repository.findUnknown(CatalogPageRequest(size = 1))
      assertEquals(1, unknown.totalElements)
      assertEquals("shared-hash", unknown.content.single().hash)
      assertEquals(2, unknown.content.single().matchCount)

      val matches =
        repository.findMatches(
          "shared-hash",
          CatalogPageRequest(
            size = 20,
            sorts = listOf(CatalogSort("bookId", CatalogSortDirection.DESC)),
          ),
        )
      assertEquals(listOf("book-2", "book-1"), matches.content.map { it.mediaItemId.value })
      assertEquals(listOf(1, 1), matches.content.map { it.pageNumber })

      repository.upsert(
        KnownPageHash(
          hash = "shared-hash",
          size = 12,
          action = PageHashAction.DELETE_MANUAL,
          createdAtMillis = 10,
        ),
      )

      assertEquals(0, repository.findUnknown(CatalogPageRequest()).totalElements)
      val known =
        repository
          .findKnown(setOf(PageHashAction.DELETE_MANUAL), CatalogPageRequest())
          .content
          .single()
      assertEquals(2, known.matchCount)
      assertEquals(12, known.size)
      assertEquals(PageHashAction.DELETE_MANUAL, known.action)
      assertNull(
        repository
          .findKnown(setOf(PageHashAction.IGNORE), CatalogPageRequest())
          .content
          .singleOrNull(),
      )

      repository.upsert(known.copy(action = PageHashAction.IGNORE, updatedAtMillis = 20))
      assertEquals(PageHashAction.IGNORE, repository.findKnownOrNull("shared-hash")?.action)
      assertEquals(10, repository.findKnownOrNull("shared-hash")?.createdAtMillis)
      assertEquals(20, repository.findKnownOrNull("shared-hash")?.updatedAtMillis)
    }
  }

  private fun seed(database: XoboroDatabase) {
    val libraries = JooqLibraryRepository(database)
    val series = JooqSeriesRepository(database)
    val books = JooqBookRepository(database)
    val media = JooqBookMediaRepository(database)
    libraries.insert(
      Library(
        id = LibraryId("library-1"),
        name = "Synthetic library",
        root = SourceLocation("local", "file:///synthetic"),
        createdAtMillis = 1,
      ),
    )
    series.insert(
      Series(
        id = SeriesId("series-1"),
        libraryId = LibraryId("library-1"),
        name = "Synthetic series",
        relativePath = "series",
        sourceItemId = "file:///synthetic/series",
        fileModifiedAtMillis = 1,
        createdAtMillis = 1,
      ),
    )
    (1..2).forEach { number ->
      val id = BookId("book-$number")
      books.insert(
        Book(
          id = id,
          libraryId = LibraryId("library-1"),
          seriesId = SeriesId("series-1"),
          name = "Synthetic book $number",
          relativePath = "series/book-$number.cbz",
          sourceItemId = "file:///synthetic/series/book-$number.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      media.upsert(
        BookMedia(
          bookId = id,
          status = MediaStatus.READY,
          mediaType = "application/zip",
          profile = MediaProfile.DIVINA,
          pages =
            listOf(
              BookPage(
                number = 1,
                fileName = "001.png",
                mediaType = "image/png",
                fileSize = 12,
                fileHash = "shared-hash",
              ),
            ),
          createdAtMillis = 1,
        ),
      )
    }
  }
}
