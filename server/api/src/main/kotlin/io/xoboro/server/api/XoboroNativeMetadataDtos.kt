package io.xoboro.server.api

import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.ManualBookMetadataPatch
import io.xoboro.core.application.ManualSeriesMetadataPatch
import io.xoboro.core.application.PatchField
import io.xoboro.core.domain.AlternateTitle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.WebLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class XoboroBookMetadataResponse(
  val title: String,
  val titleLock: Boolean,
  val summary: String,
  val summaryLock: Boolean,
  val number: String,
  val numberLock: Boolean,
  val numberSort: Float,
  val numberSortLock: Boolean,
  val releaseDate: String? = null,
  val releaseDateLock: Boolean,
  val authors: List<XoboroAuthorResponse>,
  val authorsLock: Boolean,
  val tags: Set<String>,
  val tagsLock: Boolean,
  val isbn: String,
  val isbnLock: Boolean,
  val links: List<XoboroWebLinkResponse>,
  val linksLock: Boolean,
)

@Serializable
data class XoboroSeriesMetadataResponse(
  val status: String,
  val statusLock: Boolean,
  val title: String,
  val titleLock: Boolean,
  val titleSort: String,
  val titleSortLock: Boolean,
  val summary: String,
  val summaryLock: Boolean,
  val readingDirection: String? = null,
  val readingDirectionLock: Boolean,
  val publisher: String,
  val publisherLock: Boolean,
  val ageRating: Int? = null,
  val ageRatingLock: Boolean,
  val language: String,
  val languageLock: Boolean,
  val genres: Set<String>,
  val genresLock: Boolean,
  val tags: Set<String>,
  val tagsLock: Boolean,
  val totalBookCount: Int? = null,
  val totalBookCountLock: Boolean,
  val sharingLabels: Set<String>,
  val sharingLabelsLock: Boolean,
  val links: List<XoboroWebLinkResponse>,
  val linksLock: Boolean,
  val alternateTitles: List<XoboroAlternateTitleResponse>,
  val alternateTitlesLock: Boolean,
)

internal fun BookMetadata.toNativeMetadataResponse(): XoboroBookMetadataResponse =
  XoboroBookMetadataResponse(
    title = title,
    titleLock = titleLock,
    summary = summary,
    summaryLock = summaryLock,
    number = number,
    numberLock = numberLock,
    numberSort = numberSort,
    numberSortLock = numberSortLock,
    releaseDate = releaseDate,
    releaseDateLock = releaseDateLock,
    authors = authors.map(Author::toNativeResponse),
    authorsLock = authorsLock,
    tags = tags,
    tagsLock = tagsLock,
    isbn = isbn,
    isbnLock = isbnLock,
    links = links.map(WebLink::toNativeResponse),
    linksLock = linksLock,
  )

internal fun SeriesMetadata.toNativeMetadataResponse(): XoboroSeriesMetadataResponse =
  XoboroSeriesMetadataResponse(
    status = status.name,
    statusLock = statusLock,
    title = title,
    titleLock = titleLock,
    titleSort = titleSort,
    titleSortLock = titleSortLock,
    summary = summary,
    summaryLock = summaryLock,
    readingDirection = readingDirection?.name,
    readingDirectionLock = readingDirectionLock,
    publisher = publisher,
    publisherLock = publisherLock,
    ageRating = ageRating,
    ageRatingLock = ageRatingLock,
    language = language,
    languageLock = languageLock,
    genres = genres,
    genresLock = genresLock,
    tags = tags,
    tagsLock = tagsLock,
    totalBookCount = totalBookCount,
    totalBookCountLock = totalBookCountLock,
    sharingLabels = sharingLabels,
    sharingLabelsLock = sharingLabelsLock,
    links = links.map(WebLink::toNativeResponse),
    linksLock = linksLock,
    alternateTitles = alternateTitles.map(AlternateTitle::toNativeResponse),
    alternateTitlesLock = alternateTitlesLock,
  )

internal fun CatalogPage<Author>.toNativeAuthorPage(): XoboroPageResponse<XoboroAuthorResponse> =
  toNativePage(content.map(Author::toNativeResponse))

internal fun JsonObject.toManualBookMetadataPatch(): ManualBookMetadataPatch =
  ManualBookMetadataPatch(
    title = optionalString("title"),
    titleLock = optionalBoolean("titleLock"),
    summary = patchField("summary") { nullableString("summary") },
    summaryLock = optionalBoolean("summaryLock"),
    number = optionalString("number"),
    numberLock = optionalBoolean("numberLock"),
    numberSort = optionalFloat("numberSort"),
    numberSortLock = optionalBoolean("numberSortLock"),
    releaseDate = patchField("releaseDate") { nullableString("releaseDate") },
    releaseDateLock = optionalBoolean("releaseDateLock"),
    authors = patchField("authors") { nullableObjects("authors", JsonObject::toAuthor) },
    authorsLock = optionalBoolean("authorsLock"),
    tags = patchField("tags") { nullableStrings("tags")?.toSet() },
    tagsLock = optionalBoolean("tagsLock"),
    isbn = patchField("isbn") { nullableString("isbn") },
    isbnLock = optionalBoolean("isbnLock"),
    links = patchField("links") { nullableObjects("links", JsonObject::toWebLink) },
    linksLock = optionalBoolean("linksLock"),
  )

internal fun JsonObject.toManualSeriesMetadataPatch(): ManualSeriesMetadataPatch =
  ManualSeriesMetadataPatch(
    status = optionalEnum<SeriesStatus>("status"),
    statusLock = optionalBoolean("statusLock"),
    title = optionalString("title"),
    titleLock = optionalBoolean("titleLock"),
    titleSort = optionalString("titleSort"),
    titleSortLock = optionalBoolean("titleSortLock"),
    summary = optionalString("summary"),
    summaryLock = optionalBoolean("summaryLock"),
    readingDirection =
      patchField("readingDirection") { nullableEnum<ReadingDirection>("readingDirection") },
    readingDirectionLock = optionalBoolean("readingDirectionLock"),
    publisher = optionalString("publisher"),
    publisherLock = optionalBoolean("publisherLock"),
    ageRating = patchField("ageRating") { nullableInt("ageRating") },
    ageRatingLock = optionalBoolean("ageRatingLock"),
    language = optionalString("language"),
    languageLock = optionalBoolean("languageLock"),
    genres = patchField("genres") { nullableStrings("genres")?.toSet() },
    genresLock = optionalBoolean("genresLock"),
    tags = patchField("tags") { nullableStrings("tags")?.toSet() },
    tagsLock = optionalBoolean("tagsLock"),
    totalBookCount = patchField("totalBookCount") { nullableInt("totalBookCount") },
    totalBookCountLock = optionalBoolean("totalBookCountLock"),
    sharingLabels = patchField("sharingLabels") { nullableStrings("sharingLabels")?.toSet() },
    sharingLabelsLock = optionalBoolean("sharingLabelsLock"),
    links = patchField("links") { nullableObjects("links", JsonObject::toWebLink) },
    linksLock = optionalBoolean("linksLock"),
    alternateTitles =
      patchField("alternateTitles") {
        nullableObjects("alternateTitles", JsonObject::toAlternateTitle)
      },
    alternateTitlesLock = optionalBoolean("alternateTitlesLock"),
  )

private fun JsonObject.optionalString(name: String): String? =
  get(name)?.takeUnless { it is JsonNull }?.requiredString(name)

private fun JsonObject.optionalBoolean(name: String): Boolean? =
  get(name)?.takeUnless { it is JsonNull }?.let { element ->
    val primitive = element as? JsonPrimitive
      ?: throw IllegalArgumentException("$name must be a boolean")
    primitive.booleanOrNull ?: throw IllegalArgumentException("$name must be a boolean")
  }

private fun JsonObject.optionalFloat(name: String): Float? =
  get(name)?.takeUnless { it is JsonNull }?.let { element ->
    val primitive = element as? JsonPrimitive
      ?: throw IllegalArgumentException("$name must be a number")
    primitive.floatOrNull ?: throw IllegalArgumentException("$name must be a number")
  }

private inline fun <reified T : Enum<T>> JsonObject.optionalEnum(name: String): T? =
  get(name)?.takeUnless { it is JsonNull }?.requiredString(name)?.toEnum(name)

private fun <T> JsonObject.patchField(
  name: String,
  converter: JsonElement.() -> T?,
): PatchField<T> =
  if (containsKey(name)) {
    PatchField(present = true, value = requireNotNull(get(name)).converter())
  } else {
    PatchField()
  }

private fun JsonElement.nullableString(name: String): String? =
  takeUnless { it is JsonNull }?.requiredString(name)

private fun JsonElement.nullableInt(name: String): Int? =
  takeUnless { it is JsonNull }?.let { element ->
    val primitive = element as? JsonPrimitive
      ?: throw IllegalArgumentException("$name must be an integer")
    primitive.intOrNull ?: throw IllegalArgumentException("$name must be an integer")
  }

private inline fun <reified T : Enum<T>> JsonElement.nullableEnum(name: String): T? =
  nullableString(name)?.toEnum(name)

private fun JsonElement.nullableStrings(name: String): List<String>? =
  takeUnless { it is JsonNull }?.let { element ->
    val values = element as? JsonArray
      ?: throw IllegalArgumentException("$name must be an array")
    values.map { it.requiredString(name) }
  }

private fun <T> JsonElement.nullableObjects(
  name: String,
  converter: JsonObject.() -> T,
): List<T>? =
  takeUnless { it is JsonNull }?.let { element ->
    val values = element as? JsonArray
      ?: throw IllegalArgumentException("$name must be an array")
    values.map {
      val value = it as? JsonObject
        ?: throw IllegalArgumentException("$name must contain objects")
      value.converter()
    }
  }

private fun JsonObject.toAuthor(): Author =
  Author(
    name = requiredObjectString("name", "authors"),
    role = requiredObjectString("role", "authors"),
  )

private fun JsonObject.toWebLink(): WebLink =
  WebLink(
    label = requiredObjectString("label", "links"),
    url = requiredObjectString("url", "links"),
  )

private fun JsonObject.toAlternateTitle(): AlternateTitle =
  AlternateTitle(
    label = requiredObjectString("label", "alternateTitles"),
    title = requiredObjectString("title", "alternateTitles"),
  )

private fun JsonObject.requiredObjectString(
  name: String,
  parent: String,
): String =
  get(name)?.takeUnless { it is JsonNull }?.requiredString("$parent.$name")
    ?: throw IllegalArgumentException("$parent.$name is required")

private fun JsonElement.requiredString(name: String): String {
  val primitive = this as? JsonPrimitive
    ?: throw IllegalArgumentException("$name must be a string")
  if (!primitive.isString) throw IllegalArgumentException("$name must be a string")
  return primitive.content
}

private inline fun <reified T : Enum<T>> String.toEnum(name: String): T =
  try {
    enumValueOf(this)
  } catch (_: IllegalArgumentException) {
    throw IllegalArgumentException("$name has an unsupported value: $this")
  }
