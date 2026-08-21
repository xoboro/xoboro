package io.xoboro.server.media

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderReadyBookIndexerTest {
  @Test
  fun `persists a readable manifest before requested hashes and dimensions`() {
    val book = bookFixture()
    val books = InMemoryBookRepository(book)
    val media = InMemoryMediaRepository()
    val randomAccess = ByteArraySourceRandomAccess(archiveBytes())
    val indexer =
      ReaderReadyBookIndexer(
        books = books,
        libraries = InMemoryLibraryRepository(libraryFixture()),
        media = media,
        randomAccesses = listOf(randomAccess),
        fallback = { error("reader-ready CBZ must not run full analysis") },
        currentTimeMillis = { 200 },
      )

    val result = indexer.execute(book.id)

    assertEquals(MediaStatus.READY, result.status)
    assertEquals(listOf("001.png", "002.jpg"), result.pages.map { it.fileName })
    assertNull(result.pages.first().dimension)
    assertEquals(result, media.stored)
    assertEquals("", requireNotNull(books.findByIdOrNull(book.id)).fileHash)
    assertEquals(1, randomAccess.opens)
  }

  @Test
  fun `does not replace an existing readable manifest while enrichment is requested`() {
    val book = bookFixture()
    val existing =
      BookMedia(
        bookId = book.id,
        status = MediaStatus.READY,
        pages = listOf(BookPage(1, "001.png", "image/png", 3)),
        createdAtMillis = 100,
        updatedAtMillis = 150,
      )
    val media = InMemoryMediaRepository(existing)
    val randomAccess = ByteArraySourceRandomAccess(archiveBytes())
    var fallbackCalls = 0
    val indexer =
      ReaderReadyBookIndexer(
        books = InMemoryBookRepository(book),
        libraries = InMemoryLibraryRepository(libraryFixture()),
        media = media,
        randomAccesses = listOf(randomAccess),
        fallback = {
          fallbackCalls++
          error("existing READY media must not run full analysis")
        },
        currentTimeMillis = { 200 },
      )

    val result = indexer.execute(book.id)

    assertEquals(existing, result)
    assertEquals(existing, media.stored)
    assertEquals(0, randomAccess.opens)
    assertEquals(0, fallbackCalls)
  }

  @Test
  fun `uses full analysis when the item cannot have a zip manifest`() {
    val book = bookFixture().copy(mediaKind = MediaKind.PDF)
    val fallbackResult =
      BookMedia(
        bookId = book.id,
        status = MediaStatus.READY,
        pages = listOf(BookPage(1, "page-1", "image/jpeg", 10)),
        createdAtMillis = 200,
        updatedAtMillis = 200,
      )
    var fallbackCalls = 0
    val indexer =
      ReaderReadyBookIndexer(
        books = InMemoryBookRepository(book),
        libraries = InMemoryLibraryRepository(libraryFixture()),
        media = InMemoryMediaRepository(),
        randomAccesses = emptyList(),
        fallback = {
          fallbackCalls++
          fallbackResult
        },
        currentTimeMillis = { 200 },
      )

    val result = indexer.execute(book.id)

    assertEquals(fallbackResult, result)
    assertEquals(1, fallbackCalls)
  }

  private fun archiveBytes(): ByteArray =
    ByteArrayOutputStream().use { bytes ->
      ZipOutputStream(bytes).use { zip ->
        listOf("002.jpg", "001.png").forEach { name ->
          zip.putNextEntry(ZipEntry(name))
          zip.write(byteArrayOf(1, 2, 3))
          zip.closeEntry()
        }
      }
      bytes.toByteArray()
    }

  private fun bookFixture(): Book =
    Book(
      id = BOOK_ID,
      libraryId = LIBRARY_ID,
      seriesId = SeriesId("series-1"),
      name = "Synthetic book",
      relativePath = "series/book.cbz",
      sourceItemId = "opaque-book",
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1,
      createdAtMillis = 1,
    )

  private fun libraryFixture(): Library =
    Library(
      id = LIBRARY_ID,
      name = "Synthetic library",
      root = SourceLocation("synthetic", "opaque-root"),
      settings =
        LibrarySettings(
          hashFiles = true,
          analyzeDimensions = true,
        ),
      createdAtMillis = 1,
    )

  private class ByteArraySourceRandomAccess(
    private val bytes: ByteArray,
  ) : SourceRandomAccess {
    override val sourceId: String = "synthetic"

    var opens: Int = 0
      private set

    override fun open(
      rootItemId: String,
      itemId: String,
    ): RandomAccessMedia {
      assertEquals("opaque-root", rootItemId)
      assertEquals("opaque-book", itemId)
      opens++
      return ByteArrayRandomAccessMedia(bytes)
    }
  }

  private class InMemoryBookRepository(
    private var book: Book,
  ) : BookRepository {
    override fun findByIdOrNull(id: BookId): Book? = book.takeIf { it.id == id }

    override fun findAllByLibraryId(libraryId: LibraryId): List<Book> =
      listOf(book).filter { it.libraryId == libraryId }

    override fun findAllBySeriesId(seriesId: SeriesId): List<Book> =
      listOf(book).filter { it.seriesId == seriesId }

    override fun findByLibraryIdAndRelativePath(
      libraryId: LibraryId,
      relativePath: String,
    ): Book? = book.takeIf { it.libraryId == libraryId && it.relativePath == relativePath }

    override fun insert(book: Book) {
      this.book = book
    }

    override fun insertAll(books: Collection<Book>) {
      this.book = books.single()
    }

    override fun update(book: Book) {
      this.book = book
    }

    override fun updateAll(books: Collection<Book>) {
      this.book = books.single()
    }

    override fun delete(id: BookId) = Unit

    override fun count(): Long = 1
  }

  private class InMemoryLibraryRepository(
    private val library: Library,
  ) : LibraryRepository {
    override fun findById(id: LibraryId): Library = requireNotNull(findByIdOrNull(id))

    override fun findByIdOrNull(id: LibraryId): Library? = library.takeIf { it.id == id }

    override fun findAll(): List<Library> = listOf(library)

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> =
      listOf(library).filter { it.id in ids }

    override fun insert(library: Library) = Unit

    override fun update(library: Library) = Unit

    override fun delete(id: LibraryId) = Unit

    override fun deleteAll() = Unit

    override fun count(): Long = 1
  }

  private class InMemoryMediaRepository(
    initial: BookMedia? = null,
  ) : BookMediaRepository {
    var stored: BookMedia? = initial
      private set

    override fun findByBookIdOrNull(bookId: BookId): BookMedia? =
      stored?.takeIf { it.bookId == bookId }

    override fun upsert(media: BookMedia) {
      stored = media
    }

    override fun deleteByBookId(bookId: BookId) {
      stored = null
    }
  }

  private companion object {
    val BOOK_ID = BookId("book-1")
    val LIBRARY_ID = LibraryId("library-1")
  }
}
