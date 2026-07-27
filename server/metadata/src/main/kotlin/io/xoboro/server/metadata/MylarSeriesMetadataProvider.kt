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

class MylarSeriesMetadataProvider(
  accesses: Collection<SourceSidecarAccess>,
  private val json: Json = Json { ignoreUnknownKeys = true },
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
    return runCatching {
      val bytes =
        access.readSeriesSidecar(
          rootItemId = library.root.itemId,
          seriesItemId = series.sourceItemId,
          fileName = SERIES_JSON_FILE,
          maximumBytes = MAX_METADATA_BYTES,
        ) ?: return null
      val metadata =
        json.parseToJsonElement(bytes.decodeToString())
          .jsonObject["metadata"]
          ?.jsonObject
          ?: return null
      val name = metadata.string("name") ?: return null
      val volume = metadata.int("volume")
      val year = metadata.int("year")
      val title =
        if (volume == null || volume == 1 || year == null) {
          name
        } else {
          "$name ($year)"
        }
      SeriesMetadataPatch(
        title = title,
        titleSort = title,
        status =
          when (metadata.string("status")?.lowercase()) {
            "ended" -> SeriesStatus.ENDED
            "continuing" -> SeriesStatus.ONGOING
            else -> null
          },
        summary =
          metadata.string("description_formatted")
            ?: metadata.string("description_text"),
        publisher = metadata.string("publisher"),
        ageRating = metadata.ageRating(),
        totalBookCount = metadata.int("total_issues"),
      )
    }.getOrNull()
  }

  private fun JsonObject.string(name: String): String? =
    get(name)
      ?.takeUnless { it is JsonNull }
      ?.jsonPrimitive
      ?.contentOrNull
      ?.trim()
      ?.ifBlank { null }

  private fun JsonObject.int(name: String): Int? =
    get(name)
      ?.takeUnless { it is JsonNull }
      ?.jsonPrimitive
      ?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

  private fun JsonObject.ageRating(): Int? {
    val value = string("age_rating") ?: return null
    return value
      .filter(Char::isDigit)
      .toIntOrNull()
      ?: MYLAR_AGE_RATINGS[value.lowercase().replace(" ", "")]
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
  }
}
