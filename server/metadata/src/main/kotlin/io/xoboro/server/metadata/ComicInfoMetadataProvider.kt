package io.xoboro.server.metadata

import io.xoboro.core.application.BookMetadataPatch
import io.xoboro.core.application.BookMetadataProvider
import io.xoboro.core.application.SeriesMetadataPatch
import io.xoboro.core.application.SeriesMetadataProvider
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.WebLink
import io.xoboro.server.media.SourceMediaAccess
import io.xoboro.server.media.UnknownSourceMediaAccessException
import java.net.URI
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

class ComicInfoMetadataProvider(
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
    if (!library.settings.importComicInfoBook) return null
    val comicInfo = readComicInfo(library, book) ?: return null
    return BookMetadataPatch(
      title = comicInfo.nonBlank("Title"),
      summary = comicInfo.nonBlank("Summary"),
      number = comicInfo.nonBlank("Number"),
      numberSort = comicInfo.nonBlank("Number")?.toFloatOrNull(),
      releaseDate = comicInfo.releaseDate(),
      authors = comicInfo.authors().ifEmpty { null },
      tags = comicInfo.commaSeparated("Tags").ifEmpty { null },
      isbn = comicInfo.validIsbnOrNull(),
      links = comicInfo.links().ifEmpty { null },
    )
  }

  override fun provide(
    library: Library,
    series: Series,
    books: List<Book>,
  ): SeriesMetadataPatch? {
    if (!library.settings.importComicInfoSeries) return null
    val source =
      books
        .asSequence()
        .sortedWith(compareBy<Book> { it.number }.thenBy { it.relativePath })
        .mapNotNull { readComicInfo(library, it) }
        .firstOrNull()
        ?: return null
    val title =
      source.nonBlank("Series")?.let { seriesTitle ->
        val volume = source.nonBlank("Volume")?.toIntOrNull()
        if (
          library.settings.importComicInfoSeriesAppendVolume &&
          volume != null &&
          volume != 1
        ) {
          "$seriesTitle ($volume)"
        } else {
          seriesTitle
        }
      }
    return SeriesMetadataPatch(
      title = title,
      titleSort = title,
      readingDirection =
        when (source.nonBlank("Manga")) {
          "No" -> ReadingDirection.LEFT_TO_RIGHT
          "YesAndRightToLeft" -> ReadingDirection.RIGHT_TO_LEFT
          else -> null
        },
      publisher = source.nonBlank("Publisher"),
      ageRating = source.ageRating(),
      language = source.normalizedLanguage(),
      genres = source.commaSeparated("Genre").ifEmpty { null },
      totalBookCount = source.nonBlank("Count")?.toIntOrNull(),
    )
  }

  private fun readComicInfo(
    library: Library,
    book: Book,
  ): Map<String, String>? {
    val access =
      accessesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceMediaAccessException(library.root.sourceId)
    return runCatching {
      access.materialize(library.root.itemId, book.sourceItemId).use { materialized ->
        ZipFile(materialized.path.toFile()).use { archive ->
          val entry = archive.getEntry(COMIC_INFO_FILE) ?: return null
          require(entry.size <= MAX_METADATA_BYTES || entry.size < 0) {
            "$COMIC_INFO_FILE exceeds the metadata size limit"
          }
          val bytes =
            archive.getInputStream(entry).use { input ->
              input.readNBytes(MAX_METADATA_BYTES + 1).also {
                require(it.size <= MAX_METADATA_BYTES) {
                  "$COMIC_INFO_FILE exceeds the metadata size limit"
                }
              }
            }
          parseComicInfo(bytes)
        }
      }
    }.getOrNull()
  }

  private fun parseComicInfo(bytes: ByteArray): Map<String, String> {
    val factory =
      XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty("javax.xml.stream.isSupportingExternalEntities", false)
      }
    val values = linkedMapOf<String, String>()
    val reader = factory.createXMLStreamReader(bytes.inputStream())
    try {
      var depth = 0
      var field: String? = null
      var text = StringBuilder()
      while (reader.hasNext()) {
        when (reader.next()) {
          XMLStreamConstants.START_ELEMENT -> {
            depth += 1
            if (depth == 2) {
              field = reader.localName
              text = StringBuilder()
            }
          }
          XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
            if (depth == 2 && field != null) text.append(reader.text)
          }
          XMLStreamConstants.END_ELEMENT -> {
            if (depth == 2 && field != null) {
              values[field] = text.toString()
              field = null
            }
            depth -= 1
          }
        }
      }
    } finally {
      reader.close()
    }
    return values
  }

  private fun Map<String, String>.nonBlank(name: String): String? =
    get(name)?.trim()?.ifBlank { null }

  private fun Map<String, String>.commaSeparated(name: String): Set<String> =
    nonBlank(name)
      ?.split(',')
      ?.mapNotNull { it.trim().ifBlank { null } }
      ?.toSet()
      .orEmpty()

  private fun Map<String, String>.releaseDate(): String? {
    val year = nonBlank("Year")?.toIntOrNull() ?: return null
    val month = nonBlank("Month")?.toIntOrNull() ?: 1
    val day = nonBlank("Day")?.toIntOrNull() ?: 1
    return try {
      LocalDate.of(year, month, day).toString()
    } catch (_: DateTimeException) {
      null
    }
  }

  private fun Map<String, String>.authors(): List<Author> =
    AUTHOR_FIELDS.flatMap { (field, role) ->
      nonBlank(field)
        ?.split(',')
        ?.mapNotNull { it.trim().ifBlank { null } }
        ?.map { Author(it, role) }
        .orEmpty()
    }

  private fun Map<String, String>.links(): List<WebLink> =
    nonBlank("Web")
      ?.split(' ')
      ?.mapNotNull { candidate ->
        runCatching {
          val uri = URI(candidate.trim())
          require(uri.isAbsolute)
          WebLink(uri.host ?: uri.scheme, uri.toString())
        }.getOrNull()
      }.orEmpty()

  private fun Map<String, String>.validIsbnOrNull(): String? {
    val candidate =
      nonBlank("GTIN")
        ?.filter { it.isDigit() || it.equals('X', ignoreCase = true) }
        ?.uppercase()
        ?: return null
    return candidate.takeIf { it.isValidIsbn() }
  }

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

  private fun Map<String, String>.normalizedLanguage(): String? {
    val value = nonBlank("LanguageISO") ?: return null
    val normalized = Locale.forLanguageTag(value).toLanguageTag()
    return normalized.takeUnless { it.equals("und", ignoreCase = true) }
  }

  private fun Map<String, String>.ageRating(): Int? =
    AGE_RATINGS[nonBlank("AgeRating")?.lowercase()?.replace(" ", "")]

  private companion object {
    const val COMIC_INFO_FILE = "ComicInfo.xml"
    const val MAX_METADATA_BYTES = 4 * 1_024 * 1_024
    val AUTHOR_FIELDS =
      listOf(
        "Writer" to "writer",
        "Penciller" to "penciller",
        "Inker" to "inker",
        "Colorist" to "colorist",
        "Letterer" to "letterer",
        "CoverArtist" to "cover",
        "Editor" to "editor",
        "Translator" to "translator",
      )
    val AGE_RATINGS =
      mapOf(
        "adultsonly18+" to 18,
        "earlychildhood" to 3,
        "everyone" to 0,
        "everyone10+" to 10,
        "g" to 0,
        "kidstoadults" to 6,
        "m" to 17,
        "ma15+" to 15,
        "mature17+" to 17,
        "pg" to 8,
        "r18+" to 18,
        "teen" to 13,
        "x18+" to 18,
      )
  }
}
