package io.xoboro.server.metadata

import io.xoboro.core.application.BookMetadataPatch
import io.xoboro.core.application.BookMetadataProvider
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.MediaKind
import io.xoboro.server.media.SourceMediaAccess
import io.xoboro.server.media.UnknownSourceMediaAccessException
import java.util.Calendar
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation

/**
 * Reads a PDF's document information dictionary into book metadata.
 *
 * Nothing read it before, so a PDF library showed filename-derived titles even when the documents
 * carried real ones. Only book metadata is provided: a PDF has no notion of a series, so inventing
 * one from a document title would be a guess dressed up as imported data.
 *
 * Both the information dictionary and the XMP packet are read, with **per-field precedence** rather
 * than one source winning outright. The two disagree in kind, not only in value:
 *
 * - For `authors` and `tags`, XMP wins when it has them. `dc:creator` and `dc:subject` are ordered
 *   lists of items, while the dictionary carries one free-text string that has to be guessed apart -
 *   so preferring XMP for these two removes the documented `"Doe, Jane"` splitting error rather than
 *   choosing between two equally good values.
 * - For every other field the dictionary wins and XMP fills gaps. The dictionary is what a producer
 *   most recently touched in the common case, XMP is routinely a stale template left from an export,
 *   and gap-filling cannot change a value a library has already imported.
 *
 * `dc:language` is read by nothing: [BookMetadataPatch] has no language field, because language in
 * this catalog belongs to a series and a PDF has no series. Storing it nowhere is better than
 * inventing a series to hold it.
 */
class PdfMetadataProvider(
  accesses: Collection<SourceMediaAccess>,
) : BookMetadataProvider {
  private val accessesBySourceId = accesses.associateBy(SourceMediaAccess::sourceId)

  init {
    require(accesses.none { it.sourceId.isBlank() }) { "Media source IDs must not be blank" }
    require(accessesBySourceId.size == accesses.size) { "Media source IDs must be unique" }
  }

  override fun provide(
    library: Library,
    book: Book,
  ): BookMetadataPatch? {
    if (!library.settings.importPdfBook || book.mediaKind != MediaKind.PDF) return null
    val access =
      accessesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceMediaAccessException(library.root.sourceId)
    // A document needing a user password throws here, which is the correct outcome: unreadable
    // metadata is absent metadata, and the encryption itself is reported by the media analyzer.
    val read =
      runCatching {
        access.materialize(library.root.itemId, book.sourceItemId).use { materialized ->
          Loader.loadPDF(materialized.path.toFile()).use { document ->
            document.documentInformation.snapshot() to document.xmpSnapshot()
          }
        }
      }.getOrNull() ?: return null
    val (information, xmp) = read
    val patch =
      BookMetadataPatch(
        title = information.title ?: xmp.title,
        summary = information.subject ?: xmp.description,
        releaseDate = information.releaseDate ?: xmp.createDate,
        // XMP first for these two: a structured list needs no guessing, and the dictionary's free-text
        // form is split heuristically.
        authors =
          xmp.creators
            .map { Author(it, WRITER_ROLE) }
            .ifEmpty { information.authors }
            .ifEmpty { null },
        tags = xmp.subjects.ifEmpty { information.keywords }.ifEmpty { null },
      )
    // Producers stamp an empty dictionary routinely. Returning a patch of all nulls would report a
    // successful import that changed nothing, so an empty read is reported as no metadata at all.
    return patch.takeUnless { it == BookMetadataPatch() }
  }

  /**
   * Reads the XMP packet, or an empty snapshot when there is none or it cannot be understood.
   *
   * Failure is absence, not an error: a malformed XMP packet is common and must never be the reason a
   * PDF's dictionary metadata goes unimported.
   */
  private fun PDDocument.xmpSnapshot(): PdfXmpSnapshot =
    runCatching {
      documentCatalog?.metadata?.exportXMPMetadata()?.use { stream ->
        PdfXmpMetadata.parse(stream.readBytes().decodeToString())
      }
    }.getOrNull() ?: PdfXmpSnapshot()

  private fun PDDocumentInformation.snapshot(): DocumentInformation =
    DocumentInformation(
      title = title.textValue(),
      subject = subject.textValue(),
      releaseDate =
        runCatching { creationDate?.isoDate() }.getOrNull(),
      authors = author.textValue()?.let(::splitNames).orEmpty().map { Author(it, WRITER_ROLE) },
      keywords = keywords.textValue()?.let(::splitKeywords).orEmpty(),
    )

  /**
   * Splits an `Author` entry into people.
   *
   * The field is a single free-text string with no agreed separator, so this handles the forms that
   * actually occur - semicolons, commas, and " and ". A name containing a comma as part of itself
   * ("Doe, Jane") is therefore split wrongly; that is the cost of reading a field with no structure,
   * and a single unsplit name is preferred to no author at all.
   */
  private fun splitNames(value: String): List<String> =
    value
      .split(';', '&')
      .flatMap { part -> part.split(AND_SEPARATOR) }
      .flatMap { part -> if (part.count { it == ',' } > 1) part.split(',') else listOf(part) }
      .mapNotNull { it.trim().ifBlank { null } }
      .distinct()

  private fun splitKeywords(value: String): Set<String> =
    value
      .split(',', ';', '\n')
      .mapNotNull { it.trim().ifBlank { null } }
      .toSet()

  private fun String?.textValue(): String? = this?.trim()?.ifBlank { null }

  private fun Calendar.isoDate(): String =
    "%04d-%02d-%02d".format(
      get(Calendar.YEAR),
      get(Calendar.MONTH) + 1,
      get(Calendar.DAY_OF_MONTH),
    )

  private data class DocumentInformation(
    val title: String?,
    val subject: String?,
    val releaseDate: String?,
    val authors: List<Author>,
    val keywords: Set<String>,
  )

  private companion object {
    const val WRITER_ROLE = "writer"
    val AND_SEPARATOR = Regex("""\s+and\s+""", RegexOption.IGNORE_CASE)
  }
}
