package io.xoboro.server.media

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AnalyzeBookTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `routes media access and atomically persists analysis`() {
    val archive = archive()
    val book = bookFixture(sourceItemId = "opaque-book")
    val library = libraryFixture(analyzeDimensions = true)
    val mediaRepository = InMemoryMediaRepository()
    var materialized = false
    val access =
      object : SourceMediaAccess {
        override val sourceId: String = "synthetic"

        override fun materialize(
          rootItemId: String,
          itemId: String,
        ): MaterializedMedia {
          assertEquals("opaque-root", rootItemId)
          assertEquals("opaque-book", itemId)
          materialized = true
          return materialized(archive)
        }
      }
    val analyzer =
      AnalyzeBook(
        books = InMemoryBookRepository(book),
        libraries = InMemoryLibraryRepository(library),
        accesses = listOf(access),
        media = mediaRepository,
        zipAnalyzer = ZipMediaAnalyzer(),
        currentTimeMillis = { 1_700_000_000_000L },
      )

    val result = analyzer.execute(book.id)

    assertTrue(materialized)
    assertEquals(MediaStatus.READY, result.status)
    assertEquals(10, result.pages.single().dimension?.width)
    assertEquals(result, mediaRepository.media)
  }

  @Test
  fun `preserves media creation time across reanalysis`() {
    val archive = archive()
    val book = bookFixture(sourceItemId = archive.toString())
    val mediaRepository =
      InMemoryMediaRepository(
        BookMedia(
          bookId = book.id,
          status = MediaStatus.OUTDATED,
          createdAtMillis = 100,
          updatedAtMillis = 100,
        ),
      )
    val analyzer =
      AnalyzeBook(
        books = InMemoryBookRepository(book),
        libraries = InMemoryLibraryRepository(libraryFixture()),
        accesses =
          listOf(
            object : SourceMediaAccess {
              override val sourceId: String = "synthetic"

              override fun materialize(
                rootItemId: String,
                itemId: String,
              ): MaterializedMedia =
                materialized(archive)
            },
          ),
        media = mediaRepository,
        zipAnalyzer = ZipMediaAnalyzer(),
        currentTimeMillis = { 200 },
      )

    val result = analyzer.execute(book.id)

    assertEquals(100, result.createdAtMillis)
    assertEquals(200, result.updatedAtMillis)
  }

  @Test
  fun `rejects unknown source access before analysis`() {
    val book = bookFixture()
    val analyzer =
      AnalyzeBook(
        books = InMemoryBookRepository(book),
        libraries = InMemoryLibraryRepository(libraryFixture()),
        accesses = emptyList(),
        media = InMemoryMediaRepository(),
        zipAnalyzer = ZipMediaAnalyzer(),
        currentTimeMillis = { 1 },
      )

    assertFailsWith<UnknownSourceMediaAccessException> {
      analyzer.execute(book.id)
    }
  }

  private fun archive(): Path {
    val path = tempDirectory.resolve("book.cbz")
    val bytes =
      java.io.ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB), "png", output)
        output.toByteArray()
      }
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      output.putNextEntry(ZipEntry("001.png"))
      output.write(bytes)
      output.closeEntry()
    }
    return path
  }

  private fun materialized(sourcePath: Path): MaterializedMedia =
    object : MaterializedMedia {
      override val path: Path = sourcePath

      override fun close() = Unit
    }

  private fun bookFixture(
    sourceItemId: String = "opaque-book",
  ): Book =
    Book(
      id = BookId("book-1"),
      libraryId = LibraryId("library-1"),
      seriesId = SeriesId("series-1"),
      name = "Synthetic book",
      relativePath = "series/book.cbz",
      sourceItemId = sourceItemId,
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1,
      createdAtMillis = 1,
    )

  private fun libraryFixture(
    analyzeDimensions: Boolean = false,
  ): Library =
    Library(
      id = LibraryId("library-1"),
      name = "Synthetic library",
      root = SourceLocation("synthetic", "opaque-root"),
      settings = LibrarySettings(analyzeDimensions = analyzeDimensions),
      createdAtMillis = 1,
    )

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
    override fun findById(id: LibraryId): Library =
      findByIdOrNull(id) ?: throw NoSuchElementException()

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
    var media: BookMedia? = null,
  ) : BookMediaRepository {
    override fun findByBookIdOrNull(bookId: BookId): BookMedia? =
      media?.takeIf { it.bookId == bookId }

    override fun upsert(media: BookMedia) {
      this.media = media
    }

    override fun deleteByBookId(bookId: BookId) {
      media = null
    }
  }
}
