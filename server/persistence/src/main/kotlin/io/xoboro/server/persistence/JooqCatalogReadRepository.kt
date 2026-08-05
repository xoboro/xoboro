package io.xoboro.server.persistence

import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookMetadataAggregation
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogGroupCount
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.SeriesRegexSearchField
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.ReadProgressRepository
import io.xoboro.core.domain.Series
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
  private val bookMetadataAggregations = JooqBookMetadataAggregationRepository(database)

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
    bindings.addAll(order.bindings)
    val limit = page.limitClause(bindings)
    val ids =
      database.dsl
        .fetch(
          """
          SELECT b.id
          FROM ${from.sql}
          WHERE ${filter.sql}
          ORDER BY ${order.sql}
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
      sorts = page.sorts,
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
    sweepFor(page.sorts)
    val from = seriesFrom(access)
    val filter = seriesFilter(query, access)
    val countFilter =
      SqlFilter(filter.sql, (from.bindings + filter.bindings).toMutableList())
    val total = count(from.sql, countFilter)
    val bindings = (from.bindings + filter.bindings).toMutableList()
    val order = seriesOrder(page.sorts, query)
    bindings.addAll(order.bindings)
    val limit = page.limitClause(bindings)
    val ids =
      database.dsl
        .fetch(
          """
          SELECT s.id
          FROM ${from.sql}
          WHERE ${filter.sql}
          ORDER BY ${order.sql}
          $limit
          """.trimIndent(),
          *bindings.toTypedArray(),
        ).map { SeriesId(requireNotNull(it.get("id", String::class.java))) }
    return CatalogPage(
      content = hydrateSeries(ids, access.userId),
      page = if (page.unpaged) 0 else page.page,
      size = if (page.unpaged) ids.size.coerceAtLeast(1) else page.size,
      totalElements = total,
      unpaged = page.unpaged,
      sorts = page.sorts,
    )
  }

  override fun findSeriesByIdOrNull(
    id: SeriesId,
    access: CatalogAccess,
  ): CatalogSeries? {
    // No sweep here: this ends in hydrateSeries, which rebuilds exactly what it is about to read.
    val from = seriesFrom(access)
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
        FROM ${from.sql}
        WHERE ${filter.sql}
        """.trimIndent(),
        *(from.bindings + filter.bindings).toTypedArray(),
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
    // Groups on `sm.title_sort`, so nothing in the answer comes from the aggregation and there is
    // nothing here worth rebuilding one for. Sweeping anyway cost this route 8.4s per call.
    val from = seriesFrom(access)
    val filter = seriesFilter(query, access)
    return database.dsl
      .fetch(
        """
        SELECT
          CASE
            WHEN trim(sm.title_sort) = '' THEN '#'
            ELSE lower(substr(trim(sm.title_sort), 1, 1))
          END AS group_name,
          count(*) AS group_count
        FROM ${from.sql}
        WHERE ${filter.sql}
        GROUP BY group_name
        ORDER BY group_name
        """.trimIndent(),
        *(from.bindings + filter.bindings).toTypedArray(),
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
    // Exactly the series this response carries, so a page pays for its own freshness and nothing
    // else's. Sweeping an arbitrary batch instead meant every read took the write lock for 500
    // series, which on a database that admits one writer is a cost the whole process shares.
    bookMetadataAggregations.refreshDirty(ids)
    val aggregations = bookMetadataAggregations.findAllBySeriesIds(ids)
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
        // A series whose aggregation has not been rebuilt yet reads as empty, not as a fault. This
        // asserted the row existed, which held only because every read swept the whole dirty table
        // first - the sweep that made the first listing after a scan take 22s. With the sweep now
        // bounded, an unbuilt row is an ordinary state and the next sweep fills it in.
        booksMetadata = aggregations[id] ?: item.emptyAggregation(),
        readProgress = progresses[id],
      )
    }
  }

  /**
   * What a series' aggregated book metadata is before anything has been aggregated.
   *
   * Timestamps come from the series itself, matching what the rebuild falls back to when a series
   * has no book metadata to aggregate (`coalesce(min(metadata.created_at_ms), series.created_at_ms)`),
   * so an unbuilt row and a built-but-empty one are not distinguishable from outside - which is
   * correct, because they mean the same thing to a reader.
   */
  private fun Series.emptyAggregation(): BookMetadataAggregation =
    BookMetadataAggregation(
      authors = emptyList(),
      tags = emptySet(),
      releaseDate = null,
      summary = "",
      summaryNumber = "",
      createdAtMillis = createdAtMillis,
      updatedAtMillis = updatedAtMillis,
    )

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
    query.regexSearch?.let {
      val column =
        when (it.field) {
          SeriesRegexSearchField.TITLE -> "sm.title"
          SeriesRegexSearchField.TITLE_SORT -> "sm.title_sort"
        }
      parts += "$column REGEXP ?"
      bindings += it.pattern
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
  ): SqlOrder {
    if (query.keepReading && access.userId != null && sorts.isEmpty()) {
      return SqlOrder("keep_progress.read_at_ms DESC, b.id ASC")
    }
    val readListId =
      query.condition
        .equalityValues(CatalogSearchField.READ_LIST_ID)
        .singleOrNull()
    return order(
      sorts = sorts,
      expression = { property ->
        if (property == "readList.number" && readListId != null) {
          SqlSortExpression(
            sql =
              """
              (
                SELECT member.position
                FROM read_list_member member
                WHERE member.book_id = b.id
                  AND member.read_list_id = ?
              )
              """.trimIndent(),
            bindings = listOf(readListId),
          )
        } else {
          BOOK_SORTS[property]?.let(::SqlSortExpression)
        }
      },
      fallback = "sm.title_sort COLLATE NOCASE ASC, bm.number_sort ASC, b.relative_uri ASC, b.id ASC",
      tieBreaker = "b.id ASC",
    )
  }

  private fun bookFrom(
    query: BookCatalogQuery,
    access: CatalogAccess,
  ): SqlFrom {
    val bindings = mutableListOf<Any?>()
    val userId = access.userId
    val progressJoin =
      if (userId == null) {
        "LEFT JOIN read_progress sort_progress ON 1 = 0"
      } else {
        bindings += userId.value
        """
        LEFT JOIN read_progress sort_progress
          ON sort_progress.book_id = b.id
          AND sort_progress.user_id = ?
        """.trimIndent()
      }
    // The reader's progress through the book's *series*, which is a different question from
    // their progress through the book. On-deck is the case that needs it: it selects the
    // first book of a series that has **no** progress row, so `sort_progress.read_at_ms` is
    // null for every row it returns and ordering by it collapsed to the tie-breaker,
    // `b.id ASC`. A feed documented as "most recently read first" was ordered by identifier.
    val seriesProgressJoin =
      if (userId == null) {
        "LEFT JOIN read_progress_series sort_series_progress ON 1 = 0"
      } else {
        bindings += userId.value
        """
        LEFT JOIN read_progress_series sort_series_progress
          ON sort_series_progress.series_id = b.series_id
          AND sort_series_progress.user_id = ?
        """.trimIndent()
      }
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
    // Join order matches the order bindings were added above, which is what makes the
    // positional parameters line up.
    return SqlFrom(
      sql =
        """
        book b
        JOIN series s ON s.id = b.series_id
        JOIN book_metadata bm ON bm.book_id = b.id
        JOIN series_metadata sm ON sm.series_id = s.id
        LEFT JOIN media sort_media ON sort_media.book_id = b.id
        $progressJoin
        $seriesProgressJoin
        $keepReadingJoins
        """.trimIndent(),
      bindings = bindings,
    )
  }

  /**
   * Sweeps the dirty aggregation as much as this query's answer actually needs.
   *
   * The listing selects nothing from `series_book_metadata_aggregation`; the hydration step loads it
   * separately per page. The join exists for one sortable column, [AGGREGATED_SORT], and only a
   * query ordering on that column can be answered wrongly by a stale row - so only that query pays
   * for a full rebuild. Everything else sweeps one bounded batch and lets the backlog drain across
   * calls.
   *
   * Before this, every series read swept the whole dirty table. A scan dirties every series, so the
   * first listing after one rebuilt the entire library inside the request: 22s for `GET /series` and
   * 61s for the alphabet grouping against 145,105 archives, decaying to under a second once drained.
   */
  private fun sweepFor(sorts: List<CatalogSort>) {
    if (sorts.any { it.property == AGGREGATED_SORT }) {
      bookMetadataAggregations.refreshAllDirty()
    }
  }

  private fun seriesFrom(access: CatalogAccess): SqlFrom {
    val bindings = mutableListOf<Any?>()
    val progressJoin =
      access.userId?.let { userId ->
        bindings += userId.value
        """
        LEFT JOIN read_progress_series sort_series_progress
          ON sort_series_progress.series_id = s.id
          AND sort_series_progress.user_id = ?
        """.trimIndent()
      } ?: "LEFT JOIN read_progress_series sort_series_progress ON 1 = 0"
    return SqlFrom(
      sql =
        """
        series s
        JOIN series_metadata sm ON sm.series_id = s.id
        LEFT JOIN series_book_metadata_aggregation ba ON ba.series_id = s.id
        $progressJoin
        """.trimIndent(),
      bindings = bindings,
    )
  }

  private fun seriesOrder(
    sorts: List<CatalogSort>,
    query: SeriesCatalogQuery,
  ): SqlOrder {
    val collectionId =
      query.condition
        .equalityValues(CatalogSearchField.COLLECTION_ID)
        .singleOrNull()
    return order(
      sorts = sorts,
      expression = { property ->
        if (property == "collection.number" && collectionId != null) {
          SqlSortExpression(
            sql =
              """
              (
                SELECT member.position
                FROM series_collection_member member
                WHERE member.series_id = s.id
                  AND member.collection_id = ?
              )
              """.trimIndent(),
            bindings = listOf(collectionId),
          )
        } else {
          SERIES_SORTS[property]?.let(::SqlSortExpression)
        }
      },
      fallback = "sm.title_sort COLLATE NOCASE ASC, s.id ASC",
      tieBreaker = "s.id ASC",
    )
  }

  private fun order(
    sorts: List<CatalogSort>,
    expression: (String) -> SqlSortExpression?,
    fallback: String,
    tieBreaker: String,
  ): SqlOrder {
    if (sorts.isEmpty()) return SqlOrder(fallback)
    val bindings = mutableListOf<Any?>()
    val requested =
      sorts.joinToString(", ") { sort ->
        val sortExpression =
          expression(sort.property)
            ?: throw IllegalArgumentException(
              "Unsupported catalog sort property: ${sort.property}",
            )
        bindings.addAll(sortExpression.bindings)
        val direction =
          when (sort.direction) {
            CatalogSortDirection.ASC -> "ASC"
            CatalogSortDirection.DESC -> "DESC"
          }
        "${sortExpression.sql} $direction"
      }
    return SqlOrder("$requested, $tieBreaker", bindings)
  }

  private fun CatalogSearchCondition?.equalityValues(field: CatalogSearchField): Set<String> =
    when (this) {
      null -> emptySet()
      is CatalogSearchCondition.Predicate ->
        value
          ?.takeIf { this.field == field && operator == CatalogSearchOperator.IS }
          ?.let(::setOf)
          .orEmpty()
      is CatalogSearchCondition.AllOf ->
        conditions.flatMapTo(linkedSetOf()) { it.equalityValues(field) }
      is CatalogSearchCondition.AnyOf -> {
        val branchValues = conditions.map { it.equalityValues(field) }
        branchValues
          .mapNotNull { it.singleOrNull() }
          .distinct()
          .singleOrNull()
          ?.takeIf { branchValues.all { values -> values.singleOrNull() == it } }
          ?.let(::setOf)
          .orEmpty()
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

  private data class SqlOrder(
    val sql: String,
    val bindings: List<Any?> = emptyList(),
  )

  private data class SqlSortExpression(
    val sql: String,
    val bindings: List<Any?> = emptyList(),
  )

  companion object {
    private val BOOK_SORTS =
      mapOf(
        "created" to "b.created_at_ms",
        "createdDate" to "b.created_at_ms",
        "fileHash" to "b.file_hash",
        "fileLastModified" to "b.file_modified_ms",
        "fileSize" to "b.file_size",
        "lastModified" to "b.updated_at_ms",
        "lastModifiedDate" to "b.updated_at_ms",
        "name" to "b.name COLLATE NOCASE",
        "number" to "bm.number_sort",
        "numberSort" to "bm.number_sort",
        "series" to "sm.title_sort COLLATE NOCASE",
        "seriesTitle" to "sm.title_sort COLLATE NOCASE",
        "size" to "b.file_size",
        "sizeBytes" to "b.file_size",
        "title" to "bm.title COLLATE NOCASE",
        "url" to "b.relative_uri COLLATE NOCASE",
        "media.status" to "sort_media.status COLLATE NOCASE",
        "media.comment" to "sort_media.comment COLLATE NOCASE",
        "media.mediaType" to "sort_media.media_type COLLATE NOCASE",
        "media.pagesCount" to "sort_media.page_count",
        "metadata.title" to "bm.title COLLATE NOCASE",
        "metadata.numberSort" to "bm.number_sort",
        "metadata.releaseDate" to "bm.release_date",
        "readProgress.lastModified" to "sort_progress.updated_at_ms",
        "readProgress.readDate" to "sort_progress.read_at_ms",
        // When the reader last read anything in this book's series, as opposed to this book.
        // The two differ for any book the reader has not opened, which is the whole of the
        // on-deck feed.
        "readProgress.seriesReadDate" to "sort_series_progress.last_read_at_ms",
        "readList.number" to
          """
          (
            SELECT min(member.position)
            FROM read_list_member member
            WHERE member.book_id = b.id
          )
          """.trimIndent(),
      )
    /**
     * The one sort key served by the denormalized aggregation, and so the one query whose order a
     * stale row can get wrong. See [sweepFor].
     */
    private const val AGGREGATED_SORT = "booksMetadata.releaseDate"

    private val SERIES_SORTS =
      mapOf(
        "booksCount" to "s.book_count",
        "created" to "s.created_at_ms",
        "createdDate" to "s.created_at_ms",
        "fileLastModified" to "s.file_modified_ms",
        "lastModified" to "s.updated_at_ms",
        "lastModifiedDate" to "s.updated_at_ms",
        "name" to "s.name COLLATE NOCASE",
        "title" to "sm.title COLLATE NOCASE",
        "titleSort" to "sm.title_sort COLLATE NOCASE",
        "metadata.titleSort" to "sm.title_sort COLLATE NOCASE",
        AGGREGATED_SORT to "ba.release_date",
        "readDate" to "sort_series_progress.last_read_at_ms",
        "collection.number" to
          """
          (
            SELECT min(member.position)
            FROM series_collection_member member
            WHERE member.series_id = s.id
          )
          """.trimIndent(),
        "random" to "random()",
      )
    private val SEARCH_TOKEN = Regex("[\\p{L}\\p{N}]+")
  }
}
