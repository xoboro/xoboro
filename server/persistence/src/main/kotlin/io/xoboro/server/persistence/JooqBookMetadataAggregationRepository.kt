package io.xoboro.server.persistence

import io.xoboro.core.application.BookMetadataAggregation
import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.application.CatalogMutationKind
import io.xoboro.core.application.SeriesAggregationSweep
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.exception.DataAccessException

/**
 * Maintains the denormalized per-series view of book metadata.
 *
 * Refreshing is **write-first**: each transaction opens by deleting the dirty rows it intends to
 * handle and taking their ids from `RETURNING`, then rebuilds those series. Reading the dirty table
 * first and writing later in the same transaction is the shape that fails with
 * `SQLITE_BUSY_SNAPSHOT` - SQLite refuses to upgrade a transaction to a writer once another
 * connection has committed against the read snapshot it already took, and `busy_timeout` does not
 * wait for a mid-transaction lock upgrade the way it waits for a fresh transaction's first write.
 * Because the delete is the first statement, this is a fresh transaction's first write, so the busy
 * handler applies normally.
 *
 * The busy handler applying is not the same as the write always succeeding, which this doc used to
 * claim. `busy_timeout` still expires, and a library scan holds the write lock for minutes - far
 * past any timeout worth configuring. [sweepOrNull] is where that outcome is absorbed: a sweep is
 * always allowed to answer "not yet" and leave the backlog for whoever comes next.
 *
 * Sweeping is no longer something a read does. [findAllBySeriesIds] is a reader, and every series
 * route reads an unbuilt row as empty rather than as a fault, so the only caller that must still
 * sweep is the one whose answer comes *out* of the aggregation - a query ordering on it. Everything
 * else is drained by `SeriesAggregationScheduler` off the request path.
 *
 * Claiming by deleting also means concurrent refreshers do not duplicate work: the write lock
 * serializes them, and whoever loses the race finds the rows already claimed and skips the rebuild
 * - correctly, because the winner's rebuild has committed by the time the loser's delete returns.
 * A rollback restores the dirty rows along with everything else, so nothing is lost.
 *
 * This is only safe because no trigger marks a series dirty in response to writes on the
 * aggregation tables themselves: every `mark_series_aggregation_dirty_*` trigger fires on `series`,
 * `book`, `book_metadata`, `book_metadata_author` or `book_metadata_tag`. If one were ever added to
 * an aggregation table, a rebuild would re-dirty what it just claimed and [refreshAllDirty] would
 * not terminate.
 */
class JooqBookMetadataAggregationRepository(
  private val database: XoboroDatabase,
) {
  fun refreshAllDirty() {
    while (true) {
      val refreshed = sweepOrNull { it.claimOldestDirty() } ?: return
      if (refreshed < QUERY_BATCH_SIZE) return
    }
  }

  /**
   * Rebuilds at most one batch, and reports whether more is waiting.
   *
   * [refreshAllDirty] is unbounded, and a read path that calls it pays for every series any recent
   * write dirtied. A scan dirties all of them - a trigger fires per book and per book_metadata row -
   * so the first listing after a scan of 145,105 archives rebuilt the whole library inside one
   * request: measured at 22s for `GET /series` and 61s for the alphabet grouping, decaying to under
   * a second once drained.
   *
   * Bounded sweeping is only correct because the listing LEFT JOINs the aggregation (see
   * [JooqCatalogReadRepository.seriesFrom]): a series whose row has not been built yet still
   * appears, so draining in the background costs freshness rather than visibility. A caller whose
   * answer actually depends on the aggregation - sorting on it - still has to sweep it all.
   */
  /**
   * Sweeps one batch, reporting which series it rebuilt.
   *
   * The ids come from the claim's `RETURNING`, so they are exactly the rows this transaction took
   * responsibility for - not a re-read, which another sweeper could have emptied in between. The
   * library each belongs to is read in the same transaction for the same reason, and it is read at
   * all because a subscriber's access is decided by `libraryId`: an id with no library cannot be
   * delivered to anyone.
   *
   * A series claimed as dirty but since deleted contributes no event. It has no row to join, and
   * announcing a change to something that is gone would be a lie about a screen the reader can no
   * longer open.
   */
  fun sweepSomeDirty(): SeriesAggregationSweep {
    var events = emptyList<CatalogMutationEvent.Series>()
    val refreshed =
      sweepOrNull { transaction ->
        val ids = transaction.claimOldestDirty()
        events = transaction.mutationEventsFor(ids)
        ids
      // Locked out rather than finished: the backlog stands, and the next tick tries again.
      } ?: return SeriesAggregationSweep(rebuilt = emptyList(), moreRemaining = true)
    return SeriesAggregationSweep(rebuilt = events, moreRemaining = refreshed >= QUERY_BATCH_SIZE)
  }

  private fun DSLContext.mutationEventsFor(
    ids: List<SeriesId>,
  ): List<CatalogMutationEvent.Series> {
    if (ids.isEmpty()) return emptyList()
    return ids.chunked(QUERY_BATCH_SIZE).flatMap { batch ->
      fetch(
        """
        SELECT id, library_id
        FROM series
        WHERE id IN (${batch.placeholders()})
        """.trimIndent(),
        *batch.bindings(),
      ).map { row ->
        CatalogMutationEvent.Series(
          kind = CatalogMutationKind.UPDATED,
          seriesId = SeriesId(row.requiredString("id")),
          libraryId = LibraryId(row.requiredString("library_id")),
        )
      }
    }
  }

  fun findAllBySeriesIds(ids: Collection<SeriesId>): Map<SeriesId, BookMetadataAggregation> {
    val requested = ids.distinct()
    if (requested.isEmpty()) return emptyMap()
    return requested.chunked(QUERY_BATCH_SIZE).flatMap { batch ->
      val rows =
        database.dsl.fetch(
          """
          SELECT
            aggregation.*,
            CAST(aggregation.created_at_ms AS TEXT) AS created_at_ms_64,
            CAST(aggregation.updated_at_ms AS TEXT) AS updated_at_ms_64
          FROM series_book_metadata_aggregation aggregation
          WHERE aggregation.series_id IN (${batch.placeholders()})
          ORDER BY aggregation.series_id
          """.trimIndent(),
          *batch.bindings(),
        )
      val authors = loadAuthors(batch)
      val tags = loadTags(batch)
      rows.map { row ->
        val id = SeriesId(row.requiredString("series_id"))
        id to
          BookMetadataAggregation(
            authors = authors[id].orEmpty(),
            tags = tags[id].orEmpty(),
            releaseDate = row.get("release_date", String::class.java),
            summary = row.requiredString("summary"),
            summaryNumber = row.requiredString("summary_number"),
            createdAtMillis = row.requiredLongText("created_at_ms_64"),
            updatedAtMillis = row.requiredLongText("updated_at_ms_64"),
          )
      }
    }.toMap()
  }

  /**
   * Claims and rebuilds one batch, answering `null` when the write lock could not be taken.
   *
   * Contention must not escape. A scan holds the write lock far past any `busy_timeout` worth
   * configuring, and the one read path that still sweeps - a query ordering on the aggregation -
   * would otherwise answer `500` for the scan's whole duration, observed against a library of
   * 145,105 archives. The background sweep must not propagate it either: a scheduled task that
   * throws is a task that stops being scheduled.
   *
   * Deferring costs nothing but freshness. The claim is the transaction's first statement, so a
   * failure rolls back with the dirty rows still in place, and the next caller or the background
   * sweep rebuilds them. Answering with a moment-stale denormalized view is the right trade against
   * failing the request; not logging is deliberate, because a sweep ticking once a minute through a
   * long scan would otherwise write this line on every tick.
   */
  private fun sweepOrNull(claim: (DSLContext) -> List<SeriesId>): Int? =
    try {
      database.transaction { transaction ->
        val ids = claim(transaction)
        if (ids.isNotEmpty()) transaction.rebuild(ids.sortedBy { it.value })
        ids.size
      }
    } catch (failure: DataAccessException) {
      if (failure.isDatabaseLocked()) null else throw failure
    }

  private fun DSLContext.claimOldestDirty(): List<SeriesId> =
    fetch(
      """
      DELETE FROM series_book_metadata_aggregation_dirty
      WHERE series_id IN (
        SELECT series_id
        FROM series_book_metadata_aggregation_dirty
        ORDER BY series_id
        LIMIT $QUERY_BATCH_SIZE
      )
      RETURNING series_id
      """.trimIndent(),
    ).map { SeriesId(it.requiredString("series_id")) }

  private fun DSLContext.rebuild(ids: Collection<SeriesId>) {
    val batch = ids.toList()
    val bindings = batch.bindings()
    val doubledBindings = (batch + batch).bindings()
    val stats =
      fetch(
        """
        WITH ranked_summary AS (
          SELECT
            book.series_id,
            metadata.summary,
            metadata.number,
            row_number() OVER (
              PARTITION BY book.series_id
              ORDER BY metadata.number_sort, book.relative_uri, book.id
            ) AS summary_rank
          FROM book
          JOIN book_metadata metadata ON metadata.book_id = book.id
          WHERE book.series_id IN (${batch.placeholders()})
            AND book.deleted_at_ms IS NULL
            AND trim(metadata.summary) <> ''
        )
        SELECT
          series.id AS series_id,
          min(metadata.release_date) AS release_date,
          CAST(
            coalesce(min(metadata.created_at_ms), series.created_at_ms)
            AS TEXT
          ) AS minimum_created_64,
          CAST(
            coalesce(max(metadata.updated_at_ms), series.updated_at_ms)
            AS TEXT
          ) AS maximum_updated_64,
          ranked_summary.summary,
          ranked_summary.number
        FROM series
        LEFT JOIN book
          ON book.series_id = series.id
          AND book.deleted_at_ms IS NULL
        LEFT JOIN book_metadata metadata ON metadata.book_id = book.id
        LEFT JOIN ranked_summary
          ON ranked_summary.series_id = series.id
          AND ranked_summary.summary_rank = 1
        WHERE series.id IN (${batch.placeholders()})
        GROUP BY
          series.id,
          series.created_at_ms,
          series.updated_at_ms,
          ranked_summary.summary,
          ranked_summary.number
        """.trimIndent(),
        *doubledBindings,
      )
    val authors =
      fetch(
        """
        WITH ranked_author AS (
          SELECT
            book.series_id,
            author.name,
            author.role,
            metadata.number_sort,
            book.relative_uri,
            book.id AS book_id,
            author.ordinal,
            row_number() OVER (
              PARTITION BY book.series_id, author.role, author.name
              ORDER BY metadata.number_sort, book.relative_uri, book.id, author.ordinal
            ) AS duplicate_rank
          FROM book
          JOIN book_metadata metadata ON metadata.book_id = book.id
          JOIN book_metadata_author author ON author.book_id = book.id
          WHERE book.series_id IN (${batch.placeholders()})
            AND book.deleted_at_ms IS NULL
        )
        SELECT series_id, name, role
        FROM ranked_author
        WHERE duplicate_rank = 1
        ORDER BY series_id, number_sort, relative_uri, book_id, ordinal
        """.trimIndent(),
        *bindings,
      ).groupBy(
        { SeriesId(it.requiredString("series_id")) },
        { Author(it.requiredString("name"), it.requiredString("role")) },
      )
    val tags =
      fetch(
        """
        SELECT DISTINCT book.series_id, tag.tag
        FROM book
        JOIN book_metadata_tag tag ON tag.book_id = book.id
        WHERE book.series_id IN (${batch.placeholders()})
          AND book.deleted_at_ms IS NULL
        ORDER BY book.series_id, tag.tag
        """.trimIndent(),
        *bindings,
      ).groupBy(
        { SeriesId(it.requiredString("series_id")) },
        { it.requiredString("tag") },
      )
    stats.forEach { row ->
      val id = SeriesId(row.requiredString("series_id"))
      execute(
        """
        INSERT INTO series_book_metadata_aggregation (
          series_id, summary, summary_number, release_date, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(series_id) DO UPDATE SET
          summary = excluded.summary,
          summary_number = excluded.summary_number,
          release_date = excluded.release_date,
          created_at_ms = excluded.created_at_ms,
          updated_at_ms = excluded.updated_at_ms
        """.trimIndent(),
        id.value,
        row.get("summary", String::class.java).orEmpty(),
        row.get("number", String::class.java).orEmpty(),
        row.get("release_date", String::class.java),
        row.requiredLongText("minimum_created_64"),
        row.requiredLongText("maximum_updated_64"),
      )
    }
    execute(
      """
      DELETE FROM series_book_metadata_aggregation_author
      WHERE series_id IN (${batch.placeholders()})
      """.trimIndent(),
      *bindings,
    )
    execute(
      """
      DELETE FROM series_book_metadata_aggregation_tag
      WHERE series_id IN (${batch.placeholders()})
      """.trimIndent(),
      *bindings,
    )
    batch.forEach { id ->
      authors[id].orEmpty().forEachIndexed { ordinal, author ->
        execute(
          """
          INSERT INTO series_book_metadata_aggregation_author
            (series_id, ordinal, name, role)
          VALUES (?, ?, ?, ?)
          """.trimIndent(),
          id.value,
          ordinal,
          author.normalizedName,
          author.normalizedRole,
        )
      }
      tags[id].orEmpty().forEach { tag ->
        execute(
          """
          INSERT INTO series_book_metadata_aggregation_tag (series_id, tag)
          VALUES (?, ?)
          """.trimIndent(),
          id.value,
          tag,
        )
      }
    }
  }

  private fun loadAuthors(ids: Collection<SeriesId>): Map<SeriesId, List<Author>> =
    database.dsl
      .fetch(
        """
        SELECT series_id, name, role
        FROM series_book_metadata_aggregation_author
        WHERE series_id IN (${ids.placeholders()})
        ORDER BY series_id, ordinal
        """.trimIndent(),
        *ids.bindings(),
      ).groupBy(
        { SeriesId(it.requiredString("series_id")) },
        { Author(it.requiredString("name"), it.requiredString("role")) },
      )

  private fun loadTags(ids: Collection<SeriesId>): Map<SeriesId, Set<String>> =
    database.dsl
      .fetch(
        """
        SELECT series_id, tag
        FROM series_book_metadata_aggregation_tag
        WHERE series_id IN (${ids.placeholders()})
        ORDER BY series_id, tag
        """.trimIndent(),
        *ids.bindings(),
      ).groupBy(
        { SeriesId(it.requiredString("series_id")) },
        { it.requiredString("tag") },
      ).mapValues { (_, tags) -> tags.toSet() }

  private fun Collection<SeriesId>.bindings(): Array<Any?> =
    map<SeriesId, Any?> { it.value }.toTypedArray()

  private fun Collection<*>.placeholders(): String = joinToString(",") { "?" }

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private companion object {
    const val QUERY_BATCH_SIZE = 500
  }
}
