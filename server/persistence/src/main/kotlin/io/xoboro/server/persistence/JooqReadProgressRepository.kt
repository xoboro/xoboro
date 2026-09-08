package io.xoboro.server.persistence

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.ReadProgressRepository
import io.xoboro.core.domain.ReadProgressUpsertResult
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesReadProgress
import io.xoboro.core.domain.UserId
import org.jooq.DSLContext
import org.jooq.Record

class JooqReadProgressRepository(
  private val database: XoboroDatabase,
) : ReadProgressRepository {
  override fun findByBookIdAndUserIdOrNull(
    bookId: BookId,
    userId: UserId,
  ): ReadProgress? =
    database.dsl.findProgressByBookIdAndUserIdOrNull(bookId, userId)

  override fun findAllByBookIdsAndUserId(
    bookIds: Collection<BookId>,
    userId: UserId,
  ): List<ReadProgress> {
    if (bookIds.isEmpty()) return emptyList()
    val bindings = bookIds.map { it.value } + userId.value
    return database.dsl
      .fetch(
        """
        $SELECT_PROGRESS
        WHERE book_id IN (${bookIds.placeholders()}) AND user_id = ?
        ORDER BY book_id
        """.trimIndent(),
        *bindings.toTypedArray(),
      ).map { it.toProgress() }
  }

  override fun findSeriesByIdAndUserIdOrNull(
    seriesId: SeriesId,
    userId: UserId,
  ): SeriesReadProgress? =
    database.dsl
      .fetchOne(
        """
        SELECT read_progress_series.*,
          CAST(last_read_at_ms AS TEXT) AS last_read_at_ms_64,
          CAST(created_at_ms AS TEXT) AS created_at_ms_64,
          CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
        FROM read_progress_series
        WHERE series_id = ? AND user_id = ?
        """.trimIndent(),
        seriesId.value,
        userId.value,
      )?.toSeriesProgress()

  override fun findAllSeriesByIdsAndUserId(
    seriesIds: Collection<SeriesId>,
    userId: UserId,
  ): List<SeriesReadProgress> =
    seriesIds
      .distinct()
      .chunked(QUERY_BATCH_SIZE)
      .flatMap { batch ->
        val bindings = batch.map { it.value } + userId.value
        database.dsl
          .fetch(
            """
            SELECT read_progress_series.*,
              CAST(last_read_at_ms AS TEXT) AS last_read_at_ms_64,
              CAST(created_at_ms AS TEXT) AS created_at_ms_64,
              CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
            FROM read_progress_series
            WHERE series_id IN (${batch.placeholders()}) AND user_id = ?
            ORDER BY series_id
            """.trimIndent(),
            *bindings.toTypedArray(),
          ).map { it.toSeriesProgress() }
      }

  override fun upsert(progress: ReadProgress) {
    database.transaction { transaction ->
      transaction.upsertProgress(progress)
      transaction.recomputeSeriesForBooks(listOf(progress.bookId), progress.userId)
    }
  }

  override fun upsertIfNewer(progress: ReadProgress): ReadProgressUpsertResult =
    database.transaction { transaction ->
      val applied = transaction.upsertProgressIfNewer(progress)
      if (applied) {
        transaction.recomputeSeriesForBooks(listOf(progress.bookId), progress.userId)
      }
      val stored =
        requireNotNull(
          transaction.findProgressByBookIdAndUserIdOrNull(progress.bookId, progress.userId),
        ) { "Conditional progress upsert must leave a winning persisted row" }
      ReadProgressUpsertResult(applied = applied, stored = stored)
    }

  override fun upsertAll(progresses: Collection<ReadProgress>) {
    if (progresses.isEmpty()) return
    database.transaction { transaction ->
      progresses.forEach { transaction.upsertProgress(it) }
      progresses.groupBy(ReadProgress::userId).forEach { (userId, userProgresses) ->
        transaction.recomputeSeriesForBooks(userProgresses.map(ReadProgress::bookId), userId)
      }
    }
  }

  override fun delete(
    bookId: BookId,
    userId: UserId,
  ) {
    database.transaction { transaction ->
      transaction.execute(
        "DELETE FROM read_progress WHERE book_id = ? AND user_id = ?",
        bookId.value,
        userId.value,
      )
      transaction.recomputeSeriesForBooks(listOf(bookId), userId)
    }
  }

  override fun deleteBySeriesIdAndUserId(
    seriesId: SeriesId,
    userId: UserId,
  ) {
    database.transaction { transaction ->
      transaction.execute(
        """
        DELETE FROM read_progress
        WHERE user_id = ?
          AND book_id IN (SELECT id FROM book WHERE series_id = ?)
        """.trimIndent(),
        userId.value,
        seriesId.value,
      )
      transaction.execute(
        "DELETE FROM read_progress_series WHERE series_id = ? AND user_id = ?",
        seriesId.value,
        userId.value,
      )
    }
  }

  private fun DSLContext.upsertProgress(progress: ReadProgress) {
    execute(
      """
      INSERT INTO read_progress (
        book_id, user_id, page, completed, read_at_ms, device_id, device_name,
        locator_json, created_at_ms, updated_at_ms
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(book_id, user_id) DO UPDATE SET
        page = excluded.page,
        completed = excluded.completed,
        read_at_ms = excluded.read_at_ms,
        device_id = excluded.device_id,
        device_name = excluded.device_name,
        locator_json = excluded.locator_json,
        updated_at_ms = excluded.updated_at_ms
      """.trimIndent(),
      progress.bookId.value,
      progress.userId.value,
      progress.page,
      progress.completed.toSqliteInt(),
      progress.readAtMillis,
      progress.deviceId,
      progress.deviceName,
      progress.locatorJson,
      progress.createdAtMillis,
      progress.updatedAtMillis,
    )
  }

  private fun DSLContext.findProgressByBookIdAndUserIdOrNull(
    bookId: BookId,
    userId: UserId,
  ): ReadProgress? =
    fetchOne(
      "$SELECT_PROGRESS WHERE book_id = ? AND user_id = ?",
      bookId.value,
      userId.value,
    )?.toProgress()

  private fun DSLContext.upsertProgressIfNewer(progress: ReadProgress): Boolean =
    execute(
      """
      INSERT INTO read_progress (
        book_id, user_id, page, completed, read_at_ms, device_id, device_name,
        locator_json, created_at_ms, updated_at_ms
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(book_id, user_id) DO UPDATE SET
        page = excluded.page,
        completed = excluded.completed,
        read_at_ms = excluded.read_at_ms,
        device_id = excluded.device_id,
        device_name = excluded.device_name,
        locator_json = excluded.locator_json,
        updated_at_ms = excluded.updated_at_ms
      WHERE excluded.read_at_ms > read_progress.read_at_ms
      """.trimIndent(),
      progress.bookId.value,
      progress.userId.value,
      progress.page,
      progress.completed.toSqliteInt(),
      progress.readAtMillis,
      progress.deviceId,
      progress.deviceName,
      progress.locatorJson,
      progress.createdAtMillis,
      progress.updatedAtMillis,
    ) > 0

  private fun DSLContext.recomputeSeriesForBooks(
    bookIds: Collection<BookId>,
    userId: UserId,
  ) {
    if (bookIds.isEmpty()) return
    val seriesIds =
      fetch(
        """
        SELECT DISTINCT series_id
        FROM book
        WHERE id IN (${bookIds.placeholders()})
        """.trimIndent(),
        *bookIds.map { it.value }.toTypedArray(),
      ).map { SeriesId(requireNotNull(it.get("series_id", String::class.java))) }
    seriesIds.forEach { recomputeSeries(it, userId) }
  }

  private fun DSLContext.recomputeSeries(
    seriesId: SeriesId,
    userId: UserId,
  ) {
    execute(
      "DELETE FROM read_progress_series WHERE series_id = ? AND user_id = ?",
      seriesId.value,
      userId.value,
    )
    execute(
      """
      INSERT INTO read_progress_series (
        series_id, user_id, books_read_count, books_in_progress_count,
        last_read_at_ms, created_at_ms, updated_at_ms
      )
      SELECT
        b.series_id,
        rp.user_id,
        sum(CASE WHEN rp.completed = 1 THEN 1 ELSE 0 END),
        sum(CASE WHEN rp.completed = 0 THEN 1 ELSE 0 END),
        max(rp.read_at_ms),
        min(rp.created_at_ms),
        max(rp.updated_at_ms)
      FROM book b
      JOIN read_progress rp ON rp.book_id = b.id
      WHERE b.series_id = ? AND rp.user_id = ?
      GROUP BY b.series_id, rp.user_id
      """.trimIndent(),
      seriesId.value,
      userId.value,
    )
  }

  private fun Record.toProgress(): ReadProgress =
    ReadProgress(
      bookId = BookId(requiredString("book_id")),
      userId = UserId(requiredString("user_id")),
      page = requiredInt("page"),
      completed = requiredBoolean("completed"),
      readAtMillis = requiredLongText("read_at_ms_64"),
      deviceId = requiredString("device_id"),
      deviceName = requiredString("device_name"),
      locatorJson = get("locator_json", String::class.java),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private fun Record.toSeriesProgress(): SeriesReadProgress =
    SeriesReadProgress(
      seriesId = SeriesId(requiredString("series_id")),
      userId = UserId(requiredString("user_id")),
      booksReadCount = requiredInt("books_read_count"),
      booksInProgressCount = requiredInt("books_in_progress_count"),
      lastReadAtMillis = requiredLongText("last_read_at_ms_64"),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredInt(field: String): Int =
    requireNotNull(get(field, Int::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.requiredBoolean(field: String): Boolean =
    when (val value = requiredInt(field)) {
      0 -> false
      1 -> true
      else -> error("Database field '$field' must be 0 or 1, got $value")
    }

  private fun Collection<*>.placeholders(): String = joinToString(",") { "?" }

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0

  private companion object {
    const val QUERY_BATCH_SIZE = 500
    const val SELECT_PROGRESS =
      """
      SELECT read_progress.*,
        CAST(read_at_ms AS TEXT) AS read_at_ms_64,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64,
        CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
      FROM read_progress
      """
  }
}
