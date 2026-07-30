package io.xoboro.server.metadata

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * The fields Xoboro reads from a PDF's XMP packet.
 *
 * Separate from the document information dictionary's snapshot because the two disagree in kind, not
 * only in value: XMP carries `dc:creator` and `dc:subject` as **ordered lists of items**, where the
 * dictionary carries one free-text string that has to be guessed apart. Keeping them distinct is what
 * lets [PdfMetadataProvider] prefer the structured form for exactly those two fields and the dictionary
 * for the rest.
 */
data class PdfXmpSnapshot(
  val title: String? = null,
  val description: String? = null,
  val createDate: String? = null,
  val creators: List<String> = emptyList(),
  val subjects: Set<String> = emptySet(),
) {
  val isEmpty: Boolean
    get() =
      title == null &&
        description == null &&
        createDate == null &&
        creators.isEmpty() &&
        subjects.isEmpty()
}

/**
 * Reads an XMP packet's Dublin Core and XMP basic fields.
 *
 * Parsed with jsoup's XML parser rather than by adding `org.apache.pdfbox:xmpbox`. XMP is RDF/XML, the
 * five fields below are ordinary elements in it, and jsoup is already a dependency of this module for
 * the EPUB package document. A new dependency to read five fields would have to earn itself.
 *
 * Every read is lenient. XMP is written by many producers and is routinely malformed, truncated, or
 * carries a stale template left over from an export — so a field that cannot be understood is absent
 * rather than an error. Metadata that fails to parse must never be the reason a book fails to import.
 */
object PdfXmpMetadata {
  /**
   * Language alternatives (`rdf:Alt`) are resolved to `x-default` when present, else the first entry.
   *
   * Xoboro's book metadata holds one title, so an alternative has to be chosen. `x-default` is the
   * producer's own statement of which one that is; falling back to the first is a guess, but a bounded
   * one, and a title in the wrong language beats no title.
   */
  fun parse(packet: String): PdfXmpSnapshot {
    if (packet.isBlank()) return PdfXmpSnapshot()
    val document =
      runCatching { Jsoup.parse(packet, "", Parser.xmlParser()) }
        .getOrNull() ?: return PdfXmpSnapshot()
    return PdfXmpSnapshot(
      title = document.alternative("dc|title"),
      description = document.alternative("dc|description"),
      // Only the date part is kept. Xoboro stores a release date, and an XMP timestamp carries a time
      // and offset that would be invented precision for "when was this published".
      createDate = document.firstText("xmp|CreateDate")?.take(ISO_DATE_LENGTH)?.takeIf(::isIsoDate),
      creators = document.items("dc|creator"),
      subjects = document.items("dc|subject").toSet(),
    )
  }

  private fun Document.alternative(selector: String): String? {
    val element = selectFirst(selector) ?: return null
    val items: List<Element> = element.select("rdf|li")
    if (items.isEmpty()) return element.ownText().cleaned()
    val preferred =
      items.firstOrNull { it.attr("xml:lang").equals(X_DEFAULT, ignoreCase = true) }
        ?: items.first()
    return preferred.text().cleaned()
  }

  private fun Document.items(selector: String): List<String> {
    val element = selectFirst(selector) ?: return emptyList()
    val items: List<Element> = element.select("rdf|li")
    // A producer may write a bare value instead of a container. Treated as a single item rather than
    // split on any separator: the whole point of reading XMP for these two fields is that it does not
    // require guessing where one value ends.
    if (items.isEmpty()) return listOfNotNull(element.ownText().cleaned())
    return items.mapNotNull { it.text().cleaned() }.distinct()
  }

  /**
   * Named [firstText] rather than `text` because jsoup's `Element.text(String)` is a *setter*, and a
   * member wins over an extension - `document.text(selector)` would have quietly set the document's
   * text and returned an Element.
   */
  private fun Document.firstText(selector: String): String? =
    selectFirst(selector)?.text().cleaned()

  private fun String?.cleaned(): String? = this?.trim()?.ifBlank { null }

  private fun isIsoDate(value: String): Boolean = ISO_DATE.matches(value)

  private const val X_DEFAULT = "x-default"
  private const val ISO_DATE_LENGTH = 10
  private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
}
