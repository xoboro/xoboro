package io.xoboro.server.persistence

import io.xoboro.core.application.BookMetadataAggregation
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.SeriesId
import org.jooq.DSLContext
import org.jooq.Record
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

internal class JooqBookMetadataAggregationRepository(
  private val database: XoboroDatabase,
) {
  fun refreshAllDirty() {
    while (true) {
      val refreshed =
        retryOnBusySnapshot {
          database.transaction { transaction ->
            transaction.forceImmediateWriteLock()
            val ids =
              transaction
                .fetch(
                  """
                  SELECT series_id
                  FROM series_book_metadata_aggregation_dirty
                  ORDER BY series_id
                  LIMIT $QUERY_BATCH_SIZE
                  """.trimIndent(),
                ).map { SeriesId(it.requiredString("series_id")) }
            if (ids.isNotEmpty()) transaction.rebuild(ids)
            ids.size
          }
        }
      if (refreshed < QUERY_BATCH_SIZE) return
    }
  }

  fun findAllBySeriesIds(ids: Collection<SeriesId>): Map<SeriesId, BookMetadataAggregation> {
    val requested = ids.distinct()
    if (requested.isEmpty()) return emptyMap()
    refreshDirty(requested)
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

  fun refreshDirty(requested: Collection<SeriesId>) {
    requested.chunked(QUERY_BATCH_SIZE).forEach { batch ->
      retryOnBusySnapshot {
        database.transaction { transaction ->
          transaction.forceImmediateWriteLock()
          val dirty =
            transaction
              .fetch(
                """
                SELECT series_id
                FROM series_book_metadata_aggregation_dirty
                WHERE series_id IN (${batch.placeholders()})
                ORDER BY series_id
                """.trimIndent(),
                *batch.bindings(),
              ).map { SeriesId(it.requiredString("series_id")) }
          if (dirty.isNotEmpty()) transaction.rebuild(dirty)
        }
      }
    }
  }

  /**
   * These transactions read [series_book_metadata_aggregation_dirty] and then, if they find
   * anything, conditionally write to it. Under SQLite's default deferred-transaction behavior, a
   * transaction that starts with a read and later attempts to *upgrade* that same transaction to a
   * writer can fail two ways, neither of which benefits from `busy_timeout` waiting:
   * - SQLITE_BUSY_SNAPSHOT: a concurrent connection already committed a write since this read
   *   established its snapshot, so SQLite refuses to silently invalidate that snapshot.
   * - a bare SQLITE_BUSY: another connection currently holds the write lock. Unlike a *fresh*
   *   transaction's first write (which SQLite's busy handler retries internally for up to
   *   `busy_timeout`), a lock *upgrade* attempted mid-transaction is not worth retrying in place -
   *   waiting cannot change the fact that this transaction's read snapshot is already fixed - so
   *   SQLite fails it immediately instead.
   *
   * Two layers of defense:
   * 1. [forceImmediateWriteLock] asks this connection's next transaction to open with `BEGIN
   *    IMMEDIATE` instead of the default deferred `BEGIN`, so the write lock is acquired upfront,
   *    before any read establishes a snapshot that could go stale. When this connection needs to
   *    wait for another writer, it now does so as a genuine fresh lock acquisition, which
   *    `busy_timeout` *does* wait for.
   * 2. [retryOnBusySnapshot] is a bounded safety net for whatever this first layer does not catch.
   *    SQLite's own documentation gives the same remedy for both failure shapes above: roll back
   *    and retry the whole transaction. A fresh attempt gets a new read snapshot and, if it needs to
   *    write, a genuinely fresh lock acquisition.
   */
  private fun <T> retryOnBusySnapshot(
    attempts: Int = MAX_BUSY_SNAPSHOT_ATTEMPTS,
    block: () -> T,
  ): T {
    repeat(attempts - 1) {
      try {
        return block()
      } catch (failure: Exception) {
        if (!failure.isTransactionUpgradeConflict()) throw failure
      }
    }
    return block()
  }

  private fun Throwable.isTransactionUpgradeConflict(): Boolean =
    generateSequence(this) { it.cause }
      .any {
        it is SQLiteException &&
          (it.resultCode == SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT || it.resultCode == SQLiteErrorCode.SQLITE_BUSY)
      }

  /**
   * This setting is intentionally left in place afterward rather than reset - every other
   * `database.transaction { }` call site in this module starts with a write as its first
   * statement, so it already needs to escalate to a writer immediately regardless of
   * deferred/immediate mode. Leaving it set is a no-op for those (pinned by
   * JooqBookMetadataAggregationRepositoryConcurrencyTest's
   * `leaving a connection in IMMEDIATE mode does not affect other write-first repositories`), and
   * is a strict improvement for the few call sites elsewhere that share this same read-then-write
   * shape (e.g. JooqServerSettingRepository.findOrCreate, JooqMetadataOrganizationWriter).
   *
   * A `finally`-scoped reset back to DEFERRED was tried and rejected: it reintroduced this exact
   * failure class at a far higher rate than leaving the mode set (144 SQLITE_BUSY_SNAPSHOT/
   * SQLITE_BUSY failures per 4000 calls at 8 concurrent readers + 4 writers, vs. 0 without the
   * reset). Do not reintroduce a reset without re-measuring at that concurrency first.
   */
  private fun DSLContext.forceImmediateWriteLock() {
    connection { connection ->
      connection
        .unwrap(SQLiteConnection::class.java)
        .connectionConfig
        .setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
    }
  }

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
    execute(
      """
      DELETE FROM series_book_metadata_aggregation_dirty
      WHERE series_id IN (${batch.placeholders()})
      """.trimIndent(),
      *bindings,
    )
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
    const val MAX_BUSY_SNAPSHOT_ATTEMPTS = 5
  }
}
