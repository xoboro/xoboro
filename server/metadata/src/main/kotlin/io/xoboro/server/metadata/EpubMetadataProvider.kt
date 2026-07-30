package io.xoboro.server.metadata

import io.xoboro.core.application.BookMetadataPatch
import io.xoboro.core.application.BookMetadataProvider
import io.xoboro.core.application.SeriesMetadataPatch
import io.xoboro.core.application.SeriesMetadataProvider
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.Series
import io.xoboro.server.media.SourceMediaAccess
import io.xoboro.server.media.UnknownSourceMediaAccessException
import java.net.URI
import java.nio.file.Path
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Elements

class EpubMetadataProvider(
  accesses: Collection<SourceMediaAccess>,
) : BookMetadataProvider,
  SeriesMetadataProvider {
  private val accessesBySourceId = accesses.associateBy(SourceMediaAccess::sourceId)

  init {
    require(accesses.none { it.sourceId.isBlank() }) { "Media source IDs must not be blank" }
    require(accessesBySourceId.size == accesses.size) { "Media source IDs must be unique" }
  }

  override fun provide(
    library: Library,
    book: Book,
  ): BookMetadataPatch? {
    if (!library.settings.importEpubBook || book.mediaKind != MediaKind.EPUB) return null
    val metadata = readMetadata(library, book) ?: return null
    return BookMetadataPatch(
      title = metadata.title,
      summary = metadata.description,
      number = metadata.seriesPosition,
      numberSort = metadata.seriesPosition?.toFloatOrNull(),
      releaseDate = metadata.releaseDate,
      authors = metadata.authors.ifEmpty { null },
      tags = metadata.subjects.ifEmpty { null },
      isbn = metadata.isbn,
      links = metadata.links.ifEmpty { null },
    )
  }

  override fun provide(
    library: Library,
    series: Series,
    books: List<Book>,
  ): SeriesMetadataPatch? {
    if (!library.settings.importEpubSeries) return null
    val metadata =
      books
        .asSequence()
        .filter { it.mediaKind == MediaKind.EPUB }
        .sortedWith(compareBy<Book> { it.number }.thenBy(Book::relativePath))
        .mapNotNull { readMetadata(library, it) }
        .firstOrNull()
        ?: return null
    return SeriesMetadataPatch(
      title = metadata.seriesTitle,
      titleSort = metadata.seriesTitleSort ?: metadata.seriesTitle,
      summary = metadata.description,
      readingDirection = metadata.readingDirection,
      publisher = metadata.publisher,
      language = metadata.language,
      genres = metadata.subjects.ifEmpty { null },
    )
  }

  private fun readMetadata(
    library: Library,
    book: Book,
  ): EpubMetadata? {
    val access =
      accessesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceMediaAccessException(library.root.sourceId)
    return runCatching {
      access.materialize(library.root.itemId, book.sourceItemId).use { materialized ->
        ZipFile(materialized.path.toFile()).use { archive ->
          val mimetype = archive.getEntry(MIMETYPE_PATH) ?: return null
          val declaredType =
            archive.getInputStream(mimetype).use { input ->
              input.readNBytes(MAXIMUM_MIMETYPE_BYTES + 1).also {
                require(it.size <= MAXIMUM_MIMETYPE_BYTES) {
                  "EPUB mimetype exceeds the byte limit"
                }
              }.decodeToString().trim()
            }
          if (declaredType != EPUB_MEDIA_TYPE) return null
          val container = archive.readXml(CONTAINER_PATH)
          val packagePath =
            container
              .selectFirst("*|rootfile[full-path]")
              ?.attr("full-path")
              ?.safeArchivePath()
              ?: return null
          archive.readXml(packagePath).toMetadata()
        }
      }
    }.getOrNull()
  }

  private fun ZipFile.readXml(path: String): Document {
    val entry = getEntry(path) ?: error("EPUB metadata entry is missing: $path")
    require(!entry.isDirectory) { "EPUB metadata entry must not be a directory" }
    require(entry.size < 0 || entry.size <= MAXIMUM_METADATA_BYTES) {
      "EPUB metadata entry exceeds the byte limit"
    }
    val bytes =
      getInputStream(entry).use { input ->
        input.readNBytes(MAXIMUM_METADATA_BYTES + 1).also {
          require(it.size <= MAXIMUM_METADATA_BYTES) {
            "EPUB metadata entry exceeds the byte limit"
          }
        }
      }
    return Jsoup.parse(bytes.decodeToString(), "", Parser.xmlParser())
  }

  private fun Document.toMetadata(): EpubMetadata {
    val metadata = selectFirst("*|metadata") ?: error("EPUB package metadata is missing")
    val refinements =
      metadata
        .select("*|meta[refines]")
        .groupBy { it.attr("refines").removePrefix("#") }
    fun Element.refinement(property: String): String? =
      id()
        .takeIf(String::isNotBlank)
        ?.let(refinements::get)
        ?.firstOrNull { it.attr("property").equals(property, ignoreCase = true) }
        ?.text()
        ?.trim()
        ?.ifBlank { null }

    // EPUB 3 states a sort form as a `file-as` refinement; EPUB 2 used an `opf:file-as` attribute.
    // Both mean the same thing and publications in the wild use either, so both are read.
    fun Element.sortForm(): String? =
      refinement(FILE_AS_PROPERTY) ?: attr("opf:file-as").trim().ifBlank { null }
    val titles = metadata.select("*|title")
    val title =
      titles
        .firstOrNull { it.refinement("title-type")?.equals("main", ignoreCase = true) == true }
        ?.textValue()
        ?: titles.firstNotNullOfOrNull { it.textValue() }
    val seriesMeta =
      metadata
        .select("*|meta[property=belongs-to-collection]")
        .firstOrNull { candidate ->
          candidate.refinement("collection-type")?.equals("series", ignoreCase = true) != false
        }
    val legacySeries =
      metadata
        .selectFirst("*|meta[name=calibre:series]")
        ?.attr("content")
        ?.trim()
        ?.ifBlank { null }
    val seriesTitle = seriesMeta?.textValue() ?: legacySeries
    val seriesPosition =
      seriesMeta?.refinement("group-position")
        ?: metadata
          .selectFirst("*|meta[name=calibre:series_index]")
          ?.attr("content")
          ?.trim()
          ?.ifBlank { null }
    val authors =
      (metadata.select("*|creator") + metadata.select("*|contributor"))
        .mapNotNull { creator ->
          val name = creator.textValue() ?: return@mapNotNull null
          val role =
            creator.attr("opf:role").trim().ifBlank {
              creator.refinement("role").orEmpty()
            }.ifBlank {
              // Relator codes rather than display words. This used to be the literal "author", which
              // no ROLE_NAMES key matched - so an undeclared `dc:creator` reached the catalog as
              // "author" while a declared `aut` on the next line reached it as "writer".
              //
              // What actually fixes that is ROLE_DISPLAY_NAMES, which now resolves "author" too. This
              // line is the smaller point: the fallback takes the same path as a declared value
              // instead of depending on "author" also happening to be a display name nobody chose to
              // support for this purpose.
              if (creator.normalName().endsWith("creator")) "aut" else "ctb"
            }
          Author(name, role.toRoleName())
        }.distinctBy { it.normalizedName to it.normalizedRole }
    val identifiers = metadata.select("*|identifier")
    val isbn =
      identifiers
        .asSequence()
        .mapNotNull { identifier ->
          val scheme =
            identifier.attr("opf:scheme").trim().ifBlank {
              identifier.refinement("identifier-type").orEmpty()
            }
          val value = identifier.textValue() ?: return@mapNotNull null
          value.takeIf {
            scheme.contains("isbn", ignoreCase = true) ||
              value.startsWith("urn:isbn:", ignoreCase = true)
          }
        }.map { it.removePrefixIgnoreCase("urn:isbn:").filter(Char::isLetterOrDigit).uppercase() }
        .firstOrNull { it.isValidIsbn() }
    val links =
      metadata
        .select("*|link[href]")
        .mapNotNull { link ->
          val href = link.attr("href").trim()
          runCatching {
            val uri = URI(href)
            require(uri.isAbsolute)
            io.xoboro.core.domain.WebLink(
              label = link.attr("rel").trim().ifBlank { uri.host ?: uri.scheme },
              url = uri.toString(),
            )
          }.getOrNull()
        }
    return EpubMetadata(
      title = title,
      description = metadata.selectFirst("*|description")?.textValue(),
      releaseDate = metadata.select("*|date").publicationDate(),
      authors = authors,
      subjects = metadata.select("*|subject").mapNotNull { it.textValue() }.toSet(),
      isbn = isbn,
      links = links,
      seriesTitle = seriesTitle,
      seriesTitleSort = seriesMeta?.sortForm(),
      seriesPosition = seriesPosition,
      publisher = metadata.selectFirst("*|publisher")?.textValue(),
      language = metadata.selectFirst("*|language")?.textValue()?.normalizedLanguage(),
      readingDirection =
        selectFirst("*|spine[page-progression-direction]")
          ?.attr("page-progression-direction")
          ?.readingDirection(),
    )
  }

  /**
   * Picks the date that means "published".
   *
   * EPUB 2 distinguished several kinds of `dc:date` through an `opf:event` attribute, so a
   * publication holding both a creation and a publication date lists them in arbitrary order. Taking
   * the first parseable one made the release date depend on how the file happened to be written.
   * EPUB 3 dropped `opf:event` and allows a single `dc:date`, which the untagged fallback covers.
   */
  private fun Elements.publicationDate(): String? {
    fun dateFor(event: String?): String? =
      asSequence()
        .filter { element ->
          val declared = element.attr("opf:event").trim().ifBlank { null }
          if (event == null) declared == null else declared.equals(event, ignoreCase = true)
        }.firstNotNullOfOrNull { it.textValue()?.isoDate() }
    return dateFor("publication")
      ?: dateFor("original-publication")
      ?: dateFor(null)
      ?: firstNotNullOfOrNull { it.textValue()?.isoDate() }
  }

  /**
   * Maps the EPUB spine's reading order onto the catalog's.
   *
   * `page-progression-direction` is how an EPUB says it reads right to left - the manga case - and
   * nothing read it before, so every imported publication inherited the default. `default` means the
   * publication declines to state a direction, which is not the same as claiming left to right, so it
   * leaves the field alone rather than overwriting a value an operator set.
   */
  private fun String.readingDirection(): ReadingDirection? =
    when (trim().lowercase()) {
      "rtl" -> ReadingDirection.RIGHT_TO_LEFT
      "ltr" -> ReadingDirection.LEFT_TO_RIGHT
      else -> null
    }

  private fun Element.textValue(): String? = text().trim().ifBlank { null }

  private fun String.safeArchivePath(): String {
    val candidate = replace('\\', '/')
    val path = Path.of(candidate).normalize()
    require(!path.isAbsolute && path.none { it.toString() == ".." }) {
      "EPUB metadata path must remain inside the archive"
    }
    return path.iterator().asSequence().joinToString("/") { it.toString() }
  }

  private fun String.isoDate(): String? {
    val candidate = trim()
    return try {
      LocalDate.parse(candidate).toString()
    } catch (_: DateTimeParseException) {
      try {
        OffsetDateTime.parse(candidate).toLocalDate().toString()
      } catch (_: DateTimeParseException) {
        YEAR.find(candidate)?.value?.let { "$it-01-01" }
      }
    }
  }

  private fun String.normalizedLanguage(): String? {
    val value = Locale.forLanguageTag(this).toLanguageTag()
    return value.takeUnless { it.equals("und", ignoreCase = true) }
  }

  private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

  private fun String.isValidIsbn(): Boolean =
    when (length) {
      10 ->
        take(9).all(Char::isDigit) &&
          last().let { it.isDigit() || it == 'X' } &&
          mapIndexed { index, character ->
            (if (character == 'X') 10 else character.digitToInt()) * (10 - index)
          }.sum() % 11 == 0
      13 ->
        all(Char::isDigit) &&
          mapIndexed { index, character ->
            character.digitToInt() * if (index % 2 == 0) 1 else 3
          }.sum() % 10 == 0
      else -> false
    }

  private data class EpubMetadata(
    val title: String?,
    val description: String?,
    val releaseDate: String?,
    val authors: List<Author>,
    val subjects: Set<String>,
    val isbn: String?,
    val links: List<io.xoboro.core.domain.WebLink>,
    val seriesTitle: String?,
    val seriesTitleSort: String?,
    val seriesPosition: String?,
    val publisher: String?,
    val language: String?,
    val readingDirection: ReadingDirection?,
  )

  /**
   * Resolves a declared role to the shared vocabulary, trying the relator code, then its English name.
   *
   * An unresolvable value is returned trimmed and verbatim rather than dropped: a role nobody mapped is
   * still information the publication asserted, and losing it would be worse than showing it raw.
   */
  private fun String.toRoleName(): String {
    val normalized = trim().lowercase()
    ROLE_NAMES[normalized]?.let { return it }
    ROLE_DISPLAY_NAMES[normalized]?.let { return it }
    // A value that is already a vocabulary name - "writer", "colorist" - passes through as itself.
    if (normalized in ROLE_NAMES.values) return normalized
    return trim()
  }

  private companion object {
    const val EPUB_MEDIA_TYPE = "application/epub+zip"
    const val MIMETYPE_PATH = "mimetype"
    const val CONTAINER_PATH = "META-INF/container.xml"
    const val MAXIMUM_MIMETYPE_BYTES = 128
    const val MAXIMUM_METADATA_BYTES = 4 * 1_024 * 1_024
    const val FILE_AS_PROPERTY = "file-as"
    val YEAR = Regex("""\b\d{4}\b""")

    /**
     * MARC relator codes mapped onto the role vocabulary `ComicInfoMetadataProvider` produces, so one
     * concept does not reach the catalog under two names depending on which file it came from.
     *
     * An unmapped code passes through verbatim, which is why the map matters: before it was extended,
     * a publication crediting `art` or `clr` surfaced the bare relator code as the author's role.
     *
     * `inker` and `letterer` have no counterpart here on purpose: MARC defines no relator for either,
     * so both arrive only from ComicInfo. In particular **`ltr` is deliberately unmapped.** It looks
     * like an abbreviation of "letterer" and mapping it there is the obvious mistake to make; the MARC
     * code for Lithographer is `ltg`, which is mapped. Guessing at `ltr` would mislabel whatever it
     * actually credits, so it passes through verbatim and a test pins that.
     *
     * Where MARC and ComicInfo agree on a concept the ComicInfo name wins, because that is the name
     * the rest of the catalog already uses. Where MARC has a credit ComicInfo does not, the relator's
     * own name is used in lowercase rather than being folded into a near-neighbour: an arranger is not
     * a writer, and crediting one as the other is worse than an unfamiliar role name.
     */
    val ROLE_NAMES =
      mapOf(
        // Shared with ComicInfo's vocabulary.
        "aut" to "writer",
        "cre" to "writer",
        "aus" to "writer",
        "aud" to "writer",
        "art" to "penciller",
        "ill" to "penciller",
        "clr" to "colorist",
        "cov" to "cover",
        "trl" to "translator",
        "edt" to "editor",
        "edc" to "editor",
        // Present in publications, absent from ComicInfo.
        "pht" to "photographer",
        "dsr" to "designer",
        "bkd" to "designer",
        "bjd" to "designer",
        "adp" to "adapter",
        "com" to "compiler",
        "ann" to "annotator",
        "aui" to "introduction",
        "wpr" to "preface",
        "aft" to "afterword",
        "nrt" to "narrator",
        "spk" to "speaker",
        "pbl" to "publisher",
        "prt" to "printer",
        "ctb" to "contributor",
        "wst" to "contributor",
        "wat" to "contributor",
        "cmm" to "commentator",
        "cwt" to "commentator",
        "rev" to "reviser",
        "abr" to "abridger",
        "cll" to "calligrapher",
        "egr" to "engraver",
        "etr" to "etcher",
        "ltg" to "lithographer",
        "lyr" to "lyricist",
        "cmp" to "composer",
        "arr" to "arranger",
        "drt" to "director",
        "prf" to "performer",
      )

    /**
     * The English relator names for the codes above, so a producer that writes `role="Illustrator"`
     * instead of `role="ill"` lands on the same role.
     *
     * EPUB 3 says a `role` refinement should carry a code from the scheme it names, but writing the
     * display name is common enough that treating it as unmapped would surface `"Illustrator"` as a
     * role beside `"penciller"` for the same credit. Derived from [ROLE_NAMES] where the relator name
     * is simply the value, and listed explicitly only where it is not.
     */
    val ROLE_DISPLAY_NAMES =
      mapOf(
        "author" to "writer",
        "creator" to "writer",
        "screenwriter" to "writer",
        "artist" to "penciller",
        "illustrator" to "penciller",
        "colourist" to "colorist",
        "coverartist" to "cover",
        "cover artist" to "cover",
        "book designer" to "designer",
        "editor of compilation" to "editor",
        "writer of preface" to "preface",
        "author of introduction" to "introduction",
        "author of afterword" to "afterword",
      )
  }
}
