package io.xoboro.server.metadata

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadingDirection
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

  @Test
  fun `reads the reading direction from the spine`() {
    val rightToLeft = createEpub(name = "rtl.epub", spineDirection = "rtl")
    val undeclared = createEpub(name = "undeclared.epub", spineDirection = "default")

    // `page-progression-direction` is how an EPUB says it reads right to left, and nothing read it
    // before, so every imported publication silently inherited the catalog default.
    assertEquals(
      ReadingDirection.RIGHT_TO_LEFT,
      provider(rightToLeft).provide(library(), series(), listOf(book()))?.readingDirection,
    )
    // `default` declines to state a direction. Reporting LEFT_TO_RIGHT would overwrite whatever an
    // operator had set with a claim the file never made.
    assertNull(provider(undeclared).provide(library(), series(), listOf(book()))?.readingDirection)
  }

  @Test
  fun `prefers the publication date over other dated events`() {
    val epub =
      createEpub(
        name = "dated.epub",
        dates =
          """
          <dc:date opf:event="creation" xmlns:opf="http://www.idpf.org/2007/opf">1999-01-02</dc:date>
          <dc:date opf:event="publication" xmlns:opf="http://www.idpf.org/2007/opf">2025-04-03</dc:date>
          """.trimIndent(),
      )

    // Taking the first parseable date made the release date depend on the order a tool happened to
    // write the elements in - here that would have been the creation date.
    assertEquals("2025-04-03", provider(epub).provide(library(), book())?.releaseDate)
  }

  @Test
  fun `maps relator codes onto the shared role vocabulary`() {
    val epub =
      createEpub(
        name = "credited.epub",
        extraCreators =
          """
          <dc:contributor opf:role="clr" xmlns:opf="http://www.idpf.org/2007/opf">Color Hand</dc:contributor>
          <dc:contributor opf:role="cov" xmlns:opf="http://www.idpf.org/2007/opf">Cover Hand</dc:contributor>
          <dc:contributor opf:role="xyz" xmlns:opf="http://www.idpf.org/2007/opf">Unknown Hand</dc:contributor>
          """.trimIndent(),
      )

    val authors =
      provider(epub).provide(library(), book())?.authors?.associate { it.name to it.role }

    // An unmapped code reaches the catalog verbatim, which is why the mapped set matters: these two
    // used to surface as the bare relator codes "clr" and "cov" in place of a role.
    assertEquals("colorist", authors?.get("Color Hand"))
    assertEquals("cover", authors?.get("Cover Hand"))
    assertEquals("xyz", authors?.get("Unknown Hand"))
  }

  @Test
  fun `gives an undeclared creator the same role as a declared one`() {
    val epub =
      createEpub(
        name = "undeclared.epub",
        extraCreators =
          """
          <dc:creator>Undeclared Hand</dc:creator>
          <dc:contributor>Undeclared Helper</dc:contributor>
          """.trimIndent(),
      )

    val authors =
      provider(epub).provide(library(), book())?.authors?.associate { it.name to it.role }

    // The fixture's own `Primary Author` declares `aut` and resolves to "writer". An undeclared
    // creator used to fall back to the literal "author", so the same concept reached the catalog under
    // two names depending on whether the publication bothered to state the relator.
    assertEquals("writer", authors?.get("Primary Author"))
    assertEquals("writer", authors?.get("Undeclared Hand"))
    assertEquals("contributor", authors?.get("Undeclared Helper"))
  }

  @Test
  fun `resolves relator display names to the same role as their codes`() {
    val epub =
      createEpub(
        name = "display-names.epub",
        extraCreators =
          """
          <dc:contributor opf:role="Illustrator" xmlns:opf="http://www.idpf.org/2007/opf">Named Artist</dc:contributor>
          <dc:contributor opf:role="ill" xmlns:opf="http://www.idpf.org/2007/opf">Coded Artist</dc:contributor>
          <dc:contributor opf:role="Cover Artist" xmlns:opf="http://www.idpf.org/2007/opf">Named Cover</dc:contributor>
          <dc:contributor opf:role="colourist" xmlns:opf="http://www.idpf.org/2007/opf">British Colour</dc:contributor>
          """.trimIndent(),
      )

    val authors =
      provider(epub).provide(library(), book())?.authors?.associate { it.name to it.role }

    // EPUB 3 says `role` carries a code, but producers write the display name often enough that
    // treating it as unmapped would surface "Illustrator" beside "penciller" for the same credit.
    assertEquals("penciller", authors?.get("Named Artist"))
    assertEquals("penciller", authors?.get("Coded Artist"))
    assertEquals("cover", authors?.get("Named Cover"))
    assertEquals("colorist", authors?.get("British Colour"))
  }

  @Test
  fun `maps lithographer and leaves the letterer lookalike unmapped`() {
    val epub =
      createEpub(
        name = "lithography.epub",
        extraCreators =
          """
          <dc:contributor opf:role="ltg" xmlns:opf="http://www.idpf.org/2007/opf">Stone Hand</dc:contributor>
          <dc:contributor opf:role="ltr" xmlns:opf="http://www.idpf.org/2007/opf">Lookalike Hand</dc:contributor>
          """.trimIndent(),
      )

    val authors =
      provider(epub).provide(library(), book())?.authors?.associate { it.name to it.role }

    // `ltg` is the MARC code for Lithographer.
    assertEquals("lithographer", authors?.get("Stone Hand"))
    // `ltr` looks like an abbreviation of "letterer" and mapping it there is the obvious mistake.
    // MARC has no relator for letterer at all, so guessing would mislabel whatever `ltr` credits.
    // This pins the absence so a later reader does not "fix" it.
    assertEquals("ltr", authors?.get("Lookalike Hand"))
  }

  @Test
  fun `keeps a role it cannot resolve rather than dropping the credit`() {
    val epub =
      createEpub(
        name = "unresolvable.epub",
        extraCreators =
          """
          <dc:contributor opf:role="zzz" xmlns:opf="http://www.idpf.org/2007/opf">Unknown Hand</dc:contributor>
          <dc:contributor opf:role="writer" xmlns:opf="http://www.idpf.org/2007/opf">Already Named</dc:contributor>
          """.trimIndent(),
      )

    val authors =
      provider(epub).provide(library(), book())?.authors?.associate { it.name to it.role }

    // A role nobody mapped is still something the publication asserted; losing it would be worse than
    // showing it raw. A value that is already a vocabulary name passes through as itself.
    assertEquals("zzz", authors?.get("Unknown Hand"))
    assertEquals("writer", authors?.get("Already Named"))
  }

  @Test
  fun `uses the collection sort form for the series title sort`() {
    val epub = createEpub(name = "sorted.epub", seriesSortForm = "Synthetic saga, The")

    val patch = provider(epub).provide(library(), series(), listOf(book()))

    assertEquals("Synthetic saga", patch?.title)
    assertEquals("Synthetic saga, The", patch?.titleSort)
  }

  private fun provider(epub: Path): EpubMetadataProvider =
    EpubMetadataProvider(listOf(FixedAccess(epub)))

  private fun createEpub(
    name: String = "synthetic.epub",
    spineDirection: String? = null,
    dates: String = "<dc:date>2025-04-03T10:15:30+00:00</dc:date>",
    seriesSortForm: String? = null,
    extraCreators: String = "",
  ): Path {
    val path = temporaryDirectory.resolve(name)
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
          $extraCreators
          <dc:description>A bounded metadata fixture.</dc:description>
          <dc:publisher>Fiction House</dc:publisher>
          <dc:language>en-US</dc:language>
          $dates
          <dc:subject>Adventure</dc:subject>
          <dc:subject>Mystery</dc:subject>
          <meta id="series" property="belongs-to-collection">Synthetic saga</meta>
          <meta refines="#series" property="collection-type">series</meta>
          <meta refines="#series" property="group-position">2.5</meta>
          ${seriesSortForm?.let { "<meta refines=\"#series\" property=\"file-as\">$it</meta>" }.orEmpty()}
          <link rel="record" href="https://example.invalid/metadata"/>
        </metadata>
        <manifest>
          <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
        </manifest>
        <spine${spineDirection?.let { " page-progression-direction=\"$it\"" }.orEmpty()}><itemref idref="chapter"/></spine>
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
