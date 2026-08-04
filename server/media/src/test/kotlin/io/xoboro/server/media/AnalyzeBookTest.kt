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
    val bookRepository = InMemoryBookRepository(book)
    val analyzer =
      AnalyzeBook(
        books = bookRepository,
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
    val updatedBook = requireNotNull(bookRepository.findByIdOrNull(book.id))
    assertTrue(updatedBook.fileHash.isNotBlank())
    assertTrue(updatedBook.fileHashKoreader.isNotBlank())
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
          pages =
            listOf(
              io.xoboro.core.domain.BookPage(
                number = 1,
                fileName = "001.png",
                mediaType = "image/png",
                fileSize =
                  java.util.zip.ZipFile(archive.toFile()).use {
                    requireNotNull(it.getEntry("001.png")).size
                  },
                fileHash = "preserved-page-hash",
              ),
            ),
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
    assertEquals("preserved-page-hash", result.pages.single().fileHash)
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

  /**
   * The whole point of the ranged path: a library that needs nothing out of an entry must not cause
   * the archive to be fetched. On a remote source, `materialize` here means transferring the file.
   */
  @Test
  fun `reads only the archive trailer when nothing needs entry bytes`() {
    val archive = archive()
    val probe = TrailerProbe(Files.readAllBytes(archive))
    val mediaRepository = InMemoryMediaRepository()
    val analyzer =
      AnalyzeBook(
        books = InMemoryBookRepository(bookFixture()),
        libraries =
          InMemoryLibraryRepository(libraryFixture(hashFiles = false, hashKoreader = false)),
        accesses = listOf(RefusingMediaAccess),
        media = mediaRepository,
        zipAnalyzer = ZipMediaAnalyzer(),
        randomAccesses = listOf(probe),
        currentTimeMillis = { 1_700_000_000_000L },
      )

    val result = analyzer.execute(BookId("book-1"))

    assertEquals(MediaStatus.READY, result.status)
    assertEquals(listOf("001.png"), result.pages.map { it.fileName })
    assertEquals(1, probe.opened)
    assertTrue(probe.lastMedia!!.closed, "the opened media must be closed")
    assertEquals(result, mediaRepository.media)
  }

  @Test
  fun `materializes when dimensions are requested, since dimensions need entry bytes`() {
    val archive = archive()
    val probe = TrailerProbe(Files.readAllBytes(archive))
    val access = CountingMediaAccess(archive)
    val analyzer =
      AnalyzeBook(
        books = InMemoryBookRepository(bookFixture()),
        libraries =
          InMemoryLibraryRepository(
            libraryFixture(analyzeDimensions = true, hashFiles = false, hashKoreader = false),
          ),
        accesses = listOf(access),
        media = InMemoryMediaRepository(),
        zipAnalyzer = ZipMediaAnalyzer(),
        randomAccesses = listOf(probe),
        currentTimeMillis = { 1_700_000_000_000L },
      )

    val result = analyzer.execute(BookId("book-1"))

    assertEquals(0, probe.opened)
    assertEquals(1, access.materializations)
    assertEquals(10, result.pages.single().dimension?.width)
  }

  @Test
  fun `materializes while a whole-file hash is still missing`() {
    val archive = archive()
    val probe = TrailerProbe(Files.readAllBytes(archive))
    val access = CountingMediaAccess(archive)
    val analyzer =
      AnalyzeBook(
        books = InMemoryBookRepository(bookFixture()),
        libraries = InMemoryLibraryRepository(libraryFixture(hashFiles = true, hashKoreader = false)),
        accesses = listOf(access),
        media = InMemoryMediaRepository(),
        zipAnalyzer = ZipMediaAnalyzer(),
        randomAccesses = listOf(probe),
        currentTimeMillis = { 1_700_000_000_000L },
      )

    analyzer.execute(BookId("book-1"))

    assertEquals(0, probe.opened)
    assertEquals(1, access.materializations)
  }

  /**
   * A hash already recorded is a hash that will not be recomputed, so the setting being on does not
   * by itself force the file to be read. This is the case that matters on reanalysis of a remote
   * library: the first scan pays, later ones do not have to.
   */
  @Test
  fun `takes the trailer path once the whole-file hash is already recorded`() {
    val archive = archive()
    val probe = TrailerProbe(Files.readAllBytes(archive))
    val access = CountingMediaAccess(archive)
    val analyzer =
      AnalyzeBook(
        books =
          InMemoryBookRepository(
            bookFixture().copy(fileHash = "already-recorded", fileHashKoreader = "already-recorded"),
          ),
        libraries = InMemoryLibraryRepository(libraryFixture(hashFiles = true, hashKoreader = true)),
        accesses = listOf(access),
        media = InMemoryMediaRepository(),
        zipAnalyzer = ZipMediaAnalyzer(),
        randomAccesses = listOf(probe),
        currentTimeMillis = { 1_700_000_000_000L },
      )

    analyzer.execute(BookId("book-1"))

    assertEquals(1, probe.opened)
    assertEquals(0, access.materializations)
  }

  /** A `.cbz` that is really a RAR reaches this, and must get the full read's diagnosis. */
  @Test
  fun `falls back to materializing when the trailer does not parse as a ZIP`() {
    val archive = archive()
    val probe = TrailerProbe(ByteArray(2_048) { 0x3f })
    val access = CountingMediaAccess(archive)
    val analyzer =
      AnalyzeBook(
        books = InMemoryBookRepository(bookFixture()),
        libraries =
          InMemoryLibraryRepository(libraryFixture(hashFiles = false, hashKoreader = false)),
        accesses = listOf(access),
        media = InMemoryMediaRepository(),
        zipAnalyzer = ZipMediaAnalyzer(),
        randomAccesses = listOf(probe),
        currentTimeMillis = { 1_700_000_000_000L },
      )

    val result = analyzer.execute(BookId("book-1"))

    assertEquals(1, probe.opened)
    assertEquals(1, access.materializations)
    assertEquals(MediaStatus.READY, result.status)
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
    hashFiles: Boolean = true,
    hashKoreader: Boolean = true,
    hashPages: Boolean = false,
  ): Library =
    Library(
      id = LibraryId("library-1"),
      name = "Synthetic library",
      root = SourceLocation("synthetic", "opaque-root"),
      settings =
        LibrarySettings(
          analyzeDimensions = analyzeDimensions,
          hashFiles = hashFiles,
          hashKoreader = hashKoreader,
          hashPages = hashPages,
        ),
      createdAtMillis = 1,
    )

  /** Serves an archive's bytes by range and records that it was opened, and later closed. */
  private class TrailerProbe(
    private val bytes: ByteArray,
  ) : SourceRandomAccess {
    override val sourceId: String = "synthetic"

    var opened: Int = 0
      private set

    var lastMedia: ByteArrayRandomAccessMedia? = null
      private set

    override fun open(
      rootItemId: String,
      itemId: String,
    ): RandomAccessMedia {
      assertEquals("opaque-root", rootItemId)
      assertEquals("opaque-book", itemId)
      opened++
      return ByteArrayRandomAccessMedia(bytes).also { lastMedia = it }
    }
  }

  /**
   * Fails the test if the whole file is ever fetched. Asserting "materialize was not called" through
   * a counter would still pass if the call happened and its result went unused; refusing outright
   * cannot.
   */
  private object RefusingMediaAccess : SourceMediaAccess {
    override val sourceId: String = "synthetic"

    override fun materialize(
      rootItemId: String,
      itemId: String,
    ): MaterializedMedia = throw AssertionError("The whole archive must not be materialized for this library")
  }

  private class CountingMediaAccess(
    private val archive: Path,
  ) : SourceMediaAccess {
    override val sourceId: String = "synthetic"

    var materializations: Int = 0
      private set

    override fun materialize(
      rootItemId: String,
      itemId: String,
    ): MaterializedMedia {
      materializations++
      return object : MaterializedMedia {
        override val path: Path = archive

        override fun close() = Unit
      }
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
