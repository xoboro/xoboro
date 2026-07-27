package io.xoboro.compatibility.komga.api

import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class CatalogSearchTarget {
  BOOK,
  SERIES,
}

internal fun JsonObject.parseCatalogSearchCondition(
  target: CatalogSearchTarget,
): CatalogSearchCondition? {
  val condition = get("condition") ?: return null
  if (condition is JsonNull) return null
  val budget = SearchBudget()
  return condition.jsonObject.parseCondition(target, depth = 0, budget = budget)
}

private fun JsonObject.parseCondition(
  target: CatalogSearchTarget,
  depth: Int,
  budget: SearchBudget,
): CatalogSearchCondition {
  require(depth <= MAXIMUM_SEARCH_DEPTH) { "Search condition nesting is too deep" }
  budget.consume()
  require(size == 1) { "Each search condition must contain exactly one field" }
  val (name, element) = entries.single()
  if (name == "allOf" || name == "anyOf") {
    val children =
      element as? JsonArray
        ?: throw IllegalArgumentException("$name must be an array")
    require(children.isNotEmpty()) { "$name must not be empty" }
    val parsed =
      children.map {
        it.jsonObject.parseCondition(target, depth + 1, budget)
      }
    return if (name == "allOf") {
      CatalogSearchCondition.AllOf(parsed)
    } else {
      CatalogSearchCondition.AnyOf(parsed)
    }
  }

  val field =
    FIELD_NAMES[name]
      ?: throw IllegalArgumentException("Unknown search condition: $name")
  require(field in TARGET_FIELDS.getValue(target)) {
    "Search condition $name is not valid for ${target.name.lowercase()}"
  }
  val operatorObject =
    element as? JsonObject
      ?: throw IllegalArgumentException("$name must contain an operator")
  val operatorName =
    operatorObject["operator"]
      ?.jsonPrimitive
      ?.contentOrNull
      ?: throw IllegalArgumentException("$name operator is missing")
  val operator =
    OPERATORS[operatorName]
      ?: throw IllegalArgumentException("Unknown search operator: $operatorName")
  require(operator in allowedOperators(field)) {
    "Search operator $operatorName is not valid for $name"
  }

  val valueElement =
    when (operator) {
      CatalogSearchOperator.BEFORE,
      CatalogSearchOperator.AFTER,
      -> operatorObject["dateTime"]
      CatalogSearchOperator.IS_IN_THE_LAST,
      CatalogSearchOperator.IS_NOT_IN_THE_LAST,
      -> operatorObject["duration"]
      else -> operatorObject["value"]
    }
  val attributes =
    (valueElement as? JsonObject)
      ?.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.contentOrNull
          ?: throw IllegalArgumentException("$name object values must be scalar")
      }.orEmpty()
  val value =
    when (valueElement) {
      null, JsonNull -> null
      is JsonPrimitive -> valueElement.contentOrNull
      is JsonObject -> null
      else -> throw IllegalArgumentException("$name value has an invalid shape")
    }
  validateValue(field, operator, value, attributes)
  return CatalogSearchCondition.Predicate(field, operator, value, attributes)
}

private fun validateValue(
  field: CatalogSearchField,
  operator: CatalogSearchOperator,
  value: String?,
  attributes: Map<String, String>,
) {
  when (operator) {
    CatalogSearchOperator.IS_TRUE,
    CatalogSearchOperator.IS_FALSE,
    CatalogSearchOperator.IS_NULL,
    CatalogSearchOperator.IS_NOT_NULL,
    -> require(value == null && attributes.isEmpty()) { "$operator does not accept a value" }
    CatalogSearchOperator.BEFORE,
    CatalogSearchOperator.AFTER,
    ->
      runCatching {
        ZonedDateTime.parse(requireNotNull(value) { "$operator requires dateTime" })
      }.getOrElse { throw IllegalArgumentException("Search dateTime is invalid", it) }
    CatalogSearchOperator.IS_IN_THE_LAST,
    CatalogSearchOperator.IS_NOT_IN_THE_LAST,
    -> {
      val duration =
        runCatching {
          Duration.parse(requireNotNull(value) { "$operator requires duration" })
        }.getOrElse { throw IllegalArgumentException("Search duration is invalid", it) }
      require(!duration.isNegative && !duration.isZero) { "Search duration must be positive" }
    }
    else -> {
      if (field == CatalogSearchField.AUTHOR || field == CatalogSearchField.POSTER) {
        require(attributes.isNotEmpty()) { "$field requires an object value" }
      } else {
        require(!value.isNullOrBlank()) { "$operator requires a non-blank value" }
      }
    }
  }
  if (field == CatalogSearchField.AUTHOR) {
    require(attributes.keys.all { it == "name" || it == "role" }) {
      "Author search accepts only name and role"
    }
  }
  if (field == CatalogSearchField.POSTER) {
    require(attributes.keys.all { it == "type" || it == "selected" }) {
      "Poster search accepts only type and selected"
    }
    attributes["selected"]?.let {
      require(it.toBooleanStrictOrNull() != null) { "Poster selected must be boolean" }
    }
  }
}

private fun allowedOperators(field: CatalogSearchField): Set<CatalogSearchOperator> =
  when (field) {
    CatalogSearchField.DELETED,
    CatalogSearchField.COMPLETE,
    CatalogSearchField.ONE_SHOT,
    -> BOOLEAN_OPERATORS
    CatalogSearchField.TITLE,
    CatalogSearchField.TITLE_SORT,
    -> STRING_OPERATORS
    CatalogSearchField.RELEASE_DATE -> DATE_OPERATORS
    CatalogSearchField.NUMBER_SORT -> NUMERIC_OPERATORS
    CatalogSearchField.AGE_RATING -> NUMERIC_NULLABLE_OPERATORS
    CatalogSearchField.TAG,
    CatalogSearchField.SHARING_LABEL,
    CatalogSearchField.GENRE,
    -> EQUALITY_NULLABLE_OPERATORS
    else -> EQUALITY_OPERATORS
  }

private class SearchBudget {
  private var remaining = MAXIMUM_SEARCH_CONDITIONS

  fun consume() {
    require(remaining-- > 0) { "Search contains too many conditions" }
  }
}

private const val MAXIMUM_SEARCH_DEPTH = 16
private const val MAXIMUM_SEARCH_CONDITIONS = 256

private val FIELD_NAMES =
  mapOf(
    "libraryId" to CatalogSearchField.LIBRARY_ID,
    "collectionId" to CatalogSearchField.COLLECTION_ID,
    "readListId" to CatalogSearchField.READ_LIST_ID,
    "seriesId" to CatalogSearchField.SERIES_ID,
    "deleted" to CatalogSearchField.DELETED,
    "complete" to CatalogSearchField.COMPLETE,
    "oneShot" to CatalogSearchField.ONE_SHOT,
    "title" to CatalogSearchField.TITLE,
    "titleSort" to CatalogSearchField.TITLE_SORT,
    "releaseDate" to CatalogSearchField.RELEASE_DATE,
    "tag" to CatalogSearchField.TAG,
    "sharingLabel" to CatalogSearchField.SHARING_LABEL,
    "publisher" to CatalogSearchField.PUBLISHER,
    "language" to CatalogSearchField.LANGUAGE,
    "genre" to CatalogSearchField.GENRE,
    "ageRating" to CatalogSearchField.AGE_RATING,
    "readStatus" to CatalogSearchField.READ_STATUS,
    "seriesStatus" to CatalogSearchField.SERIES_STATUS,
    "author" to CatalogSearchField.AUTHOR,
    "numberSort" to CatalogSearchField.NUMBER_SORT,
    "mediaStatus" to CatalogSearchField.MEDIA_STATUS,
    "mediaProfile" to CatalogSearchField.MEDIA_PROFILE,
    "poster" to CatalogSearchField.POSTER,
  )

private val TARGET_FIELDS =
  mapOf(
    CatalogSearchTarget.BOOK to
      setOf(
        CatalogSearchField.LIBRARY_ID,
        CatalogSearchField.READ_LIST_ID,
        CatalogSearchField.SERIES_ID,
        CatalogSearchField.DELETED,
        CatalogSearchField.ONE_SHOT,
        CatalogSearchField.TITLE,
        CatalogSearchField.RELEASE_DATE,
        CatalogSearchField.TAG,
        CatalogSearchField.NUMBER_SORT,
        CatalogSearchField.READ_STATUS,
        CatalogSearchField.MEDIA_STATUS,
        CatalogSearchField.MEDIA_PROFILE,
        CatalogSearchField.AUTHOR,
        CatalogSearchField.POSTER,
      ),
    CatalogSearchTarget.SERIES to
      setOf(
        CatalogSearchField.LIBRARY_ID,
        CatalogSearchField.COLLECTION_ID,
        CatalogSearchField.DELETED,
        CatalogSearchField.COMPLETE,
        CatalogSearchField.ONE_SHOT,
        CatalogSearchField.TITLE,
        CatalogSearchField.TITLE_SORT,
        CatalogSearchField.RELEASE_DATE,
        CatalogSearchField.TAG,
        CatalogSearchField.SHARING_LABEL,
        CatalogSearchField.PUBLISHER,
        CatalogSearchField.LANGUAGE,
        CatalogSearchField.GENRE,
        CatalogSearchField.AGE_RATING,
        CatalogSearchField.READ_STATUS,
        CatalogSearchField.SERIES_STATUS,
        CatalogSearchField.AUTHOR,
      ),
  )

private val OPERATORS =
  CatalogSearchOperator.entries.associateBy {
    when (it) {
      CatalogSearchOperator.IS -> "is"
      CatalogSearchOperator.IS_NOT -> "isNot"
      CatalogSearchOperator.CONTAINS -> "contains"
      CatalogSearchOperator.DOES_NOT_CONTAIN -> "doesNotContain"
      CatalogSearchOperator.BEGINS_WITH -> "beginsWith"
      CatalogSearchOperator.DOES_NOT_BEGIN_WITH -> "doesNotBeginWith"
      CatalogSearchOperator.ENDS_WITH -> "endsWith"
      CatalogSearchOperator.DOES_NOT_END_WITH -> "doesNotEndWith"
      CatalogSearchOperator.GREATER_THAN -> "greaterThan"
      CatalogSearchOperator.LESS_THAN -> "lessThan"
      CatalogSearchOperator.BEFORE -> "before"
      CatalogSearchOperator.AFTER -> "after"
      CatalogSearchOperator.IS_IN_THE_LAST -> "isInTheLast"
      CatalogSearchOperator.IS_NOT_IN_THE_LAST -> "isNotInTheLast"
      CatalogSearchOperator.IS_NULL -> "isNull"
      CatalogSearchOperator.IS_NOT_NULL -> "isNotNull"
      CatalogSearchOperator.IS_TRUE -> "isTrue"
      CatalogSearchOperator.IS_FALSE -> "isFalse"
    }
  }

private val EQUALITY_OPERATORS =
  setOf(CatalogSearchOperator.IS, CatalogSearchOperator.IS_NOT)
private val EQUALITY_NULLABLE_OPERATORS =
  EQUALITY_OPERATORS + setOf(CatalogSearchOperator.IS_NULL, CatalogSearchOperator.IS_NOT_NULL)
private val BOOLEAN_OPERATORS =
  setOf(CatalogSearchOperator.IS_TRUE, CatalogSearchOperator.IS_FALSE)
private val STRING_OPERATORS =
  setOf(
    CatalogSearchOperator.IS,
    CatalogSearchOperator.IS_NOT,
    CatalogSearchOperator.CONTAINS,
    CatalogSearchOperator.DOES_NOT_CONTAIN,
    CatalogSearchOperator.BEGINS_WITH,
    CatalogSearchOperator.DOES_NOT_BEGIN_WITH,
    CatalogSearchOperator.ENDS_WITH,
    CatalogSearchOperator.DOES_NOT_END_WITH,
  )
private val NUMERIC_OPERATORS =
  setOf(
    CatalogSearchOperator.IS,
    CatalogSearchOperator.IS_NOT,
    CatalogSearchOperator.GREATER_THAN,
    CatalogSearchOperator.LESS_THAN,
  )
private val NUMERIC_NULLABLE_OPERATORS =
  NUMERIC_OPERATORS + setOf(CatalogSearchOperator.IS_NULL, CatalogSearchOperator.IS_NOT_NULL)
private val DATE_OPERATORS =
  setOf(
    CatalogSearchOperator.BEFORE,
    CatalogSearchOperator.AFTER,
    CatalogSearchOperator.IS_IN_THE_LAST,
    CatalogSearchOperator.IS_NOT_IN_THE_LAST,
    CatalogSearchOperator.IS_NULL,
    CatalogSearchOperator.IS_NOT_NULL,
  )
