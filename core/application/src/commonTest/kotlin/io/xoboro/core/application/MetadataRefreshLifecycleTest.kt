package io.xoboro.core.application

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SeriesMetadataRepository
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataRefreshLifecycleTest {
  @Test
  fun `applies ordered provider patches while preserving locked fields`() {
    val repositories = Repositories()
    repositories.bookMetadata.upsert(
      BookMetadata(
        bookId = BOOK_ID,
        title = "Locked book title",
        summary = "Old summary",
        number = "1",
        numberSort = 1F,
        titleLock = true,
        createdAtMillis = 1,
      ),
    )
    repositories.seriesMetadata.upsert(
      SeriesMetadata(
        seriesId = SERIES_ID,
        title = "Old series title",
        publisher = "Locked publisher",
        publisherLock = true,
        createdAtMillis = 1,
      ),
    )
    val lifecycle =
      MetadataRefreshLifecycle(
        libraries = repositories.libraries,
        books = repositories.books,
        series = repositories.series,
        bookMetadata = repositories.bookMetadata,
        seriesMetadata = repositories.seriesMetadata,
        bookProviders =
          listOf(
            BookMetadataProvider { _, _ ->
              BookMetadataPatch(
                title = "Ignored title",
                summary = "First summary",
                number = "2",
              )
            },
            BookMetadataProvider { _, _ ->
              BookMetadataPatch(summary = "Final summary")
            },
          ),
        seriesProviders =
          listOf(
            SeriesMetadataProvider { _, _, _ ->
              SeriesMetadataPatch(
                status = SeriesStatus.ENDED,
                title = "Updated series title",
                readingDirection = ReadingDirection.WEBTOON,
                publisher = "Ignored publisher",
                totalBookCount = 12,
              )
            },
          ),
        currentTimeMillis = { 100 },
      )

    val book = requireNotNull(lifecycle.refreshBook(BOOK_ID))
    assertEquals("Locked book title", book.title)
    assertEquals("Final summary", book.summary)
    assertEquals("2", book.number)
    assertEquals(100, book.updatedAtMillis)

    val series = requireNotNull(lifecycle.refreshSeries(SERIES_ID))
    assertEquals(SeriesStatus.ENDED, series.status)
    assertEquals("Updated series title", series.title)
    assertEquals(ReadingDirection.WEBTOON, series.readingDirection)
    assertEquals("Locked publisher", series.publisher)
    assertEquals(12, series.totalBookCount)
    assertEquals(100, series.updatedAtMillis)
  }

  @Test
  fun `returns null when the catalog target no longer exists`() {
    val repositories = Repositories()
    repositories.books.delete(BOOK_ID)
    repositories.series.delete(SERIES_ID)
    val lifecycle =
      MetadataRefreshLifecycle(
        libraries = repositories.libraries,
        books = repositories.books,
        series = repositories.series,
        bookMetadata = repositories.bookMetadata,
        seriesMetadata = repositories.seriesMetadata,
        bookProviders = emptyList(),
        seriesProviders = emptyList(),
        currentTimeMillis = { 100 },
      )

    assertNull(lifecycle.refreshBook(BOOK_ID))
    assertNull(lifecycle.refreshSeries(SERIES_ID))
  }

  private class Repositories {
    val library =
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation("synthetic", "root"),
        createdAtMillis = 1,
      )
    val seriesItem =
      Series(
        id = SERIES_ID,
        libraryId = LIBRARY_ID,
        name = "Synthetic series",
        relativePath = "series",
        sourceItemId = "series-item",
        fileModifiedAtMillis = 1,
        bookCount = 1,
        createdAtMillis = 1,
      )
    val book =
      Book(
        id = BOOK_ID,
        libraryId = LIBRARY_ID,
        seriesId = SERIES_ID,
        name = "Synthetic book",
        relativePath = "series/book.cbz",
        sourceItemId = "book-item",
        mediaKind = MediaKind.COMIC_ARCHIVE,
        fileModifiedAtMillis = 1,
        number = 1,
        createdAtMillis = 1,
      )
    val libraries = InMemoryLibraryRepository(library)
    val series = InMemorySeriesRepository(seriesItem)
    val books = InMemoryBookRepository(book)
    val bookMetadata = InMemoryBookMetadataRepository()
    val seriesMetadata = InMemorySeriesMetadataRepository()
  }

  private class InMemoryLibraryRepository(
    private val library: Library,
  ) : LibraryRepository {
    override fun findById(id: LibraryId): Library =
      requireNotNull(findByIdOrNull(id))

    override fun findByIdOrNull(id: LibraryId): Library? = library.takeIf { it.id == id }

    override fun findAll(): List<Library> = listOf(library)

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> =
      listOf(library).filter { it.id in ids }

    override fun insert(library: Library) = error("not used")

    override fun update(library: Library) = error("not used")

    override fun delete(id: LibraryId) = error("not used")

    override fun deleteAll() = error("not used")

    override fun count(): Long = 1
  }

  private class InMemoryBookRepository(book: Book) : BookRepository {
    private val values = linkedMapOf(book.id to book)

    override fun findByIdOrNull(id: BookId): Book? = values[id]

    override fun findAllByLibraryId(libraryId: LibraryId): List<Book> =
      values.values.filter { it.libraryId == libraryId }

    override fun findAllBySeriesId(seriesId: SeriesId): List<Book> =
      values.values.filter { it.seriesId == seriesId }

    override fun findByLibraryIdAndRelativePath(
      libraryId: LibraryId,
      relativePath: String,
    ): Book? = values.values.firstOrNull {
      it.libraryId == libraryId && it.relativePath == relativePath
    }

    override fun insert(book: Book) {
      values[book.id] = book
    }

    override fun insertAll(books: Collection<Book>) = books.forEach(::insert)

    override fun update(book: Book) = insert(book)

    override fun updateAll(books: Collection<Book>) = books.forEach(::update)

    override fun delete(id: BookId) {
      values.remove(id)
    }

    override fun count(): Long = values.size.toLong()
  }

  private class InMemorySeriesRepository(series: Series) : SeriesRepository {
    private val values = linkedMapOf(series.id to series)

    override fun findByIdOrNull(id: SeriesId): Series? = values[id]

    override fun findAllByLibraryId(libraryId: LibraryId): List<Series> =
      values.values.filter { it.libraryId == libraryId }

    override fun findByLibraryIdAndRelativePath(
      libraryId: LibraryId,
      relativePath: String,
    ): Series? = values.values.firstOrNull {
      it.libraryId == libraryId && it.relativePath == relativePath
    }

    override fun insert(series: Series) {
      values[series.id] = series
    }

    override fun insertAll(series: Collection<Series>) = series.forEach(::insert)

    override fun update(series: Series) = insert(series)

    override fun updateAll(series: Collection<Series>) = series.forEach(::update)

    override fun delete(id: SeriesId) {
      values.remove(id)
    }

    override fun count(): Long = values.size.toLong()
  }

  private class InMemoryBookMetadataRepository : BookMetadataRepository {
    private val values = mutableMapOf<BookId, BookMetadata>()

    override fun findByBookIdOrNull(bookId: BookId): BookMetadata? = values[bookId]

    override fun upsert(metadata: BookMetadata) {
      values[metadata.bookId] = metadata
    }
  }

  private class InMemorySeriesMetadataRepository : SeriesMetadataRepository {
    private val values = mutableMapOf<SeriesId, SeriesMetadata>()

    override fun findBySeriesIdOrNull(seriesId: SeriesId): SeriesMetadata? = values[seriesId]

    override fun upsert(metadata: SeriesMetadata) {
      values[metadata.seriesId] = metadata
    }
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
  }
}
