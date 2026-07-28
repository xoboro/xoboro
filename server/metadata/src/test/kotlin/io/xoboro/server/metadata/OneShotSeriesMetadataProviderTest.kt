package io.xoboro.server.metadata

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OneShotSeriesMetadataProviderTest {
  @Test
  fun `promotes the first book metadata into a one-shot series`() {
    val repository =
      FixedBookMetadataRepository(
        BookMetadata(
          bookId = BOOK_ID,
          title = "Synthetic standalone",
          summary = "Synthetic standalone summary",
          number = "1",
          numberSort = 1F,
          createdAtMillis = 1,
        ),
      )
    val patch =
      requireNotNull(
        OneShotSeriesMetadataProvider(repository)
          .provide(library(), series(oneshot = true), listOf(book())),
      )

    assertEquals("Synthetic standalone", patch.title)
    assertEquals("Synthetic standalone", patch.titleSort)
    assertEquals(SeriesStatus.ENDED, patch.status)
    assertEquals("Synthetic standalone summary", patch.summary)
    assertEquals(1, patch.totalBookCount)
  }

  @Test
  fun `skips regular series and missing book metadata`() {
    val repository = FixedBookMetadataRepository()
    val provider = OneShotSeriesMetadataProvider(repository)

    assertNull(provider.provide(library(), series(oneshot = false), listOf(book())))
    assertNull(provider.provide(library(), series(oneshot = true), listOf(book())))
  }

  private fun library(): Library =
    Library(
      id = LIBRARY_ID,
      name = "Synthetic library",
      root = SourceLocation("synthetic", "root"),
      createdAtMillis = 1,
    )

  private fun series(oneshot: Boolean): Series =
    Series(
      id = SERIES_ID,
      libraryId = LIBRARY_ID,
      name = "standalone",
      relativePath = "oneshots/standalone.cbz",
      sourceItemId = "standalone-item",
      fileModifiedAtMillis = 1,
      bookCount = 1,
      oneshot = oneshot,
      createdAtMillis = 1,
    )

  private fun book(): Book =
    Book(
      id = BOOK_ID,
      libraryId = LIBRARY_ID,
      seriesId = SERIES_ID,
      name = "standalone",
      relativePath = "oneshots/standalone.cbz",
      sourceItemId = "standalone-item",
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1,
      number = 1,
      oneshot = true,
      createdAtMillis = 1,
    )

  private class FixedBookMetadataRepository(
    vararg metadata: BookMetadata,
  ) : BookMetadataRepository {
    private val values = metadata.associateBy(BookMetadata::bookId).toMutableMap()

    override fun findByBookIdOrNull(bookId: BookId): BookMetadata? = values[bookId]

    override fun findAllByBookIds(bookIds: Collection<BookId>): List<BookMetadata> =
      bookIds.mapNotNull(values::get)

    override fun upsert(metadata: BookMetadata) {
      values[metadata.bookId] = metadata
    }
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
  }
}
