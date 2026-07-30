package io.xoboro.server.metadata

import io.xoboro.core.application.SeriesMetadataPatch
import io.xoboro.core.application.SeriesMetadataProvider
import io.xoboro.core.application.SourceSidecarAccess
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads Mylar's `series.json` sidecar into series metadata.
 *
 * **Fields read**, with where each lands: `name` with `volume`/`year` composes the title, `status` maps
 * to [SeriesStatus], `description_formatted` then `description_text` become the summary, `publisher` and
 * `total_issues` map directly, `age_rating` maps to a numeric rating, and `booktype` and `imprint` become
 * tags.
 *
 * **Fields deliberately not read**, because the catalog has nowhere honest to put them:
 *
 * - `comicid` is a Comic Vine identifier. Turning it into a link means hardcoding a URL shape this file
 *   never states, and a wrong link is worse than no link.
 * - `collects` is prose about which other issues a collected edition contains, not a property of this
 *   series.
 * - `publication_run` is a free-text date range; the catalog stores release dates per book.
 * - `comic_image` is a cover URL. Artwork comes from disk sidecars and generation, and fetching a remote
 *   image during a refresh would make an offline scan depend on the network.
 * - `type` is the schema's own discriminator, checked implicitly by requiring `metadata`.
 *
 * `booktype` and `imprint` land in `tags` because neither has a field of its own and both are short
 * descriptors of the edition, which is what a tag is. `imprint` is **not** folded into `publisher`: that
 * field already holds the publisher, and replacing it with a division of the same publisher would lose
 * information rather than add it.
 *
 * Every failure is reported to [diagnostics] rather than swallowed. A `series.json` with a typo used to
 * behave exactly like a directory with no sidecar at all.
 */
class MylarSeriesMetadataProvider(
  accesses: Collection<SourceSidecarAccess>,
  private val json: Json = Json { ignoreUnknownKeys = true },
  private val diagnostics: MylarSeriesDiagnosticSink = MylarSeriesDiagnosticSink.NONE,
) : SeriesMetadataProvider {
  private val accessesBySourceId = accesses.associateBy(SourceSidecarAccess::sourceId)

  init {
    require(accesses.none { it.sourceId.isBlank() }) { "Sidecar source IDs must not be blank" }
    require(accessesBySourceId.size == accesses.size) { "Sidecar source IDs must be unique" }
  }

  override fun provide(
    library: Library,
    series: Series,
    books: List<Book>,
  ): SeriesMetadataPatch? {
    if (!library.settings.importMylarSeries || series.oneshot) return null
    val access = accessesBySourceId[library.root.sourceId] ?: return null
    val bytes =
      try {
        access.readSeriesSidecar(
          rootItemId = library.root.itemId,
          seriesItemId = series.sourceItemId,
          fileName = SERIES_JSON_FILE,
          maximumBytes = MAX_METADATA_BYTES,
        ) ?: return null
      } catch (failure: Exception) {
        // A missing sidecar returns null above and stays silent. Reaching here means the file exists and
        // could not be read - a permission or size problem the operator can act on.
        diagnostics.report(
          MylarSeriesDiagnostic.SeriesJsonUnreadable(
            seriesItemId = series.sourceItemId,
            reason = failure.message ?: failure::class.simpleName.orEmpty().ifBlank { "read failed" },
          ),
        )
        return null
      }
    val root =
      try {
        json.parseToJsonElement(bytes.decodeToString()).jsonObject
      } catch (failure: Exception) {
        diagnostics.report(
          MylarSeriesDiagnostic.SeriesJsonMalformed(
            seriesItemId = series.sourceItemId,
            reason = failure.message ?: "not a JSON object",
          ),
        )
        return null
      }
    val metadata =
      runCatching { root["metadata"]?.jsonObject }.getOrNull()
        ?: run {
          diagnostics.report(MylarSeriesDiagnostic.SeriesJsonNotMylar(series.sourceItemId))
          return null
        }
    val name =
      metadata.string("name") ?: run {
        diagnostics.report(MylarSeriesDiagnostic.SeriesJsonMissingName(series.sourceItemId))
        return null
      }
    metadata.reportUnreadFields(series.sourceItemId)

    val volume = metadata.int("volume", series.sourceItemId)
    val year = metadata.int("year", series.sourceItemId)
    val title =
      if (volume == null || volume == 1 || year == null) {
        name
      } else {
        "$name ($year)"
      }
    val tags = setOfNotNull(metadata.string("booktype"), metadata.string("imprint"))
    return SeriesMetadataPatch(
      title = title,
      titleSort = title,
      status = metadata.status(series.sourceItemId),
      summary =
        metadata.string("description_formatted")
          ?: metadata.string("description_text"),
      publisher = metadata.string("publisher"),
      ageRating = metadata.ageRating(series.sourceItemId),
      tags = tags.ifEmpty { null },
      totalBookCount = metadata.int("total_issues", series.sourceItemId),
    )
  }

  private fun JsonObject.status(seriesItemId: String): SeriesStatus? {
    val declared = string("status") ?: return null
    return when (declared.lowercase()) {
      "ended" -> SeriesStatus.ENDED
      "continuing" -> SeriesStatus.ONGOING
      else -> {
        // Reported rather than silently dropped: Mylar writes exactly these two, so a third value is
        // either a hand-edit or a schema change, and both are worth knowing about.
        diagnostics.report(
          MylarSeriesDiagnostic.SeriesJsonFieldIgnored(seriesItemId, "status", declared),
        )
        null
      }
    }
  }

  /**
   * Reports fields present in the file that this version does not read.
   *
   * Compared against the union of read and knowingly-unread names, so a field the class doc names as
   * deliberately skipped does not show up as drift. Only genuinely unrecognised names are reported.
   */
  private fun JsonObject.reportUnreadFields(seriesItemId: String) {
    val unknown = keys - KNOWN_FIELDS
    if (unknown.isNotEmpty()) {
      diagnostics.report(MylarSeriesDiagnostic.SeriesJsonIgnored(seriesItemId, unknown))
    }
  }

  private fun JsonObject.string(name: String): String? =
    get(name)
      ?.takeUnless { it is JsonNull }
      ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
      ?.trim()
      ?.ifBlank { null }

  private fun JsonObject.int(
    name: String,
    seriesItemId: String,
  ): Int? {
    val element = get(name)?.takeUnless { it is JsonNull } ?: return null
    val primitive = runCatching { element.jsonPrimitive }.getOrNull()
    val parsed = primitive?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
    if (parsed == null) {
      diagnostics.report(
        MylarSeriesDiagnostic.SeriesJsonFieldIgnored(
          seriesItemId = seriesItemId,
          field = name,
          value = primitive?.contentOrNull ?: element.toString(),
        ),
      )
    }
    return parsed
  }

  private fun JsonObject.ageRating(seriesItemId: String): Int? {
    val value = string("age_rating") ?: return null
    val mapped =
      value.filter(Char::isDigit).toIntOrNull()
        ?: MYLAR_AGE_RATINGS[value.lowercase().replace(" ", "")]
    if (mapped == null) {
      diagnostics.report(
        MylarSeriesDiagnostic.SeriesJsonFieldIgnored(seriesItemId, "age_rating", value),
      )
    }
    return mapped
  }

  private companion object {
    const val SERIES_JSON_FILE = "series.json"
    const val MAX_METADATA_BYTES = 4 * 1_024 * 1_024
    val MYLAR_AGE_RATINGS =
      mapOf(
        "adult" to 18,
        "mature" to 17,
        "teen" to 13,
        "everyone" to 0,
      )

    /**
     * Every field this provider recognises, whether it reads it or knowingly declines to.
     *
     * The declined ones are listed on purpose: omitting them would report a well-formed Mylar file as
     * carrying unknown fields on every refresh, and that noise trains an operator to ignore the
     * diagnostic that matters.
     */
    val KNOWN_FIELDS =
      setOf(
        // Read.
        "name",
        "volume",
        "year",
        "status",
        "description_formatted",
        "description_text",
        "publisher",
        "age_rating",
        "total_issues",
        "booktype",
        "imprint",
        // Knowingly unread; the class doc says why for each.
        "comicid",
        "collects",
        "publication_run",
        "comic_image",
        "type",
      )
  }
}
