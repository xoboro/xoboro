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
import io.xoboro.core.domain.ReadProgressRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadataRepository
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.UserId

class JooqCatalogReadRepository(
  private val database: XoboroDatabase,
  private val books: BookRepository = JooqBookRepository(database),
  private val series: SeriesRepository = JooqSeriesRepository(database),
  private val bookMetadata: BookMetadataRepository = JooqBookMetadataRepository(database),
  private val seriesMetadata: SeriesMetadataRepository = JooqSeriesMetadataRepository(database),
  private val media: BookMediaRepository = JooqBookMediaRepository(database),
  private val readProgress: ReadProgressRepository = JooqReadProgressRepository(database),
  currentTimeMillis: () -> Long = System::currentTimeMillis,
) : CatalogReadRepository {
  private val structuredSearch = CatalogStructuredSearch(currentTimeMillis)

  override fun findBooks(
    query: BookCatalogQuery,
    access: CatalogAccess,
    page: CatalogPageRequest,
  ): CatalogPage<CatalogBook> {
    val from = bookFrom(query, access)
    val filter = bookFilter(query, access)
    val countFilter =
      SqlFilter(
        filter.sql,
        (from.bindings + filter.bindings).toMutableList(),
      )
    val total = count(from.sql, countFilter)
    val bindings = (from.bindings + filter.bindings).toMutableList()
    val order = bookOrder(page.sorts, query, access)
    val limit = page.limitClause(bindings)
    val ids =
      database.dsl
        .fetch(
          """
          SELECT b.id
          FROM ${from.sql}
          WHERE ${filter.sql}
          ORDER BY $order
          $limit
          """.trimIndent(),
          *bindings.toTypedArray(),
        ).map { BookId(requireNotNull(it.get("id", String::class.java))) }
    return CatalogPage(
      content = hydrateBooks(ids, access.userId),
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
      content = hydrateSeries(ids, access.userId),
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
    return hydrateSeries(
      listOf(SeriesId(requireNotNull(found.get("id", String::class.java)))),
      access.userId,
    ).singleOrNull()
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
    return hydrateBook(
      BookId(requireNotNull(found.get("id", String::class.java))),
      access.userId,
    )
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
    return hydrateBook(
      BookId(requireNotNull(found.get("id", String::class.java))),
      access.userId,
    )
  }

  private fun hydrateBook(
    id: BookId,
    userId: UserId?,
  ): CatalogBook? = hydrateBooks(listOf(id), userId).singleOrNull()

  private fun hydrateBooks(
    ids: List<BookId>,
    userId: UserId?,
  ): List<CatalogBook> {
    if (ids.isEmpty()) return emptyList()
    val items = books.findAllByIds(ids).associateBy { it.id }
    val parentIds = items.values.map { it.seriesId }.distinct()
    val parents = series.findAllByIds(parentIds).associateBy { it.id }
    val metadata = bookMetadata.findAllByBookIds(ids).associateBy { it.bookId }
    val parentMetadata =
      seriesMetadata.findAllBySeriesIds(parentIds).associateBy { it.seriesId }
    val mediaById = media.findAllByBookIds(ids).associateBy { it.bookId }
    val progresses =
      userId
        ?.let { readProgress.findAllByBookIdsAndUserId(ids, it) }
        .orEmpty()
        .associateBy { it.bookId }
    return ids.mapNotNull { id ->
      val book = items[id] ?: return@mapNotNull null
      val parent = parents[book.seriesId] ?: return@mapNotNull null
      val parentDetails = parentMetadata[parent.id] ?: return@mapNotNull null
      CatalogBook(
        book = book,
        seriesTitle = parentDetails.title,
        seriesMetadata = parentDetails,
        metadata = metadata[id] ?: return@mapNotNull null,
        media = mediaById[id],
        readProgress = progresses[id],
      )
    }
  }

  private fun hydrateSeries(
    ids: List<SeriesId>,
    userId: UserId?,
  ): List<CatalogSeries> {
    if (ids.isEmpty()) return emptyList()
    val items = series.findAllByIds(ids).associateBy { it.id }
    val metadata = seriesMetadata.findAllBySeriesIds(ids).associateBy { it.seriesId }
    val aggregations = loadBookMetadataAggregations(ids, items)
    val progresses =
      userId
        ?.let { readProgress.findAllSeriesByIdsAndUserId(ids, it) }
        .orEmpty()
        .associateBy { it.seriesId }
    return ids.mapNotNull { id ->
      val item = items[id] ?: return@mapNotNull null
      CatalogSeries(
        series = item,
        metadata = metadata[id] ?: return@mapNotNull null,
        booksMetadata = requireNotNull(aggregations[id]),
        readProgress = progresses[id],
      )
    }
  }

  private fun loadBookMetadataAggregations(
    ids: Collection<SeriesId>,
    items: Map<SeriesId, io.xoboro.core.domain.Series>,
  ): Map<SeriesId, BookMetadataAggregation> {
    val result =
      items.mapValues { (_, item) ->
        BookMetadataAggregation(
          createdAtMillis = item.createdAtMillis,
          updatedAtMillis = item.updatedAtMillis,
        )
      }.toMutableMap()
    ids.distinct().chunked(QUERY_BATCH_SIZE).forEach { batch ->
      val bindings = batch.map { it.value }.toTypedArray()
      val stats =
        database.dsl
          .fetch(
            """
            WITH ranked_summary AS (
              SELECT
                b.series_id,
                bm.summary,
                bm.number,
                row_number() OVER (
                  PARTITION BY b.series_id
                  ORDER BY bm.number_sort, b.relative_uri, b.id
                ) AS summary_rank
              FROM book b
              JOIN book_metadata bm ON bm.book_id = b.id
              WHERE b.series_id IN (${batch.placeholders()})
                AND b.deleted_at_ms IS NULL
                AND trim(bm.summary) <> ''
            ),
            aggregation AS (
              SELECT
                b.series_id,
                min(bm.release_date) AS release_date,
                min(bm.created_at_ms) AS minimum_created,
                max(bm.updated_at_ms) AS maximum_updated
              FROM book b
              JOIN book_metadata bm ON bm.book_id = b.id
              WHERE b.series_id IN (${batch.placeholders()})
                AND b.deleted_at_ms IS NULL
              GROUP BY b.series_id
            )
            SELECT
              aggregation.series_id,
              aggregation.release_date,
              CAST(aggregation.minimum_created AS TEXT) AS minimum_created_64,
              CAST(aggregation.maximum_updated AS TEXT) AS maximum_updated_64,
              ranked_summary.summary,
              ranked_summary.number
            FROM aggregation
            LEFT JOIN ranked_summary
              ON ranked_summary.series_id = aggregation.series_id
              AND ranked_summary.summary_rank = 1
            """.trimIndent(),
            *(bindings + bindings),
          ).associateBy { SeriesId(requireNotNull(it.get("series_id", String::class.java))) }
      val authors =
        database.dsl
          .fetch(
            """
            WITH ranked_author AS (
              SELECT
                b.series_id,
                author.name,
                author.role,
                bm.number_sort,
                b.relative_uri,
                b.id AS book_id,
                author.ordinal,
                row_number() OVER (
                  PARTITION BY b.series_id, author.role, author.name
                  ORDER BY bm.number_sort, b.relative_uri, b.id, author.ordinal
                ) AS duplicate_rank
              FROM book b
              JOIN book_metadata bm ON bm.book_id = b.id
              JOIN book_metadata_author author ON author.book_id = b.id
              WHERE b.series_id IN (${batch.placeholders()})
                AND b.deleted_at_ms IS NULL
            )
            SELECT series_id, name, role
            FROM ranked_author
            WHERE duplicate_rank = 1
            ORDER BY series_id, number_sort, relative_uri, book_id, ordinal
            """.trimIndent(),
            *bindings,
          ).groupBy(
            { SeriesId(requireNotNull(it.get("series_id", String::class.java))) },
            {
              Author(
                name = requireNotNull(it.get("name", String::class.java)),
                role = requireNotNull(it.get("role", String::class.java)),
              )
            },
          )
      val tags =
        database.dsl
          .fetch(
            """
            SELECT DISTINCT b.series_id, tag.tag
            FROM book b
            JOIN book_metadata_tag tag ON tag.book_id = b.id
            WHERE b.series_id IN (${batch.placeholders()})
              AND b.deleted_at_ms IS NULL
            ORDER BY b.series_id, tag.tag
            """.trimIndent(),
            *bindings,
          ).groupBy(
            { SeriesId(requireNotNull(it.get("series_id", String::class.java))) },
            { requireNotNull(it.get("tag", String::class.java)) },
          ).mapValues { (_, values) -> values.toSet() }
      batch.forEach { id ->
        val current = result[id] ?: return@forEach
        val row = stats[id]
        result[id] =
          current.copy(
            authors = authors[id].orEmpty(),
            tags = tags[id].orEmpty(),
            releaseDate = row?.get("release_date", String::class.java),
            summary = row?.get("summary", String::class.java).orEmpty(),
            summaryNumber = row?.get("number", String::class.java).orEmpty(),
            createdAtMillis = row?.get("minimum_created_64", String::class.java)?.toLong()
              ?: current.createdAtMillis,
            updatedAtMillis = row?.get("maximum_updated_64", String::class.java)?.toLong()
              ?: current.updatedAtMillis,
          )
      }
    }
    return result
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
    if (query.onDeck) {
      val userId = access.userId
      if (userId == null) {
        parts += "1 = 0"
      } else {
        parts +=
          """
          EXISTS (
            SELECT 1
            FROM read_progress_series series_progress
            WHERE series_progress.series_id = b.series_id
              AND series_progress.user_id = ?
              AND series_progress.books_read_count > 0
              AND series_progress.books_in_progress_count = 0
          )
          """.trimIndent()
        bindings += userId.value
        parts +=
          """
          b.id = (
            SELECT unread.id
            FROM book unread
            JOIN book_metadata unread_metadata ON unread_metadata.book_id = unread.id
            LEFT JOIN read_progress unread_progress
              ON unread_progress.book_id = unread.id
              AND unread_progress.user_id = ?
            WHERE unread.series_id = b.series_id
              AND unread.deleted_at_ms IS NULL
              AND unread_progress.book_id IS NULL
            ORDER BY unread_metadata.number_sort, unread.relative_uri, unread.id
            LIMIT 1
          )
          """.trimIndent()
        bindings += userId.value
      }
    }
    if (query.keepReading) {
      if (access.userId == null) {
        parts += "1 = 0"
      }
    }
    if (query.duplicatesOnly) {
      parts +=
        """
        b.file_hash <> ''
        AND EXISTS (
          SELECT 1
          FROM book duplicate
          WHERE duplicate.id <> b.id
            AND duplicate.file_hash = b.file_hash
            AND duplicate.file_size = b.file_size
        )
        """.trimIndent()
    }
    query.fullTextSearch?.trim()?.takeIf(String::isNotEmpty)?.let {
      val match = it.toFtsQuery()
      if (match == null) {
        parts += "1 = 0"
      } else {
        parts +=
          """
          b.id IN (
            SELECT entity_id
            FROM catalog_search_fts
            WHERE entity_type = 'BOOK' AND catalog_search_fts MATCH ?
          )
          """.trimIndent()
        bindings += match
      }
    }
    query.condition?.let {
      val condition = structuredSearch.book(it, access)
      parts += "(${condition.sql})"
      bindings.addAll(condition.bindings)
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
      val match = it.toFtsQuery()
      if (match == null) {
        parts += "1 = 0"
      } else {
        parts +=
          """
          s.id IN (
            SELECT entity_id
            FROM catalog_search_fts
            WHERE entity_type = 'SERIES' AND catalog_search_fts MATCH ?
          )
          """.trimIndent()
        bindings += match
      }
    }
    query.condition?.let {
      val condition = structuredSearch.series(it, access)
      parts += "(${condition.sql})"
      bindings.addAll(condition.bindings)
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

  private fun bookOrder(
    sorts: List<CatalogSort>,
    query: BookCatalogQuery,
    access: CatalogAccess,
  ): String {
    if (query.keepReading && access.userId != null && sorts.isEmpty()) {
      return "keep_progress.read_at_ms DESC, b.id ASC"
    }
    return order(
      sorts = sorts,
      mappings =
        mapOf(
          "created" to "b.created_at_ms",
          "fileHash" to "b.file_hash",
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
  }

  private fun bookFrom(
    query: BookCatalogQuery,
    access: CatalogAccess,
  ): SqlFrom {
    val bindings = mutableListOf<Any?>()
    val userId = access.userId
    val keepReadingJoins =
      if (query.keepReading && userId != null) {
        bindings += userId.value
        """
        JOIN read_progress keep_progress
          ON keep_progress.book_id = b.id
          AND keep_progress.user_id = ?
          AND keep_progress.completed = 0
        JOIN media keep_media
          ON keep_media.book_id = b.id
          AND keep_media.status = 'READY'
        """.trimIndent()
      } else {
        ""
      }
    return SqlFrom(
      sql =
        """
        book b
        JOIN series s ON s.id = b.series_id
        JOIN book_metadata bm ON bm.book_id = b.id
        JOIN series_metadata sm ON sm.series_id = s.id
        $keepReadingJoins
        """.trimIndent(),
      bindings = bindings,
    )
  }

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

  private fun String.toFtsQuery(): String? =
    SEARCH_TOKEN
      .findAll(this)
      .map(MatchResult::value)
      .filter(String::isNotBlank)
      .map { token -> "\"${token.replace("\"", "\"\"")}\"*" }
      .toList()
      .takeIf(List<String>::isNotEmpty)
      ?.joinToString(" AND ")

  private fun Set<String>.normalized(): Set<String> =
    asSequence().map(String::trim).filter(String::isNotEmpty).map(String::lowercase).toSet()

  private fun Collection<*>.placeholders(): String = joinToString(",") { "?" }

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0

  private fun Any?.asLongOrNull(): Long? = (this as? Number)?.toLong()

  private data class SqlFilter(
    val sql: String,
    val bindings: MutableList<Any?>,
  )

  private data class SqlFrom(
    val sql: String,
    val bindings: MutableList<Any?>,
  )

  companion object {
    private const val QUERY_BATCH_SIZE = 500
    private val SEARCH_TOKEN = Regex("[\\p{L}\\p{N}]+")
  }
}
