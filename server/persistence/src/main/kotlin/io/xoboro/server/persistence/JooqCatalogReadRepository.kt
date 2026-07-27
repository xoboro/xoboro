package io.xoboro.server.persistence

import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookMetadataAggregation
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogGroupCount
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadataRepository
import io.xoboro.core.domain.SeriesRepository

class JooqCatalogReadRepository(
  private val database: XoboroDatabase,
  private val books: BookRepository = JooqBookRepository(database),
  private val series: SeriesRepository = JooqSeriesRepository(database),
  private val bookMetadata: BookMetadataRepository = JooqBookMetadataRepository(database),
  private val seriesMetadata: SeriesMetadataRepository = JooqSeriesMetadataRepository(database),
  private val media: BookMediaRepository = JooqBookMediaRepository(database),
) : CatalogReadRepository {
  override fun findBooks(
    query: BookCatalogQuery,
    access: CatalogAccess,
    page: CatalogPageRequest,
  ): CatalogPage<CatalogBook> {
    val filter = bookFilter(query, access)
    val total = count("book b JOIN series s ON s.id = b.series_id JOIN book_metadata bm ON bm.book_id = b.id JOIN series_metadata sm ON sm.series_id = s.id", filter)
    val ids =
      database.dsl
        .fetch(
          """
          SELECT b.id
          FROM book b
          JOIN series s ON s.id = b.series_id
          JOIN book_metadata bm ON bm.book_id = b.id
          JOIN series_metadata sm ON sm.series_id = s.id
          WHERE ${filter.sql}
          ORDER BY ${bookOrder(page.sorts)}
          ${page.limitClause(filter.bindings)}
          """.trimIndent(),
          *filter.bindings.toTypedArray(),
        ).map { BookId(requireNotNull(it.get("id", String::class.java))) }
    return CatalogPage(
      content = ids.mapNotNull(::hydrateBook),
      page = if (page.unpaged) 0 else page.page,
      size = if (page.unpaged) ids.size.coerceAtLeast(1) else page.size,
      totalElements = total,
      unpaged = page.unpaged,
    )
  }

  override fun findBookByIdOrNull(
    id: BookId,
    access: CatalogAccess,
  ): CatalogBook? =
    findSingleBook(id, access)

  override fun findPreviousBookOrNull(
    id: BookId,
    access: CatalogAccess,
  ): CatalogBook? =
    findSibling(id, access, previous = true)

  override fun findNextBookOrNull(
    id: BookId,
    access: CatalogAccess,
  ): CatalogBook? =
    findSibling(id, access, previous = false)

  override fun findSeries(
    query: SeriesCatalogQuery,
    access: CatalogAccess,
    page: CatalogPageRequest,
  ): CatalogPage<CatalogSeries> {
    val filter = seriesFilter(query, access)
    val total = count("series s JOIN series_metadata sm ON sm.series_id = s.id", filter)
    val ids =
      database.dsl
        .fetch(
          """
          SELECT s.id
          FROM series s
          JOIN series_metadata sm ON sm.series_id = s.id
          WHERE ${filter.sql}
          ORDER BY ${seriesOrder(page.sorts)}
          ${page.limitClause(filter.bindings)}
          """.trimIndent(),
          *filter.bindings.toTypedArray(),
        ).map { SeriesId(requireNotNull(it.get("id", String::class.java))) }
    return CatalogPage(
      content = ids.mapNotNull(::hydrateSeries),
      page = if (page.unpaged) 0 else page.page,
      size = if (page.unpaged) ids.size.coerceAtLeast(1) else page.size,
      totalElements = total,
      unpaged = page.unpaged,
    )
  }

  override fun findSeriesByIdOrNull(
    id: SeriesId,
    access: CatalogAccess,
  ): CatalogSeries? {
    val filter =
      seriesFilter(
        SeriesCatalogQuery(deleted = null),
        access,
        extraSql = "s.id = ?",
        extraBindings = listOf(id.value),
      )
    val found =
      database.dsl.fetchOne(
        """
        SELECT s.id
        FROM series s
        JOIN series_metadata sm ON sm.series_id = s.id
        WHERE ${filter.sql}
        """.trimIndent(),
        *filter.bindings.toTypedArray(),
      ) ?: return null
    return hydrateSeries(SeriesId(requireNotNull(found.get("id", String::class.java))))
  }

  override fun countSeriesByFirstCharacter(
    query: SeriesCatalogQuery,
    access: CatalogAccess,
  ): List<CatalogGroupCount> {
    val filter = seriesFilter(query, access)
    return database.dsl
      .fetch(
        """
        SELECT
          CASE
            WHEN trim(sm.title_sort) = '' THEN '#'
            ELSE upper(substr(trim(sm.title_sort), 1, 1))
          END AS group_name,
          count(*) AS group_count
        FROM series s
        JOIN series_metadata sm ON sm.series_id = s.id
        WHERE ${filter.sql}
        GROUP BY group_name
        ORDER BY group_name
        """.trimIndent(),
        *filter.bindings.toTypedArray(),
      ).map {
        CatalogGroupCount(
          group = requireNotNull(it.get("group_name", String::class.java)),
          count = (requireNotNull(it.get("group_count")) as Number).toInt(),
        )
      }
  }

  private fun findSingleBook(
    id: BookId,
    access: CatalogAccess,
  ): CatalogBook? {
    val filter =
      bookFilter(
        BookCatalogQuery(deleted = null),
        access,
        extraSql = "b.id = ?",
        extraBindings = listOf(id.value),
      )
    val found =
      database.dsl.fetchOne(
        """
        SELECT b.id
        FROM book b
        JOIN series s ON s.id = b.series_id
        JOIN book_metadata bm ON bm.book_id = b.id
        JOIN series_metadata sm ON sm.series_id = s.id
        WHERE ${filter.sql}
        """.trimIndent(),
        *filter.bindings.toTypedArray(),
      ) ?: return null
    return hydrateBook(BookId(requireNotNull(found.get("id", String::class.java))))
  }

  private fun findSibling(
    id: BookId,
    access: CatalogAccess,
    previous: Boolean,
  ): CatalogBook? {
    val current = findSingleBook(id, access) ?: return null
    val comparison = if (previous) "<" else ">"
    val order = if (previous) "DESC" else "ASC"
    val filter =
      bookFilter(
        BookCatalogQuery(seriesId = current.book.seriesId, deleted = false),
        access,
        extraSql =
          """
          (
            bm.number_sort $comparison ?
            OR (
              bm.number_sort = ?
              AND (
                b.relative_uri $comparison ?
                OR (b.relative_uri = ? AND b.id $comparison ?)
              )
            )
          )
          """.trimIndent(),
        extraBindings =
          listOf(
            current.metadata.numberSort,
            current.metadata.numberSort,
            current.book.relativePath,
            current.book.relativePath,
            current.book.id.value,
          ),
      )
    val found =
      database.dsl.fetchOne(
        """
        SELECT b.id
        FROM book b
        JOIN series s ON s.id = b.series_id
        JOIN book_metadata bm ON bm.book_id = b.id
        JOIN series_metadata sm ON sm.series_id = s.id
        WHERE ${filter.sql}
        ORDER BY bm.number_sort $order, b.relative_uri $order, b.id $order
        LIMIT 1
        """.trimIndent(),
        *filter.bindings.toTypedArray(),
      ) ?: return null
    return hydrateBook(BookId(requireNotNull(found.get("id", String::class.java))))
  }

  private fun hydrateBook(id: BookId): CatalogBook? {
    val book = books.findByIdOrNull(id) ?: return null
    val parent = series.findByIdOrNull(book.seriesId) ?: return null
    val metadata = bookMetadata.findByBookIdOrNull(id) ?: return null
    val parentMetadata = seriesMetadata.findBySeriesIdOrNull(parent.id) ?: return null
    return CatalogBook(
      book = book,
      seriesTitle = parentMetadata.title,
      metadata = metadata,
      media = media.findByBookIdOrNull(id),
    )
  }

  private fun hydrateSeries(id: SeriesId): CatalogSeries? {
    val item = series.findByIdOrNull(id) ?: return null
    val metadata = seriesMetadata.findBySeriesIdOrNull(id) ?: return null
    val summary =
      database.dsl.fetchOne(
        """
        SELECT bm.summary, bm.number
        FROM book b
        JOIN book_metadata bm ON bm.book_id = b.id
        WHERE b.series_id = ? AND b.deleted_at_ms IS NULL
        ORDER BY bm.number_sort, b.relative_uri, b.id
        LIMIT 1
        """.trimIndent(),
        id.value,
      )
    val timestamps =
      database.dsl.fetchOne(
        """
        SELECT
          min(bm.created_at_ms) AS minimum_created,
          max(bm.updated_at_ms) AS maximum_updated
        FROM book b
        JOIN book_metadata bm ON bm.book_id = b.id
        WHERE b.series_id = ? AND b.deleted_at_ms IS NULL
        """.trimIndent(),
        id.value,
      )
    val authors =
      database.dsl
        .fetch(
          """
          SELECT DISTINCT author.name, author.role
          FROM book b
          JOIN book_metadata_author author ON author.book_id = b.id
          WHERE b.series_id = ? AND b.deleted_at_ms IS NULL
          ORDER BY lower(author.name), lower(author.role)
          """.trimIndent(),
          id.value,
        ).map {
          Author(
            name = requireNotNull(it.get("name", String::class.java)),
            role = requireNotNull(it.get("role", String::class.java)),
          )
        }
    val tags =
      database.dsl
        .fetch(
          """
          SELECT DISTINCT tag.tag
          FROM book b
          JOIN book_metadata_tag tag ON tag.book_id = b.id
          WHERE b.series_id = ? AND b.deleted_at_ms IS NULL
          ORDER BY tag.tag
          """.trimIndent(),
          id.value,
        ).mapTo(linkedSetOf()) { requireNotNull(it.get("tag", String::class.java)) }
    val releaseDate =
      database.dsl
        .fetchOne(
          """
          SELECT max(bm.release_date) AS release_date
          FROM book b
          JOIN book_metadata bm ON bm.book_id = b.id
          WHERE b.series_id = ? AND b.deleted_at_ms IS NULL
          """.trimIndent(),
          id.value,
        )?.get("release_date", String::class.java)
    return CatalogSeries(
      series = item,
      metadata = metadata,
      booksMetadata =
        BookMetadataAggregation(
          authors = authors,
          tags = tags,
          releaseDate = releaseDate,
          summary = summary?.get("summary", String::class.java).orEmpty(),
          summaryNumber = summary?.get("number", String::class.java).orEmpty(),
          createdAtMillis =
            timestamps?.get("minimum_created").asLongOrNull() ?: item.createdAtMillis,
          updatedAtMillis =
            timestamps?.get("maximum_updated").asLongOrNull() ?: item.updatedAtMillis,
        ),
    )
  }

  private fun bookFilter(
    query: BookCatalogQuery,
    access: CatalogAccess,
    extraSql: String? = null,
    extraBindings: List<Any?> = emptyList(),
  ): SqlFilter {
    val parts = mutableListOf<String>()
    val bindings = mutableListOf<Any?>()
    addLibraryFilter(parts, bindings, "b.library_id", query.libraryIds, access.libraryIds)
    query.seriesId?.let {
      parts += "b.series_id = ?"
      bindings += it.value
    }
    query.deleted?.let {
      parts += if (it) "b.deleted_at_ms IS NOT NULL" else "b.deleted_at_ms IS NULL"
    }
    query.fullTextSearch?.trim()?.takeIf(String::isNotEmpty)?.let {
      parts +=
        """
        (
          lower(b.name) LIKE ? ESCAPE '\'
          OR lower(bm.title) LIKE ? ESCAPE '\'
          OR lower(s.name) LIKE ? ESCAPE '\'
          OR lower(sm.title) LIKE ? ESCAPE '\'
        )
        """.trimIndent()
      repeat(4) { _ -> bindings += it.likePattern() }
    }
    addContentRestriction(parts, bindings, access.restrictions)
    extraSql?.let {
      parts += it
      bindings.addAll(extraBindings)
    }
    return SqlFilter(parts.ifEmpty { listOf("1 = 1") }.joinToString(" AND "), bindings)
  }

  private fun seriesFilter(
    query: SeriesCatalogQuery,
    access: CatalogAccess,
    extraSql: String? = null,
    extraBindings: List<Any?> = emptyList(),
  ): SqlFilter {
    val parts = mutableListOf<String>()
    val bindings = mutableListOf<Any?>()
    addLibraryFilter(parts, bindings, "s.library_id", query.libraryIds, access.libraryIds)
    query.deleted?.let {
      parts += if (it) "s.deleted_at_ms IS NOT NULL" else "s.deleted_at_ms IS NULL"
    }
    query.oneshot?.let {
      parts += "s.oneshot = ?"
      bindings += it.toSqliteInt()
    }
    query.fullTextSearch?.trim()?.takeIf(String::isNotEmpty)?.let {
      parts += "(lower(s.name) LIKE ? ESCAPE '\\' OR lower(sm.title) LIKE ? ESCAPE '\\')"
      repeat(2) { _ -> bindings += it.likePattern() }
    }
    addAnyValue(parts, bindings, "lower(sm.publisher)", query.publishers)
    addAnyValue(parts, bindings, "lower(sm.language)", query.languages)
    addRelationValues(parts, bindings, "series_metadata_genre", "genre", query.genres)
    addRelationValues(parts, bindings, "series_metadata_tag", "tag", query.tags)
    addContentRestriction(parts, bindings, access.restrictions)
    extraSql?.let {
      parts += it
      bindings.addAll(extraBindings)
    }
    return SqlFilter(parts.ifEmpty { listOf("1 = 1") }.joinToString(" AND "), bindings)
  }

  private fun addLibraryFilter(
    parts: MutableList<String>,
    bindings: MutableList<Any?>,
    column: String,
    requested: Set<LibraryId>,
    authorized: Set<LibraryId>?,
  ) {
    val effective =
      when {
        authorized == null -> requested.takeIf { it.isNotEmpty() }
        requested.isEmpty() -> authorized
        else -> authorized.intersect(requested)
      } ?: return
    if (effective.isEmpty()) {
      parts += "1 = 0"
    } else {
      parts += "$column IN (${effective.placeholders()})"
      bindings.addAll(effective.map(LibraryId::value))
    }
  }

  private fun addContentRestriction(
    parts: MutableList<String>,
    bindings: MutableList<Any?>,
    restrictions: ContentRestrictions,
  ) {
    val age = restrictions.ageRestriction
    val allowedLabels = restrictions.labelsAllow
    if (age?.mode == RestrictionMode.ALLOW_ONLY || allowedLabels.isNotEmpty()) {
      val positive = mutableListOf<String>()
      if (age?.mode == RestrictionMode.ALLOW_ONLY) {
        positive += "(sm.age_rating IS NOT NULL AND sm.age_rating <= ?)"
        bindings += age.age
      }
      if (allowedLabels.isNotEmpty()) {
        positive +=
          """
          EXISTS (
            SELECT 1
            FROM series_metadata_sharing_label allowed_label
            WHERE allowed_label.series_id = sm.series_id
              AND allowed_label.sharing_label IN (${allowedLabels.placeholders()})
          )
          """.trimIndent()
        bindings.addAll(allowedLabels)
      }
      parts += "(${positive.joinToString(" OR ")})"
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
          WHERE excluded_label.series_id = sm.series_id
            AND excluded_label.sharing_label IN (${restrictions.labelsExclude.placeholders()})
        )
        """.trimIndent()
      bindings.addAll(restrictions.labelsExclude)
    }
  }

  private fun addAnyValue(
    parts: MutableList<String>,
    bindings: MutableList<Any?>,
    column: String,
    values: Set<String>,
  ) {
    val normalized = values.normalized()
    if (normalized.isEmpty()) return
    parts += "$column IN (${normalized.placeholders()})"
    bindings.addAll(normalized)
  }

  private fun addRelationValues(
    parts: MutableList<String>,
    bindings: MutableList<Any?>,
    table: String,
    column: String,
    values: Set<String>,
  ) {
    val normalized = values.normalized()
    if (normalized.isEmpty()) return
    parts +=
      """
      EXISTS (
        SELECT 1
        FROM $table relation_value
        WHERE relation_value.series_id = sm.series_id
          AND relation_value.$column IN (${normalized.placeholders()})
      )
      """.trimIndent()
    bindings.addAll(normalized)
  }

  private fun count(
    from: String,
    filter: SqlFilter,
  ): Long =
    (requireNotNull(
      database.dsl.fetchOne(
        "SELECT count(*) AS item_count FROM $from WHERE ${filter.sql}",
        *filter.bindings.toTypedArray(),
      )?.get("item_count"),
    ) as Number).toLong()

  private fun bookOrder(sorts: List<CatalogSort>): String =
    order(
      sorts = sorts,
      mappings =
        mapOf(
          "created" to "b.created_at_ms",
          "fileLastModified" to "b.file_modified_ms",
          "lastModified" to "b.updated_at_ms",
          "name" to "b.name COLLATE NOCASE",
          "number" to "bm.number_sort",
          "numberSort" to "bm.number_sort",
          "seriesTitle" to "sm.title_sort COLLATE NOCASE",
          "sizeBytes" to "b.file_size",
          "title" to "bm.title COLLATE NOCASE",
        ),
      fallback = "sm.title_sort COLLATE NOCASE ASC, bm.number_sort ASC, b.relative_uri ASC, b.id ASC",
    )

  private fun seriesOrder(sorts: List<CatalogSort>): String =
    order(
      sorts = sorts,
      mappings =
        mapOf(
          "booksCount" to "s.book_count",
          "created" to "s.created_at_ms",
          "fileLastModified" to "s.file_modified_ms",
          "lastModified" to "s.updated_at_ms",
          "name" to "s.name COLLATE NOCASE",
          "title" to "sm.title COLLATE NOCASE",
          "titleSort" to "sm.title_sort COLLATE NOCASE",
        ),
      fallback = "sm.title_sort COLLATE NOCASE ASC, s.id ASC",
    )

  private fun order(
    sorts: List<CatalogSort>,
    mappings: Map<String, String>,
    fallback: String,
  ): String {
    if (sorts.isEmpty()) return fallback
    return sorts.joinToString(", ") { sort ->
      val column =
        mappings[sort.property]
          ?: throw IllegalArgumentException("Unsupported catalog sort property: ${sort.property}")
      val direction =
        when (sort.direction) {
          CatalogSortDirection.ASC -> "ASC"
          CatalogSortDirection.DESC -> "DESC"
        }
      "$column $direction"
    }
  }

  private fun CatalogPageRequest.limitClause(bindings: MutableList<Any?>): String {
    if (unpaged) return ""
    bindings += size
    bindings += page.toLong() * size
    return "LIMIT ? OFFSET ?"
  }

  private fun String.likePattern(): String =
    "%" +
      lowercase()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") +
      "%"

  private fun Set<String>.normalized(): Set<String> =
    asSequence().map(String::trim).filter(String::isNotEmpty).map(String::lowercase).toSet()

  private fun Collection<*>.placeholders(): String = joinToString(",") { "?" }

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0

  private fun Any?.asLongOrNull(): Long? = (this as? Number)?.toLong()

  private data class SqlFilter(
    val sql: String,
    val bindings: MutableList<Any?>,
  )
}
