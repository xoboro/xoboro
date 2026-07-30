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

  private fun provider(pdf: Path): PdfMetadataProvider =
    PdfMetadataProvider(listOf(FixedAccess(pdf)))

  private fun createPdf(
    name: String,
    describe: (org.apache.pdfbox.pdmodel.PDDocumentInformation) -> Unit,
  ): Path {
    val path = temporaryDirectory.resolve(name)
    PDDocument().use { document ->
      document.addPage(PDPage())
      describe(document.documentInformation)
      document.save(path.toFile())
    }
    return path
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
