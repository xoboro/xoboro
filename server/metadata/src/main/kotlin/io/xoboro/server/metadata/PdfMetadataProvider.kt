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
import org.apache.pdfbox.pdmodel.PDDocumentInformation

/**
 * Reads a PDF's document information dictionary into book metadata.
 *
 * Nothing read it before, so a PDF library showed filename-derived titles even when the documents
 * carried real ones. Only book metadata is provided: a PDF has no notion of a series, so inventing
 * one from a document title would be a guess dressed up as imported data.
 *
 * XMP metadata is not read. It can carry the same fields in a richer form, but the information
 * dictionary is what a PDF practically always has, and reading both raises a precedence question
 * that no observed file has yet forced.
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
    val information =
      runCatching {
        access.materialize(library.root.itemId, book.sourceItemId).use { materialized ->
          Loader.loadPDF(materialized.path.toFile()).use { document ->
            document.documentInformation.snapshot()
          }
        }
      }.getOrNull() ?: return null
    val patch =
      BookMetadataPatch(
        title = information.title,
        summary = information.subject,
        releaseDate = information.releaseDate,
        authors = information.authors.ifEmpty { null },
        tags = information.keywords.ifEmpty { null },
      )
    // Producers stamp an empty dictionary routinely. Returning a patch of all nulls would report a
    // successful import that changed nothing, so an empty read is reported as no metadata at all.
    return patch.takeUnless { it == BookMetadataPatch() }
  }

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
