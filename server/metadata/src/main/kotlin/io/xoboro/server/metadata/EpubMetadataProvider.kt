package io.xoboro.server.metadata

import io.xoboro.core.application.BookMetadataPatch
import io.xoboro.core.application.BookMetadataProvider
import io.xoboro.core.application.SeriesMetadataPatch
import io.xoboro.core.application.SeriesMetadataProvider
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.MediaKind
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
      titleSort = metadata.seriesTitle,
      summary = metadata.description,
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
              if (creator.normalName().endsWith("creator")) "author" else "contributor"
            }
          Author(name, ROLE_NAMES[role.lowercase()] ?: role)
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
      releaseDate = metadata.select("*|date").firstNotNullOfOrNull { it.textValue()?.isoDate() },
      authors = authors,
      subjects = metadata.select("*|subject").mapNotNull { it.textValue() }.toSet(),
      isbn = isbn,
      links = links,
      seriesTitle = seriesTitle,
      seriesPosition = seriesPosition,
      publisher = metadata.selectFirst("*|publisher")?.textValue(),
      language = metadata.selectFirst("*|language")?.textValue()?.normalizedLanguage(),
    )
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
    val seriesPosition: String?,
    val publisher: String?,
    val language: String?,
  )

  private companion object {
    const val EPUB_MEDIA_TYPE = "application/epub+zip"
    const val MIMETYPE_PATH = "mimetype"
    const val CONTAINER_PATH = "META-INF/container.xml"
    const val MAXIMUM_MIMETYPE_BYTES = 128
    const val MAXIMUM_METADATA_BYTES = 4 * 1_024 * 1_024
    val YEAR = Regex("""\b\d{4}\b""")
    val ROLE_NAMES =
      mapOf(
        "aut" to "writer",
        "ill" to "penciller",
        "trl" to "translator",
        "edt" to "editor",
        "nrt" to "narrator",
        "pbl" to "publisher",
      )
  }
}
