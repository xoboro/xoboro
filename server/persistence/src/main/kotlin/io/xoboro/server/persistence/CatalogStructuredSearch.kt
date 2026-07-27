package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal data class CatalogSqlCondition(
  val sql: String,
  val bindings: List<Any?> = emptyList(),
)

internal class CatalogStructuredSearch(
  private val currentTimeMillis: () -> Long,
) {
  fun book(
    condition: CatalogSearchCondition,
    access: CatalogAccess,
  ): CatalogSqlCondition =
    compile(condition) { predicate -> bookPredicate(predicate, access) }

  fun series(
    condition: CatalogSearchCondition,
    access: CatalogAccess,
  ): CatalogSqlCondition =
    compile(condition) { predicate -> seriesPredicate(predicate, access) }

  private fun compile(
    condition: CatalogSearchCondition,
    predicateCompiler: (CatalogSearchCondition.Predicate) -> CatalogSqlCondition,
  ): CatalogSqlCondition =
    when (condition) {
      is CatalogSearchCondition.AllOf ->
        combine(condition.conditions, "AND", predicateCompiler)
      is CatalogSearchCondition.AnyOf ->
        combine(condition.conditions, "OR", predicateCompiler)
      is CatalogSearchCondition.Predicate -> predicateCompiler(condition)
    }

  private fun combine(
    conditions: List<CatalogSearchCondition>,
    operator: String,
    predicateCompiler: (CatalogSearchCondition.Predicate) -> CatalogSqlCondition,
  ): CatalogSqlCondition {
    val compiled = conditions.map { compile(it, predicateCompiler) }
    return CatalogSqlCondition(
      sql = compiled.joinToString(" $operator ") { "(${it.sql})" },
      bindings = compiled.flatMap(CatalogSqlCondition::bindings),
    )
  }

  private fun bookPredicate(
    predicate: CatalogSearchCondition.Predicate,
    access: CatalogAccess,
  ): CatalogSqlCondition =
    when (predicate.field) {
      CatalogSearchField.LIBRARY_ID -> equality("b.library_id", predicate)
      CatalogSearchField.READ_LIST_ID ->
        membership(
          predicate,
          """
          SELECT member.book_id
          FROM read_list_member member
          WHERE member.read_list_id = ?
          """.trimIndent(),
        )
      CatalogSearchField.SERIES_ID -> equality("b.series_id", predicate)
      CatalogSearchField.DELETED ->
        booleanNullability("b.deleted_at_ms", predicate, trueMeansNotNull = true)
      CatalogSearchField.ONE_SHOT -> booleanValue("b.oneshot", predicate)
      CatalogSearchField.TITLE -> stringValue("bm.title", predicate)
      CatalogSearchField.RELEASE_DATE -> dateValue("bm.release_date", predicate)
      CatalogSearchField.TAG ->
        nullableRelation(
          ownerColumn = "b.id",
          relationTable = "book_metadata_tag",
          relationOwnerColumn = "book_id",
          relationValueColumn = "tag",
          predicate = predicate,
        )
      CatalogSearchField.NUMBER_SORT -> numericValue("bm.number_sort", predicate)
      CatalogSearchField.READ_STATUS -> bookReadStatus(predicate, access)
      CatalogSearchField.MEDIA_STATUS ->
        equalitySubquery(
          ownerColumn = "b.id",
          table = "media",
          ownerField = "book_id",
          valueField = "status",
          predicate = predicate,
        )
      CatalogSearchField.MEDIA_PROFILE ->
        equalitySubquery(
          ownerColumn = "b.id",
          table = "media",
          ownerField = "book_id",
          valueField = "profile",
          predicate = predicate,
        )
      CatalogSearchField.AUTHOR ->
        author(
          ownerColumn = "b.id",
          ownerSelection = "author.book_id",
          from =
            """
            book_metadata_author author
            """.trimIndent(),
          predicate = predicate,
        )
      CatalogSearchField.POSTER -> poster(predicate)
      else -> throw IllegalArgumentException(
        "Search field ${predicate.field} is not supported for books",
      )
    }

  private fun seriesPredicate(
    predicate: CatalogSearchCondition.Predicate,
    access: CatalogAccess,
  ): CatalogSqlCondition =
    when (predicate.field) {
      CatalogSearchField.LIBRARY_ID -> equality("s.library_id", predicate)
      CatalogSearchField.COLLECTION_ID ->
        membership(
          predicate,
          """
          SELECT member.series_id
          FROM series_collection_member member
          WHERE member.collection_id = ?
          """.trimIndent(),
        )
      CatalogSearchField.DELETED ->
        booleanNullability("s.deleted_at_ms", predicate, trueMeansNotNull = true)
      CatalogSearchField.COMPLETE -> complete(predicate)
      CatalogSearchField.ONE_SHOT -> booleanValue("s.oneshot", predicate)
      CatalogSearchField.TITLE -> stringValue("sm.title", predicate)
      CatalogSearchField.TITLE_SORT -> stringValue("sm.title_sort", predicate)
      CatalogSearchField.RELEASE_DATE -> seriesReleaseDate(predicate)
      CatalogSearchField.TAG -> seriesTag(predicate)
      CatalogSearchField.SHARING_LABEL ->
        nullableRelation(
          ownerColumn = "s.id",
          relationTable = "series_metadata_sharing_label",
          relationOwnerColumn = "series_id",
          relationValueColumn = "sharing_label",
          predicate = predicate,
        )
      CatalogSearchField.PUBLISHER -> equality("sm.publisher", predicate, ignoreCase = true)
      CatalogSearchField.LANGUAGE -> equality("sm.language", predicate, ignoreCase = true)
      CatalogSearchField.GENRE ->
        nullableRelation(
          ownerColumn = "s.id",
          relationTable = "series_metadata_genre",
          relationOwnerColumn = "series_id",
          relationValueColumn = "genre",
          predicate = predicate,
        )
      CatalogSearchField.AGE_RATING -> numericValue("sm.age_rating", predicate)
      CatalogSearchField.READ_STATUS -> seriesReadStatus(predicate, access)
      CatalogSearchField.SERIES_STATUS -> equality("sm.status", predicate)
      CatalogSearchField.AUTHOR ->
        author(
          ownerColumn = "s.id",
          ownerSelection = "child.series_id",
          from =
            """
            book child
            JOIN book_metadata_author author ON author.book_id = child.id
            """.trimIndent(),
          predicate = predicate,
        )
      else -> throw IllegalArgumentException(
        "Search field ${predicate.field} is not supported for series",
      )
    }

  private fun equality(
    column: String,
    predicate: CatalogSearchCondition.Predicate,
    ignoreCase: Boolean = false,
  ): CatalogSqlCondition {
    val value = requireNotNull(predicate.value)
    val expression = if (ignoreCase) "lower($column)" else column
    val binding = if (ignoreCase) value.lowercase() else value
    return when (predicate.operator) {
      CatalogSearchOperator.IS -> CatalogSqlCondition("$expression = ?", listOf(binding))
      CatalogSearchOperator.IS_NOT ->
        CatalogSqlCondition("($expression <> ? OR $column IS NULL)", listOf(binding))
      else -> invalid(predicate)
    }
  }

  private fun membership(
    predicate: CatalogSearchCondition.Predicate,
    selection: String,
  ): CatalogSqlCondition {
    val prefix =
      when (predicate.field) {
        CatalogSearchField.READ_LIST_ID -> "b.id"
        CatalogSearchField.COLLECTION_ID -> "s.id"
        else -> error("Unsupported membership field")
      }
    val operator =
      when (predicate.operator) {
        CatalogSearchOperator.IS -> "IN"
        CatalogSearchOperator.IS_NOT -> "NOT IN"
        else -> invalid(predicate)
      }
    return CatalogSqlCondition(
      "$prefix $operator ($selection)",
      listOf(requireNotNull(predicate.value)),
    )
  }

  private fun stringValue(
    column: String,
    predicate: CatalogSearchCondition.Predicate,
  ): CatalogSqlCondition {
    val value = requireNotNull(predicate.value)
    val normalized = value.lowercase()
    val (sql, binding) =
      when (predicate.operator) {
        CatalogSearchOperator.IS -> "lower($column) = ?" to normalized
        CatalogSearchOperator.IS_NOT -> "lower($column) <> ?" to normalized
        CatalogSearchOperator.CONTAINS ->
          "lower($column) LIKE ? ESCAPE '\\'" to "%${normalized.likeEscaped()}%"
        CatalogSearchOperator.DOES_NOT_CONTAIN ->
          "lower($column) NOT LIKE ? ESCAPE '\\'" to "%${normalized.likeEscaped()}%"
        CatalogSearchOperator.BEGINS_WITH ->
          "lower($column) LIKE ? ESCAPE '\\'" to "${normalized.likeEscaped()}%"
        CatalogSearchOperator.DOES_NOT_BEGIN_WITH ->
          "lower($column) NOT LIKE ? ESCAPE '\\'" to "${normalized.likeEscaped()}%"
        CatalogSearchOperator.ENDS_WITH ->
          "lower($column) LIKE ? ESCAPE '\\'" to "%${normalized.likeEscaped()}"
        CatalogSearchOperator.DOES_NOT_END_WITH ->
          "lower($column) NOT LIKE ? ESCAPE '\\'" to "%${normalized.likeEscaped()}"
        else -> invalid(predicate)
      }
    return CatalogSqlCondition(sql, listOf(binding))
  }

  private fun numericValue(
    column: String,
    predicate: CatalogSearchCondition.Predicate,
  ): CatalogSqlCondition =
    when (predicate.operator) {
      CatalogSearchOperator.IS_NULL -> CatalogSqlCondition("$column IS NULL")
      CatalogSearchOperator.IS_NOT_NULL -> CatalogSqlCondition("$column IS NOT NULL")
      else -> {
        val value =
          requireNotNull(predicate.value).toDoubleOrNull()
            ?: throw IllegalArgumentException("${predicate.field} requires a numeric value")
        val operator =
          when (predicate.operator) {
            CatalogSearchOperator.IS -> "="
            CatalogSearchOperator.IS_NOT -> "<>"
            CatalogSearchOperator.GREATER_THAN -> ">"
            CatalogSearchOperator.LESS_THAN -> "<"
            else -> invalid(predicate)
          }
        CatalogSqlCondition("$column $operator ?", listOf(value))
      }
    }

  private fun dateValue(
    column: String,
    predicate: CatalogSearchCondition.Predicate,
  ): CatalogSqlCondition =
    when (predicate.operator) {
      CatalogSearchOperator.IS_NULL -> CatalogSqlCondition("$column IS NULL")
      CatalogSearchOperator.IS_NOT_NULL -> CatalogSqlCondition("$column IS NOT NULL")
      CatalogSearchOperator.BEFORE ->
        CatalogSqlCondition("$column < ?", listOf(predicate.date()))
      CatalogSearchOperator.AFTER ->
        CatalogSqlCondition("$column > ?", listOf(predicate.date()))
      CatalogSearchOperator.IS_IN_THE_LAST ->
        CatalogSqlCondition("$column >= ?", listOf(predicate.durationThreshold()))
      CatalogSearchOperator.IS_NOT_IN_THE_LAST ->
        CatalogSqlCondition("($column < ? OR $column IS NULL)", listOf(predicate.durationThreshold()))
      else -> invalid(predicate)
    }

  private fun seriesReleaseDate(
    predicate: CatalogSearchCondition.Predicate,
  ): CatalogSqlCondition =
    dateValue(
      column =
        """
        (
          SELECT max(child_metadata.release_date)
          FROM book child
          JOIN book_metadata child_metadata ON child_metadata.book_id = child.id
          WHERE child.series_id = s.id
        )
        """.trimIndent(),
      predicate = predicate,
    )

  private fun nullableRelation(
    ownerColumn: String,
    relationTable: String,
    relationOwnerColumn: String,
    relationValueColumn: String,
    predicate: CatalogSearchCondition.Predicate,
  ): CatalogSqlCondition {
    val matching =
      when (predicate.operator) {
        CatalogSearchOperator.IS,
        CatalogSearchOperator.IS_NOT,
        ->
          "AND lower(relation.$relationValueColumn) = ?"
        CatalogSearchOperator.IS_NULL,
        CatalogSearchOperator.IS_NOT_NULL,
        -> ""
        else -> invalid(predicate)
      }
    val exists =
      when (predicate.operator) {
        CatalogSearchOperator.IS,
        CatalogSearchOperator.IS_NOT_NULL,
        -> "EXISTS"
        CatalogSearchOperator.IS_NOT,
        CatalogSearchOperator.IS_NULL,
        -> "NOT EXISTS"
        else -> invalid(predicate)
      }
    return CatalogSqlCondition(
      """
      $exists (
        SELECT 1 FROM $relationTable relation
        WHERE relation.$relationOwnerColumn = $ownerColumn
        $matching
      )
      """.trimIndent(),
      if (matching.isEmpty()) emptyList() else listOf(requireNotNull(predicate.value).lowercase()),
    )
  }

  private fun seriesTag(predicate: CatalogSearchCondition.Predicate): CatalogSqlCondition {
    val valueFilter =
      if (
        predicate.operator == CatalogSearchOperator.IS ||
        predicate.operator == CatalogSearchOperator.IS_NOT
      ) {
        "AND lower(tag_value) = ?"
      } else {
        ""
      }
    val anyMatch =
      """
      EXISTS (
        SELECT 1
        FROM (
          SELECT series_tag.tag AS tag_value
          FROM series_metadata_tag series_tag
          WHERE series_tag.series_id = s.id
          UNION ALL
          SELECT book_tag.tag AS tag_value
          FROM book child
          JOIN book_metadata_tag book_tag ON book_tag.book_id = child.id
          WHERE child.series_id = s.id
        ) combined_tag
        WHERE 1 = 1 $valueFilter
      )
      """.trimIndent()
    val sql =
      when (predicate.operator) {
      CatalogSearchOperator.IS,
      CatalogSearchOperator.IS_NOT_NULL,
      -> anyMatch
      CatalogSearchOperator.IS_NOT,
      CatalogSearchOperator.IS_NULL,
      -> "NOT ($anyMatch)"
      else -> invalid(predicate)
    }
    return CatalogSqlCondition(
      sql,
      if (valueFilter.isEmpty()) emptyList() else listOf(requireNotNull(predicate.value).lowercase()),
    )
  }

  private fun equalitySubquery(
    ownerColumn: String,
    table: String,
    ownerField: String,
    valueField: String,
    predicate: CatalogSearchCondition.Predicate,
  ): CatalogSqlCondition {
    val exists =
      if (predicate.operator == CatalogSearchOperator.IS) "EXISTS" else "NOT EXISTS"
    return CatalogSqlCondition(
      """
      $exists (
        SELECT 1 FROM $table relation
        WHERE relation.$ownerField = $ownerColumn
          AND relation.$valueField = ?
      )
      """.trimIndent(),
      listOf(requireNotNull(predicate.value)),
    )
  }

  private fun author(
    ownerColumn: String,
    ownerSelection: String,
    from: String,
    predicate: CatalogSearchCondition.Predicate,
  ): CatalogSqlCondition {
    val filters = mutableListOf<String>()
    val bindings = mutableListOf<Any?>()
    predicate.attributes["name"]?.takeIf(String::isNotBlank)?.let {
      filters += "lower(author.name) = ?"
      bindings += it.lowercase()
    }
    predicate.attributes["role"]?.takeIf(String::isNotBlank)?.let {
      filters += "lower(author.role) = ?"
      bindings += it.lowercase()
    }
    if (filters.isEmpty()) return CatalogSqlCondition("1 = 1")
    val membership =
      if (predicate.operator == CatalogSearchOperator.IS) "IN" else "NOT IN"
    return CatalogSqlCondition(
      """
      $ownerColumn $membership (
        SELECT $ownerSelection
        FROM $from
        WHERE ${filters.joinToString(" AND ")}
      )
      """.trimIndent(),
      bindings,
    )
  }

  private fun poster(predicate: CatalogSearchCondition.Predicate): CatalogSqlCondition {
    val filters = mutableListOf<String>()
    val bindings = mutableListOf<Any?>()
    predicate.attributes["type"]?.let {
      filters += "artwork.artwork_type = ?"
      bindings += it
    }
    predicate.attributes["selected"]?.let {
      filters += "artwork.selected = ?"
      bindings += if (it.toBooleanStrict()) 1 else 0
    }
    if (filters.isEmpty()) return CatalogSqlCondition("1 = 1")
    val exists = if (predicate.operator == CatalogSearchOperator.IS) "EXISTS" else "NOT EXISTS"
    return CatalogSqlCondition(
      """
      $exists (
        SELECT 1 FROM artwork_thumbnail artwork
        WHERE artwork.owner_kind = 'MEDIA_ITEM'
          AND artwork.owner_id = b.id
          AND ${filters.joinToString(" AND ")}
      )
      """.trimIndent(),
      bindings,
    )
  }

  private fun complete(predicate: CatalogSearchCondition.Predicate): CatalogSqlCondition =
    if (predicate.operator == CatalogSearchOperator.IS_TRUE) {
      CatalogSqlCondition(
        "sm.total_book_count IS NOT NULL AND sm.total_book_count = s.book_count",
      )
    } else {
      CatalogSqlCondition(
        "sm.total_book_count IS NOT NULL AND sm.total_book_count <> s.book_count",
      )
    }

  private fun bookReadStatus(
    predicate: CatalogSearchCondition.Predicate,
    access: CatalogAccess,
  ): CatalogSqlCondition {
    val userId = access.userId ?: return CatalogSqlCondition("1 = 0")
    val status = requireNotNull(predicate.value).uppercase()
    val positive =
      when (status) {
        "UNREAD" -> "progress.book_id IS NULL"
        "READ" -> "progress.completed = 1"
        "IN_PROGRESS" -> "progress.completed = 0"
        else -> throw IllegalArgumentException("Unknown read status: $status")
      }
    val condition =
      if (predicate.operator == CatalogSearchOperator.IS) positive else "NOT ($positive)"
    return CatalogSqlCondition(
      """
      EXISTS (
        SELECT 1
        FROM (SELECT b.id AS book_id) current_book
        LEFT JOIN read_progress progress
          ON progress.book_id = current_book.book_id AND progress.user_id = ?
        WHERE $condition
      )
      """.trimIndent(),
      listOf(userId.value),
    )
  }

  private fun seriesReadStatus(
    predicate: CatalogSearchCondition.Predicate,
    access: CatalogAccess,
  ): CatalogSqlCondition {
    val userId = access.userId ?: return CatalogSqlCondition("1 = 0")
    val status = requireNotNull(predicate.value).uppercase()
    val positive =
      when (status) {
        "UNREAD" -> "progress.series_id IS NULL"
        "READ" -> "progress.books_read_count = s.book_count"
        "IN_PROGRESS" ->
          "progress.series_id IS NOT NULL AND progress.books_read_count <> s.book_count"
        else -> throw IllegalArgumentException("Unknown read status: $status")
      }
    val condition =
      if (predicate.operator == CatalogSearchOperator.IS) positive else "NOT ($positive)"
    return CatalogSqlCondition(
      """
      EXISTS (
        SELECT 1
        FROM (SELECT s.id AS series_id) current_series
        LEFT JOIN read_progress_series progress
          ON progress.series_id = current_series.series_id AND progress.user_id = ?
        WHERE $condition
      )
      """.trimIndent(),
      listOf(userId.value),
    )
  }

  private fun booleanNullability(
    column: String,
    predicate: CatalogSearchCondition.Predicate,
    trueMeansNotNull: Boolean,
  ): CatalogSqlCondition {
    val wantsTrue = predicate.operator == CatalogSearchOperator.IS_TRUE
    val isNotNull = wantsTrue == trueMeansNotNull
    return CatalogSqlCondition("$column IS ${if (isNotNull) "NOT " else ""}NULL")
  }

  private fun booleanValue(
    column: String,
    predicate: CatalogSearchCondition.Predicate,
  ): CatalogSqlCondition =
    CatalogSqlCondition(
      "$column = ?",
      listOf(if (predicate.operator == CatalogSearchOperator.IS_TRUE) 1 else 0),
    )

  private fun CatalogSearchCondition.Predicate.date(): String =
    ZonedDateTime.parse(requireNotNull(value)).toLocalDate().toString()

  private fun CatalogSearchCondition.Predicate.durationThreshold(): String {
    val now = currentTimeMillis().also { require(it >= 0) }
    return Instant
      .ofEpochMilli(now)
      .atZone(ZoneOffset.UTC)
      .minus(Duration.parse(requireNotNull(value)))
      .toLocalDate()
      .toString()
  }

  private fun String.likeEscaped(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  private fun invalid(predicate: CatalogSearchCondition.Predicate): Nothing =
    throw IllegalArgumentException(
      "Operator ${predicate.operator} is not valid for ${predicate.field}",
    )
}
