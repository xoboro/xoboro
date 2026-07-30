package io.xoboro.server.metadata

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.MaterializedMedia
import io.xoboro.server.media.SourceMediaAccess
import java.nio.file.Path
import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.io.TempDir

class PdfMetadataProviderTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `imports the document information dictionary`() {
    val pdf =
      createPdf("titled.pdf") { information ->
        information.title = "  Synthetic document  "
        information.subject = "A bounded metadata fixture."
        information.author = "Primary Author; Second Author and Third Author"
        information.keywords = "Adventure, Mystery,  ,Adventure"
        information.creationDate = GregorianCalendar(2025, Calendar.APRIL, 3, 10, 15, 30)
      }

    val patch = requireNotNull(provider(pdf).provide(library(), book()))

    assertEquals("Synthetic document", patch.title)
    assertEquals("A bounded metadata fixture.", patch.summary)
    assertEquals("2025-04-03", patch.releaseDate)
    assertEquals(
      listOf("Primary Author", "Second Author", "Third Author"),
      patch.authors?.map { it.name },
    )
    assertEquals(listOf("writer"), patch.authors?.map { it.role }?.distinct())
    assertEquals(setOf("Adventure", "Mystery"), patch.tags)
  }

  /**
   * Producers stamp an empty dictionary routinely. A patch of all nulls would report an import that
   * changed nothing, and would keep the metadata refresh reporting work it did not do.
   */
  @Test
  fun `reports no metadata when the dictionary is empty`() {
    val pdf = createPdf("bare.pdf") { }

    assertNull(provider(pdf).provide(library(), book()))
  }

  @Test
  fun `honors the import setting and media kind`() {
    val pdf = createPdf("titled.pdf") { it.title = "Synthetic document" }

    assertNull(
      provider(pdf).provide(library(LibrarySettings(importPdfBook = false)), book()),
    )
    assertNull(provider(pdf).provide(library(), book().copy(mediaKind = MediaKind.EPUB)))
  }

  /**
   * A document needing a user password cannot be opened, and unreadable metadata is absent metadata.
   * The encryption itself is reported by the media analyzer, not here (ADR 0089).
   */
  @Test
  fun `reports no metadata for a document it cannot open`() {
    val pdf = temporaryDirectory.resolve("locked.pdf")
    PDDocument().use { document ->
      document.addPage(PDPage())
      document.documentInformation.title = "Synthetic document"
      document.protect(
        StandardProtectionPolicy("synthetic-owner", "synthetic-user", AccessPermission()),
      )
      document.save(pdf.toFile())
    }

    assertNull(provider(pdf).provide(library(), book()))
  }

  @Test
  fun `lets the dictionary win and XMP fill the gaps`() {
    val pdf =
      createPdf(
        name = "both.pdf",
        xmp =
          xmpPacket(
            title = "XMP Title",
            description = "XMP description",
            createDate = "2001-01-01",
          ),
      ) { information ->
        information.title = "Dictionary Title"
        // No subject and no creation date, so those two come from XMP.
      }

    val patch = requireNotNull(provider(pdf).provide(library(), book()))

    // The dictionary is what a producer most recently touched in the common case, and gap-filling
    // cannot change a value a library already imported.
    assertEquals("Dictionary Title", patch.title)
    assertEquals("XMP description", patch.summary)
    assertEquals("2001-01-01", patch.releaseDate)
  }

  @Test
  fun `prefers the structured XMP creator list over the dictionary's free text`() {
    val pdf =
      createPdf(
        name = "creators.pdf",
        xmp = xmpPacket(creators = listOf("Doe, Jane", "Roe, John"), subjects = listOf("xmp-tag")),
      ) { information ->
        // Comma-separated, which the dictionary's heuristic splits into four people because it cannot
        // tell a surname comma from a separator. The fixture has to be a value the dictionary gets
        // *wrong*, or the assertion below would pass whichever source won.
        information.author = "Doe, Jane, Roe, John"
        information.keywords = "dictionary-tag"
      }

    val patch = requireNotNull(provider(pdf).provide(library(), book()))

    // XMP carries the names as separate items, so preferring it removes a documented error rather than
    // choosing between two equally good values.
    assertEquals(listOf("Doe, Jane", "Roe, John"), patch.authors?.map { it.name })
    assertEquals(listOf("writer"), patch.authors?.map { it.role }?.distinct())
    assertEquals(setOf("xmp-tag"), patch.tags)
  }

  @Test
  fun `falls back to the dictionary when XMP carries no creators`() {
    val pdf =
      createPdf(name = "dictionary-only.pdf", xmp = xmpPacket(creators = emptyList())) { information ->
        information.author = "Primary Author; Second Author"
      }

    val patch = requireNotNull(provider(pdf).provide(library(), book()))

    assertEquals(listOf("Primary Author", "Second Author"), patch.authors?.map { it.name })
  }

  @Test
  fun `imports XMP alone when the dictionary is empty`() {
    val pdf = createPdf(name = "xmp-only.pdf", xmp = xmpPacket(title = "XMP Title")) { }

    val patch = requireNotNull(provider(pdf).provide(library(), book()))

    assertEquals("XMP Title", patch.title)
  }

  @Test
  fun `reports no metadata when a malformed XMP packet is the only source`() {
    val pdf = createPdf(name = "broken-xmp.pdf", xmp = "<x:xmpmeta><rdf:RDF><rdf:Desc") { }

    // A malformed packet is absence, not an error: it must never be the reason an import fails.
    assertNull(provider(pdf).provide(library(), book()))
  }

  private fun provider(pdf: Path): PdfMetadataProvider =
    PdfMetadataProvider(listOf(FixedAccess(pdf)))

  private fun createPdf(
    name: String,
    xmp: String? = null,
    describe: (org.apache.pdfbox.pdmodel.PDDocumentInformation) -> Unit,
  ): Path {
    val path = temporaryDirectory.resolve(name)
    PDDocument().use { document ->
      document.addPage(PDPage())
      describe(document.documentInformation)
      xmp?.let { packet ->
        document.documentCatalog.metadata =
          PDMetadata(document, packet.byteInputStream())
      }
      document.save(path.toFile())
    }
    return path
  }

  private fun xmpPacket(
    title: String? = null,
    description: String? = null,
    createDate: String? = null,
    creators: List<String> = emptyList(),
    subjects: List<String> = emptyList(),
  ): String =
    buildString {
      append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
      append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"")
      append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
      append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"><rdf:Description>")
      title?.let {
        append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">$it</rdf:li></rdf:Alt></dc:title>")
      }
      description?.let {
        append(
          "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">$it</rdf:li></rdf:Alt>" +
            "</dc:description>",
        )
      }
      createDate?.let { append("<xmp:CreateDate>$it</xmp:CreateDate>") }
      if (creators.isNotEmpty()) {
        append("<dc:creator><rdf:Seq>")
        creators.forEach { append("<rdf:li>$it</rdf:li>") }
        append("</rdf:Seq></dc:creator>")
      }
      if (subjects.isNotEmpty()) {
        append("<dc:subject><rdf:Bag>")
        subjects.forEach { append("<rdf:li>$it</rdf:li>") }
        append("</rdf:Bag></dc:subject>")
      }
      append("</rdf:Description></rdf:RDF></x:xmpmeta>")
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
      relativePath = "Synthetic saga/volume.pdf",
      sourceItemId = "fixed://volume",
      mediaKind = MediaKind.PDF,
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
