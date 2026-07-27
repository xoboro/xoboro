package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.MetadataFacet
import io.xoboro.core.application.MetadataFacetQuery
import io.xoboro.core.application.MetadataFacetRepository
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.RestrictionMode

class JooqMetadataFacetRepository(
  private val database: XoboroDatabase,
) : MetadataFacetRepository {
  override fun findValues(
    facet: MetadataFacet,
    query: MetadataFacetQuery,
    access: CatalogAccess,
  ): List<String> {
    val bookLevel = facet == MetadataFacet.BOOK_TAG || facet == MetadataFacet.RELEASE_YEAR
    val filter = filter(query, access, bookLevel)
    val projection =
      when (facet) {
        MetadataFacet.GENRE ->
          ValueProjection("relation.genre", "series_metadata_genre relation ON relation.series_id = s.id")
        MetadataFacet.SERIES_TAG ->
          ValueProjection("relation.tag", "series_metadata_tag relation ON relation.series_id = s.id")
        MetadataFacet.BOOK_TAG ->
          ValueProjection("relation.tag", "book_metadata_tag relation ON relation.book_id = b.id")
        MetadataFacet.LANGUAGE -> ValueProjection("sm.language")
        MetadataFacet.PUBLISHER -> ValueProjection("sm.publisher")
        MetadataFacet.AGE_RATING ->
          ValueProjection(
            "CASE WHEN sm.age_rating IS NULL THEN 'None' ELSE CAST(sm.age_rating AS TEXT) END",
          )
        MetadataFacet.SHARING_LABEL ->
          ValueProjection(
            "relation.sharing_label",
            "series_metadata_sharing_label relation ON relation.series_id = s.id",
          )
        MetadataFacet.RELEASE_YEAR ->
          ValueProjection("substr(bm.release_date, 1, 4)")
      }
    val joins =
      buildList {
        add("JOIN series_metadata sm ON sm.series_id = s.id")
        if (bookLevel) {
          add("JOIN book b ON b.series_id = s.id")
          add("JOIN book_metadata bm ON bm.book_id = b.id")
        }
        projection.relationJoin?.let { add("JOIN $it") }
      }.joinToString("\n")
    val nonBlank =
      when (facet) {
        MetadataFacet.AGE_RATING -> "1 = 1"
        else -> "trim(${projection.expression}) <> ''"
      }
    return database.dsl
      .fetch(
        """
        SELECT DISTINCT ${projection.expression} AS facet_value
        FROM series s
        $joins
        WHERE ${filter.sql}
          AND $nonBlank
        ORDER BY facet_value COLLATE NOCASE
        """.trimIndent(),
        *filter.bindings.toTypedArray(),
      ).mapNotNull { it.get("facet_value", String::class.java) }
  }

  override fun findAuthors(
    query: MetadataFacetQuery,
    access: CatalogAccess,
    page: CatalogPageRequest,
  ): CatalogPage<Author> {
    val filter = filter(query, access, bookLevel = true)
    val parts = mutableListOf(filter.sql)
    val bindings = filter.bindings
    query.search?.trim()?.takeIf(String::isNotEmpty)?.let {
      parts += "lower(author.name) LIKE ? ESCAPE '\\'"
      bindings += it.likePattern()
    }
    query.role?.trim()?.takeIf(String::isNotEmpty)?.let {
      parts += "lower(author.role) = ?"
      bindings += it.lowercase()
    }
    val where = parts.joinToString(" AND ")
    val from =
      """
      FROM book_metadata_author author
      JOIN book b ON b.id = author.book_id
      JOIN series s ON s.id = b.series_id
      JOIN series_metadata sm ON sm.series_id = s.id
      WHERE $where
      """.trimIndent()
    val total =
      (requireNotNull(
        database.dsl
          .fetchOne(
            "SELECT count(*) AS item_count FROM (SELECT DISTINCT author.name, author.role $from)",
            *bindings.toTypedArray(),
          )?.get("item_count"),
      ) as Number).toLong()
    val limitBindings = bindings.toMutableList()
    val limit =
      if (page.unpaged) {
        ""
      } else {
        limitBindings += page.size
        limitBindings += page.page.toLong() * page.size
        "LIMIT ? OFFSET ?"
      }
    val authors =
      database.dsl
        .fetch(
          """
          SELECT DISTINCT author.name, author.role
          $from
          ORDER BY lower(author.name), lower(author.role)
          $limit
          """.trimIndent(),
          *limitBindings.toTypedArray(),
        ).map {
          Author(
            name = requireNotNull(it.get("name", String::class.java)),
            role = requireNotNull(it.get("role", String::class.java)),
          )
        }
    return CatalogPage(
      content = authors,
      page = if (page.unpaged) 0 else page.page,
      size = if (page.unpaged) authors.size.coerceAtLeast(1) else page.size,
      totalElements = total,
      unpaged = page.unpaged,
    )
  }

  private fun filter(
    query: MetadataFacetQuery,
    access: CatalogAccess,
    bookLevel: Boolean,
  ): SqlFilter {
    val parts = mutableListOf("s.deleted_at_ms IS NULL")
    val bindings = mutableListOf<Any?>()
    if (bookLevel) parts += "b.deleted_at_ms IS NULL"
    addLibraryFilter(parts, bindings, query.libraryIds, access.libraryIds)
    query.collectionId?.let {
      parts +=
        """
        EXISTS (
          SELECT 1
          FROM series_collection_member collection_member
          WHERE collection_member.collection_id = ?
            AND collection_member.series_id = s.id
        )
        """.trimIndent()
      bindings += it.value
    }
    query.seriesId?.let {
      parts += "s.id = ?"
      bindings += it.value
    }
    query.readListId?.takeIf { bookLevel }?.let {
      parts +=
        """
        EXISTS (
          SELECT 1
          FROM read_list_member read_list_member
          WHERE read_list_member.read_list_id = ?
            AND read_list_member.book_id = b.id
        )
        """.trimIndent()
      bindings += it.value
    }
    addContentRestriction(parts, bindings, access.restrictions)
    return SqlFilter(parts.joinToString(" AND "), bindings)
  }

  private fun addLibraryFilter(
    parts: MutableList<String>,
    bindings: MutableList<Any?>,
    requested: Set<LibraryId>,
    authorized: Set<LibraryId>?,
  ) {
    val effective =
      when {
        authorized == null -> requested.takeIf(Set<LibraryId>::isNotEmpty)
        requested.isEmpty() -> authorized
        else -> authorized.intersect(requested)
      } ?: return
    if (effective.isEmpty()) {
      parts += "1 = 0"
    } else {
      parts += "s.library_id IN (${effective.joinToString(",") { "?" }})"
      bindings.addAll(effective.map(LibraryId::value))
    }
  }

  private fun addContentRestriction(
    parts: MutableList<String>,
    bindings: MutableList<Any?>,
    restrictions: ContentRestrictions,
  ) {
    val age = restrictions.ageRestriction
    if (age?.mode == RestrictionMode.ALLOW_ONLY || restrictions.labelsAllow.isNotEmpty()) {
      val allowed = mutableListOf<String>()
      if (age?.mode == RestrictionMode.ALLOW_ONLY) {
        allowed += "(sm.age_rating IS NOT NULL AND sm.age_rating <= ?)"
        bindings += age.age
      }
      if (restrictions.labelsAllow.isNotEmpty()) {
        allowed +=
          """
          EXISTS (
            SELECT 1
            FROM series_metadata_sharing_label allowed_label
            WHERE allowed_label.series_id = s.id
              AND allowed_label.sharing_label IN (
                ${restrictions.labelsAllow.joinToString(",") { "?" }}
              )
          )
          """.trimIndent()
        bindings.addAll(restrictions.labelsAllow)
      }
      parts += "(${allowed.joinToString(" OR ")})"
    }
    if (age?.mode == RestrictionMode.EXCLUDE) {
      parts += "(sm.age_rating IS NULL OR sm.age_rating < ?)"
      bindings += age.age
    }
    if (restrictions.labelsExclude.isNotEmpty()) {
      parts +=
        """
        NOT EXISTS (
          SELECT 1
          FROM series_metadata_sharing_label excluded_label
          WHERE excluded_label.series_id = s.id
            AND excluded_label.sharing_label IN (
              ${restrictions.labelsExclude.joinToString(",") { "?" }}
            )
        )
        """.trimIndent()
      bindings.addAll(restrictions.labelsExclude)
    }
  }

  private fun String.likePattern(): String =
    "%" +
      lowercase()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") +
      "%"

  private data class ValueProjection(
    val expression: String,
    val relationJoin: String? = null,
  )

  private data class SqlFilter(
    val sql: String,
    val bindings: MutableList<Any?>,
  )
}
