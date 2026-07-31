package io.xoboro.server.tasks

import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.AnalyzeBook
import io.xoboro.server.media.BookContentService
import io.xoboro.server.media.SafeJpegArtworkProcessor
import io.xoboro.server.media.ZipMediaAnalyzer
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqArtworkRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookMetadataRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.sources.local.LocalSourceMediaAccess
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BookCoverGenerationLifecycleTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `analyzing a comic archive generates a selected cover with the right content type`() {
    withEnvironment("comic-cover") { env ->
      val archive = env.comicArchive("book.cbz")
      env.insertBook(BOOK_ID, archive, MediaKind.COMIC_ARCHIVE)

      env.analyze(BOOK_ID)
      env.lifecycle.generateForBook(BOOK_ID)

      val content = env.artwork.selectedContentOrNull(mediaItemOwner(BOOK_ID))
      assertNotNull(content)
      assertEquals("image/jpeg", content.artwork.mediaType)
      assertEquals(ArtworkType.GENERATED, content.artwork.type)
      assertTrue(content.artwork.selected)
      assertTrue(content.bytes.isNotEmpty())
    }
  }

  @Test
  fun `an uploaded cover is not replaced by a later analysis`() {
    withEnvironment("uploaded-cover") { env ->
      val archive = env.comicArchive("book.cbz")
      env.insertBook(BOOK_ID, archive, MediaKind.COMIC_ARCHIVE)
      env.analyze(BOOK_ID)
      val owner = mediaItemOwner(BOOK_ID)
      env.artwork.addUploaded(owner, jpegBytes(Color.MAGENTA), selected = true)

      env.lifecycle.generateForBook(BOOK_ID)

      val selected = env.artwork.selectedContentOrNull(owner)
      assertNotNull(selected)
      assertEquals(ArtworkType.USER_UPLOADED, selected.artwork.type)
      val all = env.artwork.findAll(owner)
      assertEquals(1, all.count { it.type == ArtworkType.USER_UPLOADED })
      assertEquals(1, all.count { it.type == ArtworkType.GENERATED })
    }
  }

  @Test
  fun `re-analysis does not accumulate generated covers`() {
    withEnvironment("no-accumulation") { env ->
      val archive = env.comicArchive("book.cbz")
      env.insertBook(BOOK_ID, archive, MediaKind.COMIC_ARCHIVE)

      env.analyze(BOOK_ID)
      env.lifecycle.generateForBook(BOOK_ID)
      env.analyze(BOOK_ID)
      env.lifecycle.generateForBook(BOOK_ID)

      val generated =
        env.artwork.findAll(mediaItemOwner(BOOK_ID)).filter { it.type == ArtworkType.GENERATED }
      assertEquals(1, generated.size)
    }
  }

  @Test
  fun `an undecodable first page leaves analysis successful and the item cover-less`() {
    withEnvironment("undecodable-page") { env ->
      val archive = env.corruptComicArchive("book.cbz")
      env.insertBook(BOOK_ID, archive, MediaKind.COMIC_ARCHIVE)

      env.analyze(BOOK_ID)
      env.lifecycle.generateForBook(BOOK_ID)

      assertEquals(MediaStatus.READY, env.media.findByBookIdOrNull(BOOK_ID)?.status)
      assertTrue(env.artwork.findAll(mediaItemOwner(BOOK_ID)).isEmpty())
    }
  }

  @Test
  fun `an epub with a declared cover gets one and one without does not`() {
    withEnvironment("epub-cover") { env ->
      val withCover = env.epub("with-cover.epub", declareCover = true)
      val withoutCover = env.epub("without-cover.epub", declareCover = false)
      env.insertBook(BOOK_ID, withCover, MediaKind.EPUB)
      env.insertBook(BOOK_ID_2, withoutCover, MediaKind.EPUB)

      env.analyze(BOOK_ID)
      env.analyze(BOOK_ID_2)
      env.lifecycle.generateForBook(BOOK_ID)
      env.lifecycle.generateForBook(BOOK_ID_2)

      assertNotNull(env.artwork.selectedContentOrNull(mediaItemOwner(BOOK_ID)))
      assertNull(env.artwork.selectedContentOrNull(mediaItemOwner(BOOK_ID_2)))
    }
  }

  @Test
  fun `series cover is derived from the first item in reading order`() {
    withEnvironment("series-cover") { env ->
      val second = env.comicArchive("book2.cbz", Color.BLUE)
      val first = env.comicArchive("book1.cbz", Color.RED)
      env.insertBook(BOOK_ID, first, MediaKind.COMIC_ARCHIVE, numberSort = 2F)
      env.insertBook(BOOK_ID_2, second, MediaKind.COMIC_ARCHIVE, numberSort = 1F)
      env.analyze(BOOK_ID)
      env.analyze(BOOK_ID_2)
      env.lifecycle.generateForBook(BOOK_ID)
      env.lifecycle.generateForBook(BOOK_ID_2)

      env.lifecycle.generateForSeries(SERIES_ID)

      val seriesCover =
        env.artwork.selectedContentOrNull(ArtworkOwner(ArtworkOwnerKind.SERIES, SERIES_ID.value))
      val firstItemCover = env.artwork.selectedContentOrNull(mediaItemOwner(BOOK_ID_2))
      assertNotNull(seriesCover)
      assertNotNull(firstItemCover)
      assertEquals(firstItemCover.bytes.toList(), seriesCover.bytes.toList())
    }
  }

  private fun mediaItemOwner(bookId: BookId) = ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, bookId.value)

  private fun withEnvironment(
    name: String,
    block: (Environment) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("$name.sqlite"))).use { database ->
      val libraries = JooqLibraryRepository(database)
      val books = JooqBookRepository(database)
      val series = JooqSeriesRepository(database)
      val bookMetadata = JooqBookMetadataRepository(database)
      val media = JooqBookMediaRepository(database)
      var sequence = 0
      val artwork =
        ArtworkLifecycle(
          artwork = JooqArtworkRepository(database),
          processor = SafeJpegArtworkProcessor(),
          idFactory = { "artwork-${++sequence}" },
          currentTimeMillis = { sequence.toLong() },
        )
      val content =
        BookContentService(
          libraries = libraries,
          books = books,
          media = media,
          accesses = listOf(LocalSourceMediaAccess()),
        )
      val analyzeBook =
        AnalyzeBook(
          books = books,
          libraries = libraries,
          accesses = listOf(LocalSourceMediaAccess()),
          media = media,
          zipAnalyzer = ZipMediaAnalyzer(),
          currentTimeMillis = { 1_000 },
        )
      val lifecycle =
        BookCoverGenerationLifecycle(
          books = books,
          media = media,
          bookMetadata = bookMetadata,
          series = series,
          content = content,
          artwork = artwork,
        )
      libraries.insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", temporaryDirectory.toUri().toString()),
          createdAtMillis = 1,
        ),
      )
      series.insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "series",
          sourceItemId = temporaryDirectory.toUri().toString(),
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      block(
        Environment(
          books = books,
          bookMetadata = bookMetadata,
          media = media,
          artwork = artwork,
          analyzeBook = analyzeBook,
          lifecycle = lifecycle,
        ),
      )
    }
  }

  private inner class Environment(
    private val books: JooqBookRepository,
    private val bookMetadata: JooqBookMetadataRepository,
    val media: JooqBookMediaRepository,
    val artwork: ArtworkLifecycle,
    private val analyzeBook: AnalyzeBook,
    val lifecycle: BookCoverGenerationLifecycle,
  ) {
    fun insertBook(
      bookId: BookId,
      file: Path,
      mediaKind: MediaKind,
      numberSort: Float = 0F,
    ) {
      books.insert(
        Book(
          id = bookId,
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic ${bookId.value}",
          relativePath = "series/${file.fileName}",
          sourceItemId = file.toUri().toString(),
          mediaKind = mediaKind,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      bookMetadata.upsert(
        BookMetadata(
          bookId = bookId,
          title = "Synthetic ${bookId.value}",
          number = numberSort.toString(),
          numberSort = numberSort,
          createdAtMillis = 1,
        ),
      )
    }

    fun analyze(bookId: BookId) {
      analyzeBook.execute(bookId)
    }

    fun comicArchive(
      name: String,
      color: Color = Color.RED,
    ): Path {
      val directory = Files.createDirectories(temporaryDirectory.resolve("series"))
      val archive = directory.resolve(name)
      ZipOutputStream(Files.newOutputStream(archive)).use { output ->
        output.putNextEntry(ZipEntry("001.png"))
        output.write(pngBytes(color))
        output.closeEntry()
      }
      return archive
    }

    /**
     * A comic archive whose only page has the PNG magic signature - enough for content-type
     * sniffing to index it as an image page during analysis - but no valid chunk data after it, so
     * nothing can actually decode it into pixels.
     */
    fun corruptComicArchive(name: String): Path {
      val directory = Files.createDirectories(temporaryDirectory.resolve("series"))
      val archive = directory.resolve(name)
      ZipOutputStream(Files.newOutputStream(archive)).use { output ->
        output.putNextEntry(ZipEntry("001.png"))
        output.write(PNG_SIGNATURE + ByteArray(32))
        output.closeEntry()
      }
      return archive
    }

    fun epub(
      name: String,
      declareCover: Boolean,
    ): Path {
      val path = temporaryDirectory.resolve(name)
      ZipOutputStream(Files.newOutputStream(path)).use { archive ->
        archive.entry("mimetype", "application/epub+zip".encodeToByteArray())
        archive.entry(
          "META-INF/container.xml",
          """
          <?xml version="1.0"?>
          <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles>
              <rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/>
            </rootfiles>
          </container>
          """.trimIndent().encodeToByteArray(),
        )
        val coverManifestEntry =
          if (declareCover) {
            """<item id="cover-image" href="images/cover.png" media-type="image/png" properties="cover-image"/>"""
          } else {
            ""
          }
        archive.entry(
          "OEBPS/package.opf",
          """
          <?xml version="1.0"?>
          <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>Synthetic publication</dc:title>
            </metadata>
            <manifest>
              <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
              $coverManifestEntry
            </manifest>
            <spine>
              <itemref idref="chapter1"/>
            </spine>
          </package>
          """.trimIndent().encodeToByteArray(),
        )
        archive.entry(
          "OEBPS/chapter1.xhtml",
          "<html><body>Synthetic reflowable publication text exceeds the comic threshold easily.</body></html>"
            .encodeToByteArray(),
        )
        if (declareCover) {
          archive.entry("OEBPS/images/cover.png", pngBytes(Color.GREEN))
        }
      }
      return path
    }
  }

  private fun ZipOutputStream.entry(
    name: String,
    bytes: ByteArray,
  ) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
  }

  private fun jpegBytes(color: Color): ByteArray =
    ByteArrayOutputStream().use { output ->
      val image = BufferedImage(6, 8, BufferedImage.TYPE_INT_RGB)
      image.createGraphics().also { graphics ->
        graphics.color = color
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.dispose()
      }
      check(ImageIO.write(image, "jpeg", output))
      output.toByteArray()
    }

  private fun pngBytes(color: Color): ByteArray =
    ByteArrayOutputStream().use { output ->
      val image = BufferedImage(6, 8, BufferedImage.TYPE_INT_RGB)
      image.createGraphics().also { graphics ->
        graphics.color = color
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.dispose()
      }
      check(ImageIO.write(image, "png", output))
      output.toByteArray()
    }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
    val BOOK_ID_2 = BookId("book-2")
    val PNG_SIGNATURE =
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
