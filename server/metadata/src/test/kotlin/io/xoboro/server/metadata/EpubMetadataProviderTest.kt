package io.xoboro.server.metadata

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.MaterializedMedia
import io.xoboro.server.media.SourceMediaAccess
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class EpubMetadataProviderTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `imports EPUB package metadata for books and series`() {
    val epub = createEpub()
    val provider = EpubMetadataProvider(listOf(FixedAccess(epub)))
    val library = library()
    val book = book()

    val bookPatch = requireNotNull(provider.provide(library, book))
    val seriesPatch = requireNotNull(provider.provide(library, series(), listOf(book)))

    assertEquals("Synthetic volume", bookPatch.title)
    assertEquals("A bounded metadata fixture.", bookPatch.summary)
    assertEquals("2.5", bookPatch.number)
    assertEquals(2.5F, bookPatch.numberSort)
    assertEquals("2025-04-03", bookPatch.releaseDate)
    assertEquals(
      listOf("Primary Author" to "writer", "Line Artist" to "penciller"),
      bookPatch.authors?.map { it.name to it.role },
    )
    assertEquals(setOf("Adventure", "Mystery"), bookPatch.tags)
    assertEquals("9780306406157", bookPatch.isbn)
    assertEquals("Synthetic saga", seriesPatch.title)
    assertEquals("Fiction House", seriesPatch.publisher)
    assertEquals("en-US", seriesPatch.language)
    assertEquals(setOf("Adventure", "Mystery"), seriesPatch.genres)
  }

  @Test
  fun `honors EPUB import settings and media kind`() {
    val epub = createEpub()
    val provider = EpubMetadataProvider(listOf(FixedAccess(epub)))

    assertNull(
      provider.provide(
        library(LibrarySettings(importEpubBook = false)),
        book(),
      ),
    )
    assertNull(
      provider.provide(
        library(),
        book().copy(mediaKind = MediaKind.PDF),
      ),
    )
    assertNull(
      provider.provide(
        library(LibrarySettings(importEpubSeries = false)),
        series(),
        listOf(book()),
      ),
    )
  }

  private fun createEpub(): Path {
    val path = temporaryDirectory.resolve("synthetic.epub")
    val container =
      """
      <?xml version="1.0"?>
      <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
        <rootfiles>
          <rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/>
        </rootfiles>
      </container>
      """.trimIndent()
    val packageDocument =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <package xmlns="http://www.idpf.org/2007/opf"
               xmlns:dc="http://purl.org/dc/elements/1.1/"
               unique-identifier="book-id" version="3.0">
        <metadata>
          <dc:identifier id="book-id">urn:uuid:synthetic</dc:identifier>
          <dc:identifier id="isbn">urn:isbn:9780306406157</dc:identifier>
          <meta refines="#isbn" property="identifier-type">isbn</meta>
          <dc:title id="main-title">Synthetic volume</dc:title>
          <meta refines="#main-title" property="title-type">main</meta>
          <dc:creator id="author">Primary Author</dc:creator>
          <meta refines="#author" property="role">aut</meta>
          <dc:contributor opf:role="ill" xmlns:opf="http://www.idpf.org/2007/opf">Line Artist</dc:contributor>
          <dc:description>A bounded metadata fixture.</dc:description>
          <dc:publisher>Fiction House</dc:publisher>
          <dc:language>en-US</dc:language>
          <dc:date>2025-04-03T10:15:30+00:00</dc:date>
          <dc:subject>Adventure</dc:subject>
          <dc:subject>Mystery</dc:subject>
          <meta id="series" property="belongs-to-collection">Synthetic saga</meta>
          <meta refines="#series" property="collection-type">series</meta>
          <meta refines="#series" property="group-position">2.5</meta>
          <link rel="record" href="https://example.invalid/metadata"/>
        </metadata>
        <manifest>
          <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
        </manifest>
        <spine><itemref idref="chapter"/></spine>
      </package>
      """.trimIndent()
    ZipOutputStream(Files.newOutputStream(path)).use { archive ->
      archive.entry("mimetype", "application/epub+zip")
      archive.entry("META-INF/container.xml", container)
      archive.entry("OPS/package.opf", packageDocument)
      archive.entry("OPS/chapter.xhtml", "<html><body>Synthetic</body></html>")
    }
    return path
  }

  private fun ZipOutputStream.entry(
    name: String,
    value: String,
  ) {
    putNextEntry(ZipEntry(name))
    write(value.encodeToByteArray())
    closeEntry()
  }

  private fun library(settings: LibrarySettings = LibrarySettings()): Library =
    Library(
      id = LIBRARY_ID,
      name = "Synthetic library",
      root = SourceLocation("fixed", "fixed://root"),
      settings = settings,
      createdAtMillis = 1,
    )

  private fun book(): Book =
    Book(
      id = BOOK_ID,
      libraryId = LIBRARY_ID,
      seriesId = SERIES_ID,
      name = "Fallback volume",
      relativePath = "Synthetic saga/volume.epub",
      sourceItemId = "fixed://volume",
      mediaKind = MediaKind.EPUB,
      fileModifiedAtMillis = 1,
      createdAtMillis = 1,
    )

  private fun series(): Series =
    Series(
      id = SERIES_ID,
      libraryId = LIBRARY_ID,
      name = "Fallback saga",
      relativePath = "Synthetic saga",
      sourceItemId = "fixed://series",
      fileModifiedAtMillis = 1,
      createdAtMillis = 1,
    )

  private class FixedAccess(
    private val path: Path,
  ) : SourceMediaAccess {
    override val sourceId = "fixed"

    override fun materialize(
      rootItemId: String,
      itemId: String,
    ): MaterializedMedia =
      object : MaterializedMedia {
        override val path: Path = this@FixedAccess.path
        override fun close() = Unit
      }
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
  }
}
