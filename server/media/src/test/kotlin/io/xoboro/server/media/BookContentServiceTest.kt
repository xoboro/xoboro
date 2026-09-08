package io.xoboro.server.media

import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle

class BookContentServiceTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  /**
   * The half of "do not transfer a remote library" that analysis alone did not buy. Cover generation
   * calls straight through here for page 1, so while this materialized, every scanned book still
   * fetched its whole archive - the WebDAV server's log showed a `206` of 65,557 bytes followed
   * immediately by a `200` of the entire file, once per book.
   */
  @Test
  fun `serves a page by range without materializing the archive`() {
    val expected = byteArrayOf(1, 3, 5, 7)
    val archive = archive(mapOf("nested/001.png" to expected, "ignored.txt" to byteArrayOf(9)))
    val probe = PageRangeProbe(Files.readAllBytes(archive), sourceId = "synthetic")
    val service = service(access = RefusingAccess(), randomAccesses = listOf(probe))

    val opened = requireNotNull(service.openPage(BOOK_ID, 1))

    assertContentEquals(expected, opened.input.readBytes())
    assertEquals("image/png", opened.mediaType)
    assertEquals(1, probe.opened)
  }

  @Test
  fun `converts a ranged page without materializing the archive`() {
    val encodedPng =
      ByteArrayOutputStream()
        .also { ImageIO.write(BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB), "png", it) }
        .toByteArray()
    val archive = archive(mapOf("nested/001.png" to encodedPng))
    val probe = PageRangeProbe(Files.readAllBytes(archive), sourceId = "synthetic")
    val service = service(access = RefusingAccess(), randomAccesses = listOf(probe))

    val opened =
      requireNotNull(
        service.openPage(BOOK_ID, 1, PageImageRequest(format = PageImageFormat.JPEG)),
      )

    assertEquals(PageImageFormat.JPEG.mediaType, opened.mediaType)
    assertTrue(opened.input.readBytes().isNotEmpty())
    assertEquals(1, probe.opened)
  }

  /** RAR needs a real file, so a ranged source must not divert it. */
  @Test
  fun `materializes a RAR archive even when a ranged source is registered`() {
    val archive = archive(mapOf("nested/001.png" to byteArrayOf(1, 2, 3, 4)))
    val access = RecordingAccess(archive)
    val probe = PageRangeProbe(Files.readAllBytes(archive), sourceId = access.sourceId)
    val service =
      service(
        access = access,
        mediaType = RarMediaAnalyzer.RAR_MEDIA_TYPE,
        randomAccesses = listOf(probe),
      )

    assertFailsWith<Exception> { service.openPage(BOOK_ID, 1) }

    assertEquals(0, probe.opened, "a RAR page must never be attempted by range")
  }

  @Test
  fun `falls back to materializing when the ranged read cannot parse the archive`() {
    val expected = byteArrayOf(2, 4, 6, 8)
    val archive = archive(mapOf("nested/001.png" to expected))
    val access = RecordingAccess(archive)
    val probe = PageRangeProbe(ByteArray(2_048) { 0x3f }, sourceId = access.sourceId)
    val service = service(access = access, randomAccesses = listOf(probe))

    val opened = requireNotNull(service.openPage(BOOK_ID, 1))

    assertContentEquals(expected, opened.input.readBytes())
    assertEquals(1, probe.opened)
  }

  @Test
  fun `streams the indexed archive entry and closes materialization`() {
    val expected = byteArrayOf(1, 3, 5, 7)
    val archive = archive(mapOf("nested/001.png" to expected, "ignored.txt" to byteArrayOf(9)))
    val access = RecordingAccess(archive)
    val service = service(access = access)

    val opened = requireNotNull(service.openPage(BOOK_ID, 1))
    val actual = buildList<Byte> {
      val buffer = ByteArray(2)
      while (true) {
        val count = opened.read(buffer)
        if (count < 0) break
        repeat(count) { add(buffer[it]) }
      }
    }.toByteArray()

    assertContentEquals(expected, actual)
    assertEquals("image/png", opened.mediaType)
    assertEquals(expected.size.toLong(), opened.contentLength)
    assertEquals(0, access.closeCount)
    opened.close()
    assertEquals(1, access.closeCount)
  }

  @Test
  fun `streams and converts an indexed RAR page`() {
    val source =
      ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(320, 160, BufferedImage.TYPE_INT_RGB), "png", output)
        output.toByteArray()
      }
    val archive =
      writeSyntheticRar4(
        temporaryDirectory.resolve("synthetic.cbr"),
        mapOf("nested/001.png" to source),
      )
    val access = RecordingAccess(archive)
    val service =
      service(
        access = access,
        mediaType = RarMediaAnalyzer.RAR_MEDIA_TYPE,
      )

    val raw = requireNotNull(service.openPage(BOOK_ID, 1))
    assertContentEquals(source, raw.inputBytes())
    raw.close()

    val converted =
      requireNotNull(
        service.openPage(
          BOOK_ID,
          1,
          PageImageRequest(
            format = PageImageFormat.JPEG,
            maximumDimension = 160,
          ),
        ),
      )
    val image = requireNotNull(ImageIO.read(ByteArrayInputStream(converted.inputBytes())))
    assertEquals(160, image.width)
    assertEquals(80, image.height)
    assertEquals("image/jpeg", converted.mediaType)
    assertEquals(2, access.closeCount)
    converted.close()
  }

  @Test
  fun `returns indexed pages without materializing the source`() {
    val access = RecordingAccess(archive(emptyMap()))
    val service = service(access = access)

    val pages = requireNotNull(service.pages(BOOK_ID))

    assertEquals("nested/001.png", pages.single().fileName)
    assertEquals(0, access.materializeCount)
  }

  @Test
  fun `streams the original book file and closes materialization`() {
    val expected = byteArrayOf(2, 4, 6, 8, 10)
    val archive = temporaryDirectory.resolve("download.cbz")
    Files.write(archive, expected)
    val access = RecordingAccess(archive)
    val service = service(access = access)

    val opened = requireNotNull(service.openBook(BOOK_ID))
    val actual = ByteArray(expected.size)
    assertEquals(expected.size, opened.read(actual))

    assertContentEquals(expected, actual)
    assertEquals("book.cbz", opened.fileName)
    assertEquals(expected.size.toLong(), opened.contentLength)
    opened.close()
    assertEquals(1, access.closeCount)
  }

  @Test
  fun `converts and bounds a single page without retaining archive resources`() {
    val source =
      ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB), "png", output)
        output.toByteArray()
      }
    val access = RecordingAccess(archive(mapOf("nested/001.png" to source)))
    val service = service(access = access)

    val opened =
      requireNotNull(
        service.openPage(
          BOOK_ID,
          1,
          PageImageRequest(
            format = PageImageFormat.JPEG,
            maximumDimension = 300,
          ),
        ),
      )
    val converted = buildList<Byte> {
      val buffer = ByteArray(1_024)
      while (true) {
        val count = opened.read(buffer)
        if (count < 0) break
        repeat(count) { add(buffer[it]) }
      }
    }.toByteArray()
    val image = requireNotNull(ImageIO.read(ByteArrayInputStream(converted)))

    assertEquals(300, image.width)
    assertEquals(150, image.height)
    assertEquals("image/jpeg", opened.mediaType)
    assertEquals("001.jpg", opened.fileName)
    assertEquals(1, access.closeCount)
    opened.close()
    assertEquals(1, access.closeCount)
  }

  @Test
  fun `maximum width preserves the aspect ratio of a tall page`() {
    val source =
      ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(1_600, 6_000, BufferedImage.TYPE_BYTE_GRAY), "png", output)
        output.toByteArray()
      }
    val service = service(access = RecordingAccess(archive(mapOf("nested/001.png" to source))))

    val opened =
      requireNotNull(
        service.openPage(
          BOOK_ID,
          1,
          PageImageRequest(maximumWidth = 800),
        ),
      )
    val image = requireNotNull(ImageIO.read(ByteArrayInputStream(opened.readAllBytes())))

    assertEquals(800, image.width)
    assertEquals(3_000, image.height)
    opened.close()
  }

  @Test
  fun `maximum width does not upscale a narrower page`() {
    val source =
      ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB), "png", output)
        output.toByteArray()
      }
    val service = service(access = RecordingAccess(archive(mapOf("nested/001.png" to source))))

    val opened =
      requireNotNull(
        service.openPage(
          BOOK_ID,
          1,
          PageImageRequest(maximumWidth = 800),
        ),
      )
    val image = requireNotNull(ImageIO.read(ByteArrayInputStream(opened.readAllBytes())))

    assertEquals(600, image.width)
    assertEquals(300, image.height)
    opened.close()
  }

  @Test
  fun `maximum width decodes a non-multiple source at or above the requested width`() {
    val source =
      ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(1_601, 600, BufferedImage.TYPE_BYTE_GRAY), "png", output)
        output.toByteArray()
      }
    val service = service(access = RecordingAccess(archive(mapOf("nested/001.png" to source))))

    val opened =
      requireNotNull(
        service.openPage(
          BOOK_ID,
          1,
          PageImageRequest(maximumWidth = 800),
        ),
      )
    val image = requireNotNull(ImageIO.read(ByteArrayInputStream(opened.readAllBytes())))

    assertEquals(800, image.width)
    assertEquals(299, image.height)
    opened.close()
  }

  @Test
  fun `does not materialize for an out of range page`() {
    val access = RecordingAccess(archive(emptyMap()))
    val service = service(access = access)

    assertNull(service.openPage(BOOK_ID, 2))
    assertEquals(0, access.materializeCount)
  }

  @Test
  fun `rejects media that is not ready`() {
    val service =
      service(
        access = RecordingAccess(archive(emptyMap())),
        status = MediaStatus.OUTDATED,
      )

    val failure =
      assertFailsWith<IllegalArgumentException> {
        service.openPage(BOOK_ID, 1)
      }

    assertTrue(failure.message.orEmpty().contains("OUTDATED"))
  }

  @Test
  fun `closes materialization when an indexed entry is missing`() {
    val access = RecordingAccess(archive(mapOf("other.png" to byteArrayOf(1))))
    val service = service(access = access)

    assertFailsWith<IllegalStateException> {
      service.openPage(BOOK_ID, 1)
    }

    assertEquals(1, access.closeCount)
  }

  @Test
  fun `streams an indexed epub resource with its declared media type`() {
    val expected = "<html><body>Synthetic</body></html>".encodeToByteArray()
    val access =
      RecordingAccess(
        archive(mapOf("OEBPS/chapter.xhtml" to expected)),
      )
    val service =
      service(
        access = access,
        mediaKind = MediaKind.EPUB,
        mediaType = EpubMediaAnalyzer.EPUB_MEDIA_TYPE,
        profile = MediaProfile.EPUB,
        pages = emptyList(),
        files =
          listOf(
            MediaFile(
              fileName = "OEBPS/chapter.xhtml",
              mediaType = "application/xhtml+xml",
            ),
          ),
      )

    val opened =
      requireNotNull(service.openResource(BOOK_ID, "OEBPS/chapter.xhtml"))
    val actual = ByteArray(expected.size)
    assertEquals(expected.size, opened.read(actual))

    assertContentEquals(expected, actual)
    assertEquals("application/xhtml+xml", opened.mediaType)
    assertEquals(0, access.closeCount)
    opened.close()
    assertEquals(1, access.closeCount)
  }

  @Test
  fun `renders and extracts a raw pdf page while closing materializations`() {
    val path = temporaryDirectory.resolve("synthetic.pdf")
    PDDocument().use { document ->
      document.addPage(PDPage())
      document.save(path.toFile())
    }
    val access = RecordingAccess(path)
    val service =
      service(
        access = access,
        mediaKind = MediaKind.PDF,
        mediaType = PdfMediaAnalyzer.PDF_MEDIA_TYPE,
        profile = MediaProfile.PDF,
        pages =
          listOf(
            BookPage(
              number = 1,
              fileName = "1",
              mediaType = PdfMediaAnalyzer.PDF_MEDIA_TYPE,
            ),
          ),
      )

    val rendered = requireNotNull(service.openPage(BOOK_ID, 1))
    val renderedBytes = rendered.readAllBytes()
    assertEquals("image/jpeg", rendered.mediaType)
    assertTrue(ImageIO.read(ByteArrayInputStream(renderedBytes)) != null)
    rendered.close()
    assertEquals(1, access.closeCount)

    val raw =
      requireNotNull(
        service.openPage(
          BOOK_ID,
          1,
          PageImageRequest(raw = true),
        ),
      )
    val rawBytes = raw.readAllBytes()
    assertEquals(PdfMediaAnalyzer.PDF_MEDIA_TYPE, raw.mediaType)
    assertTrue(rawBytes.decodeToString(0, 4).startsWith("%PDF"))
    raw.close()
    assertEquals(2, access.closeCount)
  }

  @Test
  fun `maximum width bounds pdf rendering before the decoded pixel safety limit`() {
    val path = temporaryDirectory.resolve("tall-synthetic.pdf")
    PDDocument().use { document ->
      document.addPage(PDPage(PDRectangle(400F, 100_000F)))
      document.save(path.toFile())
    }
    val service =
      service(
        access = RecordingAccess(path),
        mediaKind = MediaKind.PDF,
        mediaType = PdfMediaAnalyzer.PDF_MEDIA_TYPE,
        profile = MediaProfile.PDF,
        pages =
          listOf(
            BookPage(
              number = 1,
              fileName = "1",
              mediaType = PdfMediaAnalyzer.PDF_MEDIA_TYPE,
            ),
          ),
      )

    val opened =
      requireNotNull(
        service.openPage(BOOK_ID, 1, PageImageRequest(maximumWidth = 200)),
      )
    val image = requireNotNull(ImageIO.read(ByteArrayInputStream(opened.readAllBytes())))

    assertEquals(200, image.width)
    assertEquals(50_000, image.height)
    opened.close()
  }

  private fun service(
    access: SourceMediaAccess,
    status: MediaStatus = MediaStatus.READY,
    mediaKind: MediaKind = MediaKind.COMIC_ARCHIVE,
    mediaType: String = "application/zip",
    profile: MediaProfile? = null,
    pages: List<BookPage> =
      listOf(
        BookPage(
          number = 1,
          fileName = "nested/001.png",
          mediaType = "image/png",
          fileSize = 4,
        ),
      ),
    files: List<MediaFile> = emptyList(),
    randomAccesses: Collection<SourceRandomAccess> = emptyList(),
  ): BookContentService {
    val library =
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation(access.sourceId, "opaque-root"),
        createdAtMillis = 1,
      )
    val book =
      Book(
        id = BOOK_ID,
        libraryId = LIBRARY_ID,
        seriesId = SeriesId("series-1"),
        name = "Synthetic book",
        relativePath = "series/book.cbz",
        sourceItemId = "opaque-book",
        mediaKind = mediaKind,
        fileModifiedAtMillis = 1,
        createdAtMillis = 1,
      )
    val analyzed =
      BookMedia(
        bookId = BOOK_ID,
        status = status,
        mediaType = mediaType,
        profile = profile,
        pages = pages,
        files = files,
        createdAtMillis = 1,
      )
    return BookContentService(
      libraries = SingleLibraryRepository(library),
      books = SingleBookRepository(book),
      media = SingleMediaRepository(analyzed),
      accesses = listOf(access),
      randomAccesses = randomAccesses,
    )
  }

  /** Serves an archive's bytes by range under the same source ID a [SourceMediaAccess] uses. */
  private class PageRangeProbe(
    private val bytes: ByteArray,
    override val sourceId: String,
  ) : SourceRandomAccess {
    var opened: Int = 0
      private set

    override fun open(
      rootItemId: String,
      itemId: String,
    ): RandomAccessMedia {
      opened++
      return ByteArrayRandomAccessMedia(bytes)
    }
  }

  /** Fails the test outright if the whole archive is fetched, rather than counting after the fact. */
  private class RefusingAccess(
    override val sourceId: String = "synthetic",
  ) : SourceMediaAccess {
    override fun materialize(
      rootItemId: String,
      itemId: String,
    ): MaterializedMedia = throw AssertionError("The archive must not be materialized to serve one page")
  }

  private fun archive(entries: Map<String, ByteArray>): Path {
    val path = temporaryDirectory.resolve("synthetic-${entries.hashCode()}.cbz")
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      entries.forEach { (name, bytes) ->
        output.putNextEntry(ZipEntry(name))
        output.write(bytes)
        output.closeEntry()
      }
    }
    return path
  }

  private fun io.xoboro.core.application.MediaContentStream.inputBytes(): ByteArray =
    buildList<Byte> {
      val buffer = ByteArray(1_024)
      while (true) {
        val count = read(buffer)
        if (count < 0) break
        repeat(count) { add(buffer[it]) }
      }
    }.toByteArray()

  private fun io.xoboro.core.application.MediaContentStream.readAllBytes(): ByteArray =
    buildList<Byte> {
      val buffer = ByteArray(4 * 1_024)
      while (true) {
        val count = read(buffer)
        if (count < 0) break
        repeat(count) { add(buffer[it]) }
      }
    }.toByteArray()

  private class RecordingAccess(
    private val archive: Path,
  ) : SourceMediaAccess {
    override val sourceId: String = "synthetic"
    var materializeCount: Int = 0
    var closeCount: Int = 0

    override fun materialize(
      rootItemId: String,
      itemId: String,
    ): MaterializedMedia {
      assertEquals("opaque-root", rootItemId)
      assertEquals("opaque-book", itemId)
      materializeCount += 1
      return object : MaterializedMedia {
        override val path: Path = archive

        override fun close() {
          closeCount += 1
        }
      }
    }
  }

  private class SingleMediaRepository(
    private val media: BookMedia,
  ) : BookMediaRepository {
    override fun findByBookIdOrNull(bookId: BookId): BookMedia? =
      media.takeIf { it.bookId == bookId }

    override fun upsert(media: BookMedia) = Unit

    override fun deleteByBookId(bookId: BookId) = Unit
  }

  private class SingleBookRepository(
    private val book: Book,
  ) : BookRepository {
    override fun findByIdOrNull(id: BookId): Book? = book.takeIf { it.id == id }

    override fun findAllByLibraryId(libraryId: LibraryId): List<Book> =
      listOf(book).filter { it.libraryId == libraryId }

    override fun findAllBySeriesId(seriesId: SeriesId): List<Book> =
      listOf(book).filter { it.seriesId == seriesId }

    override fun findByLibraryIdAndRelativePath(
      libraryId: LibraryId,
      relativePath: String,
    ): Book? =
      book.takeIf { it.libraryId == libraryId && it.relativePath == relativePath }

    override fun insert(book: Book) = Unit

    override fun insertAll(books: Collection<Book>) = Unit

    override fun update(book: Book) = Unit

    override fun updateAll(books: Collection<Book>) = Unit

    override fun delete(id: BookId) = Unit

    override fun count(): Long = 1
  }

  private class SingleLibraryRepository(
    private val library: Library,
  ) : LibraryRepository {
    override fun findById(id: LibraryId): Library =
      requireNotNull(findByIdOrNull(id))

    override fun findByIdOrNull(id: LibraryId): Library? =
      library.takeIf { it.id == id }

    override fun findAll(): List<Library> = listOf(library)

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> =
      listOf(library).filter { it.id in ids }

    override fun insert(library: Library) = Unit

    override fun update(library: Library) = Unit

    override fun delete(id: LibraryId) = Unit

    override fun deleteAll() = Unit

    override fun count(): Long = 1
  }

  private companion object {
    val BOOK_ID = BookId("book-1")
    val LIBRARY_ID = LibraryId("library-1")
  }
}
