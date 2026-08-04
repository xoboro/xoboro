package io.xoboro.server.metadata

import io.xoboro.core.application.BookMetadataPatch
import io.xoboro.core.application.BookMetadataProvider
import io.xoboro.core.application.ReadListMetadataEntry
import io.xoboro.core.application.SeriesMetadataPatch
import io.xoboro.core.application.SeriesMetadataProvider
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.WebLink
import io.xoboro.server.media.SourceMediaAccess
import io.xoboro.server.media.SourceRandomAccess
import io.xoboro.server.media.UnknownSourceMediaAccessException
import io.xoboro.server.media.ZipCentralDirectory
import io.xoboro.server.media.ZipDirectoryUnreadableException
import io.xoboro.server.media.ZipRangedEntryReader
import java.net.URI
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

class ComicInfoMetadataProvider(
  accesses: Collection<SourceMediaAccess>,
  randomAccesses: Collection<SourceRandomAccess> = emptyList(),
) : BookMetadataProvider,
  SeriesMetadataProvider {
  private val accessesBySourceId = accesses.associateBy(SourceMediaAccess::sourceId)
  private val randomAccessesBySourceId = randomAccesses.associateBy(SourceRandomAccess::sourceId)

  init {
    require(accesses.none { it.sourceId.isBlank() }) { "Media source IDs must not be blank" }
    require(accessesBySourceId.size == accesses.size) { "Media source IDs must be unique" }
    require(randomAccesses.none { it.sourceId.isBlank() }) { "Random access source IDs must not be blank" }
    require(randomAccessesBySourceId.size == randomAccesses.size) { "Random access source IDs must be unique" }
  }

  override fun provide(
    library: Library,
    book: Book,
  ): BookMetadataPatch? {
    if (
      !library.settings.importComicInfoBook &&
      !library.settings.importComicInfoReadList
    ) {
      return null
    }
    val comicInfo = readComicInfo(library, book) ?: return null
    return BookMetadataPatch(
      title = comicInfo.nonBlank("Title"),
      summary = comicInfo.nonBlank("Summary"),
      number = comicInfo.nonBlank("Number"),
      numberSort = comicInfo.nonBlank("Number")?.toFloatOrNull(),
      releaseDate = comicInfo.releaseDate(),
      authors = comicInfo.authors().ifEmpty { null },
      tags = comicInfo.commaSeparated("Tags").mapTo(linkedSetOf()) { it.lowercase() }
        .ifEmpty { null },
      isbn = comicInfo.validIsbnOrNull(),
      links = comicInfo.links().ifEmpty { null },
      readLists =
        if (library.settings.importComicInfoReadList) {
          comicInfo.readLists()
        } else {
          emptyList()
        },
    )
  }

  override fun shouldApplyBookMetadata(library: Library): Boolean =
    library.settings.importComicInfoBook

  override fun provide(
    library: Library,
    series: Series,
    books: List<Book>,
  ): SeriesMetadataPatch? {
    if (
      !library.settings.importComicInfoSeries &&
      !library.settings.importComicInfoCollection
    ) {
      return null
    }
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
      collections =
        if (library.settings.importComicInfoCollection) {
          source.commaSeparated("SeriesGroup")
        } else {
          emptySet()
        },
    )
  }

  override fun shouldApplySeriesMetadata(library: Library): Boolean =
    library.settings.importComicInfoSeries

  /**
   * Reads `ComicInfo.xml` by byte range when the source can serve one, falling back to fetching the
   * whole archive only when it cannot.
   *
   * 99.6% of the books in the library this was measured against carry a `ComicInfo.xml`, so skipping
   * absent ones would have saved nothing - what saves is reading the one entry rather than the 7.26 MB
   * around it. This was the last caller of `materialize` left on the scan path: with analysis and
   * cover generation already ranged, 10,112 pending `REFRESH_BOOK_METADATA` tasks would still have
   * pulled about 73 GB between them.
   */
  private fun readComicInfoByRange(
    library: Library,
    book: Book,
  ): RangedComicInfo {
    val randomAccess = randomAccessesBySourceId[library.root.sourceId] ?: return RangedComicInfo.Unavailable
    return try {
      randomAccess.open(library.root.itemId, book.sourceItemId).use { opened ->
        val entry =
          ZipCentralDirectory
            .read(opened)
            .firstOrNull { it.name.equals(COMIC_INFO_FILE, ignoreCase = true) }
            // Read, and the answer is "this archive has none". Fetching the whole file to confirm an
            // absence the central directory already settled is the one thing this must not do.
            ?: return RangedComicInfo.Read(null)
        if (entry.uncompressedSize > MAX_METADATA_BYTES) return RangedComicInfo.Read(null)
        RangedComicInfo.Read(parseComicInfo(ZipRangedEntryReader.read(opened, entry)))
      }
    } catch (_: ZipDirectoryUnreadableException) {
      RangedComicInfo.Unavailable
    } catch (_: java.io.IOException) {
      RangedComicInfo.Unavailable
    } catch (_: SecurityException) {
      RangedComicInfo.Unavailable
    } catch (_: javax.xml.stream.XMLStreamException) {
      // Malformed XML is the archive's problem, not the transport's; the whole-file path would read
      // the same bytes and fail the same way.
      RangedComicInfo.Read(null)
    }
  }

  /**
   * Three outcomes, not two. "The archive holds no `ComicInfo.xml`" and "this source cannot serve
   * ranges" both have no metadata to return, and collapsing them means every archive without one gets
   * downloaded in full to establish what was already known.
   */
  private sealed interface RangedComicInfo {
    data class Read(
      val values: Map<String, String>?,
    ) : RangedComicInfo

    data object Unavailable : RangedComicInfo
  }

  private fun readComicInfo(
    library: Library,
    book: Book,
  ): Map<String, String>? {
    when (val ranged = readComicInfoByRange(library, book)) {
      is RangedComicInfo.Read -> return ranged.values
      RangedComicInfo.Unavailable -> Unit
    }
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

  private fun Map<String, String>.readLists(): List<ReadListMetadataEntry> =
    buildList {
      nonBlank("AlternateSeries")?.let { name ->
        add(
          ReadListMetadataEntry(
            name = name,
            number = nonBlank("AlternateNumber")?.toIntOrNull(),
          ),
        )
      }
      val arcs =
        nonBlank("StoryArc")
          ?.split(',')
          ?.map { it.trim().ifBlank { null } }
          .orEmpty()
      val numbers =
        nonBlank("StoryArcNumber")
          ?.split(',')
          ?.map { it.trim().toIntOrNull() }
      if (numbers.isNullOrEmpty()) {
        addAll(arcs.filterNotNull().map(::ReadListMetadataEntry))
      } else {
        arcs.zip(numbers).forEach { (arc, number) ->
          if (arc != null && number != null) {
            add(ReadListMetadataEntry(arc, number))
          }
        }
      }
    }

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
