package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogCandidate
import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.application.CatalogMutationEventPublisher
import io.xoboro.core.application.CatalogMutationKind
import io.xoboro.core.application.CatalogReconciliationResult
import io.xoboro.core.application.CatalogReconciliationStore
import io.xoboro.core.application.ScanSessionId
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record

class JooqCatalogReconciliationStore(
  private val database: XoboroDatabase,
  private val eventPublisher: CatalogMutationEventPublisher = CatalogMutationEventPublisher {},
  private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : CatalogReconciliationStore {
  override fun begin(
    libraryId: LibraryId,
    deep: Boolean,
    startedAtMillis: Long,
  ): ScanSessionId {
    require(startedAtMillis >= 0) { "Scan start timestamp must not be negative" }
    val sessionId = ScanSessionId(sessionIdFactory())
    database.dsl.execute(
      """
      INSERT INTO catalog_scan_session
        (id, library_id, deep, status, started_at_ms)
      VALUES (?, ?, ?, 'STAGING', ?)
      """.trimIndent(),
      sessionId.value,
      libraryId.value,
      deep.toSqliteInt(),
      startedAtMillis,
    )
    return sessionId
  }

  override fun stage(
    sessionId: ScanSessionId,
    candidates: List<CatalogCandidate>,
  ) {
    if (candidates.isEmpty()) return
    requireSessionIsStaging(sessionId)
    database.transaction { transaction ->
      transaction.batch(
        STAGE_CANDIDATE_SQL,
        *candidates
          .map { candidate ->
            arrayOf<Any?>(
              sessionId.value,
              candidate.relativePath,
              candidate.sourceItemId,
              candidate.sourceIdentity,
              candidate.name,
              candidate.mediaKind.name,
              candidate.fileSize,
              candidate.fileModifiedAtMillis,
              candidate.seriesRelativePath,
              candidate.seriesSourceItemId,
              candidate.seriesName,
              candidate.oneshot.toSqliteInt(),
            )
          }
          .toTypedArray(),
      ).execute()
    }
  }

  override fun complete(
    sessionId: ScanSessionId,
    failedEntries: Long,
    ignoredFiles: Long,
    completedAtMillis: Long,
  ): CatalogReconciliationResult {
    require(failedEntries >= 0) { "Failed entry count must not be negative" }
    require(ignoredFiles >= 0) { "Ignored file count must not be negative" }
    require(completedAtMillis >= 0) { "Scan completion timestamp must not be negative" }

    val completion = database.transaction { transaction ->
      val session = transaction.requireStagingSession(sessionId)
      val libraryId = session.requiredString("library_id")
      val deep = session.requiredBoolean("deep")
      require(completedAtMillis >= session.requiredLongText("started_at_ms_64")) {
        "Scan completion timestamp must not precede its start"
      }
      transaction.classifyByLocation(sessionId, libraryId)
      transaction.classifyUniqueIdentityMoves(sessionId, libraryId)
      transaction.execute(
        """
        UPDATE catalog_scan_candidate
        SET change_type = 'NEW'
        WHERE session_id = ? AND matched_book_id IS NULL
        """.trimIndent(),
        sessionId.value,
      )

      val addedBooks = transaction.countCandidates(sessionId, "change_type = 'NEW'")
      val changedBooks = transaction.countCandidates(sessionId, "change_type = 'CHANGED'")
      val movedBooks = transaction.countCandidates(sessionId, "change_type = 'MOVED'")
      val restoredBooks = transaction.countCandidates(sessionId, "was_deleted = 1")
      val addedSeries = transaction.countAddedSeries(sessionId, libraryId)
      val restoredSeries = transaction.countRestoredSeries(sessionId, libraryId)
      val deletedBooks =
        if (failedEntries == 0L) transaction.countDeletedBooks(sessionId, libraryId) else 0L
      val deletedSeries =
        if (failedEntries == 0L) transaction.countDeletedSeries(sessionId, libraryId) else 0L
      val eventSnapshot =
        transaction.catalogEventSnapshot(
          sessionId = sessionId,
          libraryId = libraryId,
          includeDeletions = failedEntries == 0L,
        )

      transaction.insertMissingSeries(sessionId, libraryId, completedAtMillis)
      transaction.updateMatchedBooks(sessionId, libraryId, completedAtMillis)
      transaction.insertNewBooks(sessionId, libraryId, completedAtMillis)
      transaction.bindNewBooks(sessionId, libraryId)
      if (failedEntries == 0L) {
        transaction.softDeleteMissingBooks(sessionId, libraryId, completedAtMillis)
      }
      transaction.updateScannedSeries(
        sessionId = sessionId,
        libraryId = libraryId,
        completedAtMillis = completedAtMillis,
        allowCountDecrease = failedEntries == 0L,
      )
      if (failedEntries == 0L) {
        transaction.softDeleteMissingSeries(sessionId, libraryId, completedAtMillis)
      }
      transaction.enqueueAnalysis(sessionId, deep, completedAtMillis)
      transaction.execute(
        """
        UPDATE catalog_scan_session SET
          status = 'COMPLETED',
          completed_at_ms = ?,
          failed_entries = ?,
          ignored_files = ?
        WHERE id = ?
        """.trimIndent(),
        completedAtMillis,
        failedEntries,
        ignoredFiles,
        sessionId.value,
      )
      val events =
        transaction.catalogMutationEvents(
          sessionId = sessionId,
          libraryId = libraryId,
          snapshot = eventSnapshot,
        )
      transaction.execute(
        "DELETE FROM catalog_scan_candidate WHERE session_id = ?",
        sessionId.value,
      )

      CatalogCompletion(
        result =
          CatalogReconciliationResult(
            addedBooks = addedBooks,
            changedBooks = changedBooks,
            movedBooks = movedBooks,
            restoredBooks = restoredBooks,
            deletedBooks = deletedBooks,
            addedSeries = addedSeries,
            restoredSeries = restoredSeries,
            deletedSeries = deletedSeries,
            ignoredFiles = ignoredFiles,
            failedEntries = failedEntries,
            partial = failedEntries > 0,
          ),
        events = events,
      )
    }
    completion.events.forEach(eventPublisher::publish)
    return completion.result
  }

  override fun abort(
    sessionId: ScanSessionId,
    abortedAtMillis: Long,
  ) {
    require(abortedAtMillis >= 0) { "Scan abort timestamp must not be negative" }
    database.transaction { transaction ->
      val session = transaction.requireStagingSession(sessionId)
      require(abortedAtMillis >= session.requiredLongText("started_at_ms_64")) {
        "Scan abort timestamp must not precede its start"
      }
      val affected =
        transaction.execute(
          """
          UPDATE catalog_scan_session
          SET status = 'ABORTED', completed_at_ms = ?
          WHERE id = ? AND status = 'STAGING'
          """.trimIndent(),
          abortedAtMillis,
          sessionId.value,
        )
      if (affected == 0) {
        throw IllegalStateException("Scan session is not staging: ${sessionId.value}")
      }
      transaction.execute(
        "DELETE FROM catalog_scan_candidate WHERE session_id = ?",
        sessionId.value,
      )
    }
  }

  private fun requireSessionIsStaging(sessionId: ScanSessionId) {
    database.dsl.requireStagingSession(sessionId)
  }

  private fun DSLContext.requireStagingSession(sessionId: ScanSessionId): Record =
    fetchOne(
      """
      SELECT
        library_id,
        deep,
        CAST(started_at_ms AS TEXT) AS started_at_ms_64
      FROM catalog_scan_session
      WHERE id = ? AND status = 'STAGING'
      """.trimIndent(),
      sessionId.value,
    ) ?: throw IllegalStateException("Scan session is not staging: ${sessionId.value}")

  private fun DSLContext.classifyByLocation(
    sessionId: ScanSessionId,
    libraryId: String,
  ) {
    execute(
      """
      UPDATE catalog_scan_candidate AS candidate SET
        matched_book_id = book.id,
        was_deleted = book.deleted_at_ms IS NOT NULL,
        change_type = CASE
          WHEN book.file_size <> candidate.file_size
            OR book.file_modified_ms <> candidate.file_modified_ms
            OR book.media_kind <> candidate.media_kind
            OR book.source_item_id <> candidate.source_item_id
            OR coalesce(book.source_identity, '') <> coalesce(candidate.source_identity, '')
            OR book.name <> candidate.name
            OR book.oneshot <> candidate.oneshot
          THEN 'CHANGED'
          ELSE 'UNCHANGED'
        END
      FROM book
      WHERE candidate.session_id = ?
        AND book.library_id = ?
        AND book.relative_uri = candidate.relative_path
      """.trimIndent(),
      sessionId.value,
      libraryId,
    )
  }

  private fun DSLContext.classifyUniqueIdentityMoves(
    sessionId: ScanSessionId,
    libraryId: String,
  ) {
    val hasUnmatchedIdentity =
      fetchOne(
        """
        SELECT 1
        FROM catalog_scan_candidate
        WHERE session_id = ? AND matched_book_id IS NULL AND source_identity IS NOT NULL
        LIMIT 1
        """.trimIndent(),
        sessionId.value,
      ) != null
    val hasCatalogIdentity =
      fetchOne(
        """
        SELECT 1
        FROM book
        WHERE library_id = ? AND source_identity IS NOT NULL
        LIMIT 1
        """.trimIndent(),
        libraryId,
      ) != null
    if (!hasUnmatchedIdentity || !hasCatalogIdentity) return

    execute(
      """
      WITH
        unique_candidate_identity AS (
          SELECT source_identity
          FROM catalog_scan_candidate
          WHERE session_id = ? AND source_identity IS NOT NULL
          GROUP BY source_identity
          HAVING count(*) = 1
        ),
        unique_book_identity AS (
          SELECT source_identity
          FROM book
          WHERE library_id = ? AND source_identity IS NOT NULL
          GROUP BY source_identity
          HAVING count(*) = 1
        )
      UPDATE catalog_scan_candidate AS candidate SET
        matched_book_id = book.id,
        was_deleted = book.deleted_at_ms IS NOT NULL,
        change_type = 'MOVED'
      FROM book
      JOIN unique_candidate_identity candidate_identity
        ON candidate_identity.source_identity = book.source_identity
      JOIN unique_book_identity book_identity
        ON book_identity.source_identity = book.source_identity
      WHERE candidate.session_id = ?
        AND candidate.matched_book_id IS NULL
        AND candidate.source_identity IS NOT NULL
        AND book.library_id = ?
        AND book.source_identity = candidate.source_identity
        AND NOT EXISTS (
          SELECT 1 FROM catalog_scan_candidate already_matched
          WHERE already_matched.session_id = candidate.session_id
            AND already_matched.matched_book_id = book.id
        )
      """.trimIndent(),
      sessionId.value,
      libraryId,
      sessionId.value,
      libraryId,
    )
  }

  private fun DSLContext.countCandidates(
    sessionId: ScanSessionId,
    trustedCondition: String,
  ): Long =
    fetchOne(
      "SELECT count(*) FROM catalog_scan_candidate WHERE session_id = ? AND $trustedCondition",
      sessionId.value,
    ).countValue()

  private fun DSLContext.catalogEventSnapshot(
    sessionId: ScanSessionId,
    libraryId: String,
    includeDeletions: Boolean,
  ): CatalogEventSnapshot {
    val newSeriesPaths =
      fetch(
        """
        SELECT DISTINCT candidate.series_relative_path
        FROM catalog_scan_candidate candidate
        WHERE candidate.session_id = ?
          AND NOT EXISTS (
            SELECT 1 FROM series
            WHERE series.library_id = ?
              AND series.relative_uri = candidate.series_relative_path
          )
        """.trimIndent(),
        sessionId.value,
        libraryId,
      ).mapTo(linkedSetOf()) { it.requiredString("series_relative_path") }
    val updatedSeriesIds =
      fetch(
        """
        SELECT DISTINCT series.id
        FROM series
        JOIN catalog_scan_candidate candidate
          ON candidate.series_relative_path = series.relative_uri
        WHERE candidate.session_id = ?
          AND series.library_id = ?
          AND (
            series.deleted_at_ms IS NOT NULL
            OR candidate.change_type <> 'UNCHANGED'
            OR candidate.was_deleted = 1
          )
        """.trimIndent(),
        sessionId.value,
        libraryId,
      ).mapTo(linkedSetOf()) { it.requiredString("id") }
    if (!includeDeletions) {
      return CatalogEventSnapshot(newSeriesPaths, updatedSeriesIds, emptyList(), emptyList())
    }
    val deletedBooks =
      fetch(
        """
        SELECT book.id, book.series_id, book.library_id
        FROM book
        WHERE book.library_id = ?
          AND book.deleted_at_ms IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM catalog_scan_candidate candidate
            WHERE candidate.session_id = ? AND candidate.matched_book_id = book.id
          )
        ORDER BY book.id
        """.trimIndent(),
        libraryId,
        sessionId.value,
      ).map { record ->
        CatalogMutationEvent.Book(
          kind = CatalogMutationKind.DELETED,
          bookId = BookId(record.requiredString("id")),
          seriesId = SeriesId(record.requiredString("series_id")),
          libraryId = LibraryId(record.requiredString("library_id")),
        )
      }
    updatedSeriesIds += deletedBooks.map { it.seriesId.value }
    val deletedSeries =
      fetch(
        """
        SELECT series.id, series.library_id
        FROM series
        WHERE series.library_id = ?
          AND series.deleted_at_ms IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM catalog_scan_candidate candidate
            WHERE candidate.session_id = ?
              AND candidate.series_relative_path = series.relative_uri
          )
        ORDER BY series.id
        """.trimIndent(),
        libraryId,
        sessionId.value,
      ).map { record ->
        CatalogMutationEvent.Series(
          kind = CatalogMutationKind.DELETED,
          seriesId = SeriesId(record.requiredString("id")),
          libraryId = LibraryId(record.requiredString("library_id")),
        )
      }
    return CatalogEventSnapshot(
      newSeriesPaths = newSeriesPaths,
      updatedSeriesIds = updatedSeriesIds,
      deletedBooks = deletedBooks,
      deletedSeries = deletedSeries,
    )
  }

  private fun DSLContext.catalogMutationEvents(
    sessionId: ScanSessionId,
    libraryId: String,
    snapshot: CatalogEventSnapshot,
  ): List<CatalogMutationEvent> {
    val books =
      fetch(
        """
        SELECT
          book.id,
          book.series_id,
          book.library_id,
          candidate.change_type,
          candidate.was_deleted
        FROM catalog_scan_candidate candidate
        JOIN book ON book.id = candidate.matched_book_id
        WHERE candidate.session_id = ?
          AND (
            candidate.change_type <> 'UNCHANGED'
            OR candidate.was_deleted = 1
          )
        ORDER BY book.id
        """.trimIndent(),
        sessionId.value,
      ).map { record ->
        CatalogMutationEvent.Book(
          kind =
            if (record.requiredString("change_type") == "NEW") {
              CatalogMutationKind.ADDED
            } else {
              CatalogMutationKind.UPDATED
            },
          bookId = BookId(record.requiredString("id")),
          seriesId = SeriesId(record.requiredString("series_id")),
          libraryId = LibraryId(record.requiredString("library_id")),
        )
      }
    val series =
      fetch(
        """
        SELECT DISTINCT series.id, series.library_id, series.relative_uri
        FROM series
        JOIN catalog_scan_candidate candidate
          ON candidate.series_relative_path = series.relative_uri
        WHERE candidate.session_id = ? AND series.library_id = ?
        ORDER BY series.id
        """.trimIndent(),
        sessionId.value,
        libraryId,
      ).mapNotNull { record ->
        val seriesId = record.requiredString("id")
        val kind =
          when {
            record.requiredString("relative_uri") in snapshot.newSeriesPaths ->
              CatalogMutationKind.ADDED
            seriesId in snapshot.updatedSeriesIds ->
              CatalogMutationKind.UPDATED
            else -> null
          } ?: return@mapNotNull null
        CatalogMutationEvent.Series(
          kind = kind,
          seriesId = SeriesId(seriesId),
          libraryId = LibraryId(record.requiredString("library_id")),
        )
      }
    return series + books + snapshot.deletedBooks + snapshot.deletedSeries
  }

  private fun DSLContext.countAddedSeries(
    sessionId: ScanSessionId,
    libraryId: String,
  ): Long =
    fetchOne(
      """
      SELECT count(*) FROM (
        SELECT candidate.series_relative_path
        FROM catalog_scan_candidate candidate
        WHERE candidate.session_id = ?
          AND NOT EXISTS (
            SELECT 1 FROM series
            WHERE series.library_id = ? AND series.relative_uri = candidate.series_relative_path
          )
        GROUP BY candidate.series_relative_path
      )
      """.trimIndent(),
      sessionId.value,
      libraryId,
    ).countValue()

  private fun DSLContext.countRestoredSeries(
    sessionId: ScanSessionId,
    libraryId: String,
  ): Long =
    fetchOne(
      """
      SELECT count(DISTINCT series.id)
      FROM series
      JOIN catalog_scan_candidate candidate
        ON candidate.series_relative_path = series.relative_uri
      WHERE candidate.session_id = ? AND series.library_id = ?
        AND series.deleted_at_ms IS NOT NULL
      """.trimIndent(),
      sessionId.value,
      libraryId,
    ).countValue()

  private fun DSLContext.countDeletedBooks(
    sessionId: ScanSessionId,
    libraryId: String,
  ): Long =
    fetchOne(
      """
      SELECT count(*) FROM book
      WHERE library_id = ? AND deleted_at_ms IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM catalog_scan_candidate candidate
          WHERE candidate.session_id = ? AND candidate.matched_book_id = book.id
        )
      """.trimIndent(),
      libraryId,
      sessionId.value,
    ).countValue()

  private fun DSLContext.countDeletedSeries(
    sessionId: ScanSessionId,
    libraryId: String,
  ): Long =
    fetchOne(
      """
      SELECT count(*) FROM series
      WHERE library_id = ? AND deleted_at_ms IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM catalog_scan_candidate candidate
          WHERE candidate.session_id = ? AND candidate.series_relative_path = series.relative_uri
        )
      """.trimIndent(),
      libraryId,
      sessionId.value,
    ).countValue()

  private fun DSLContext.insertMissingSeries(
    sessionId: ScanSessionId,
    libraryId: String,
    nowMillis: Long,
  ) {
    execute(
      """
      INSERT INTO series (
        id, library_id, relative_uri, source_item_id, name, sort_title,
        file_modified_ms, book_count, deleted_at_ms, oneshot, created_at_ms, updated_at_ms
      )
      SELECT
        lower(hex(randomblob(16))), ?, candidate.series_relative_path,
        min(candidate.series_source_item_id), min(candidate.series_name),
        min(candidate.series_name), max(candidate.file_modified_ms), count(*),
        NULL, max(candidate.oneshot), ?, ?
      FROM catalog_scan_candidate candidate
      WHERE candidate.session_id = ?
        AND NOT EXISTS (
          SELECT 1 FROM series
          WHERE series.library_id = ? AND series.relative_uri = candidate.series_relative_path
        )
      GROUP BY candidate.series_relative_path
      """.trimIndent(),
      libraryId,
      nowMillis,
      nowMillis,
      sessionId.value,
      libraryId,
    )
  }

  private fun DSLContext.updateMatchedBooks(
    sessionId: ScanSessionId,
    libraryId: String,
    nowMillis: Long,
  ) {
    execute(
      """
      UPDATE book AS target SET
        series_id = scanned_series.id,
        relative_uri = candidate.relative_path,
        source_item_id = candidate.source_item_id,
        source_identity = candidate.source_identity,
        name = candidate.name,
        media_kind = candidate.media_kind,
        media_item_type = CASE
          WHEN target.media_item_type = CASE target.media_kind
            WHEN 'COMIC_ARCHIVE' THEN 'COMIC'
            WHEN 'EPUB' THEN 'NOVEL'
            ELSE 'BOOK'
          END
          THEN CASE candidate.media_kind
            WHEN 'COMIC_ARCHIVE' THEN 'COMIC'
            WHEN 'EPUB' THEN 'NOVEL'
            ELSE 'BOOK'
          END
          ELSE target.media_item_type
        END,
        file_hash = CASE
          WHEN candidate.file_size = target.file_size
            AND candidate.file_modified_ms = target.file_modified_ms
          THEN target.file_hash ELSE '' END,
        file_hash_koreader = CASE
          WHEN candidate.file_size = target.file_size
            AND candidate.file_modified_ms = target.file_modified_ms
          THEN target.file_hash_koreader ELSE '' END,
        file_size = candidate.file_size,
        file_modified_ms = candidate.file_modified_ms,
        deleted_at_ms = NULL,
        oneshot = candidate.oneshot,
        updated_at_ms = ?
      FROM catalog_scan_candidate candidate
      JOIN series scanned_series
        ON scanned_series.library_id = ?
        AND scanned_series.relative_uri = candidate.series_relative_path
      WHERE target.library_id = ?
        AND candidate.session_id = ?
        AND candidate.matched_book_id = target.id
        AND (candidate.change_type <> 'UNCHANGED' OR candidate.was_deleted = 1)
      """.trimIndent(),
      nowMillis,
      libraryId,
      libraryId,
      sessionId.value,
    )
  }

  private fun DSLContext.insertNewBooks(
    sessionId: ScanSessionId,
    libraryId: String,
    nowMillis: Long,
  ) {
    execute(
      """
      INSERT INTO book (
        id, library_id, series_id, relative_uri, source_item_id, source_identity,
        name, media_kind, media_item_type, file_size, file_modified_ms,
        file_hash, file_hash_koreader, number, deleted_at_ms, oneshot,
        created_at_ms, updated_at_ms
      )
      SELECT
        lower(hex(randomblob(16))), ?, series.id, candidate.relative_path,
        candidate.source_item_id, candidate.source_identity, candidate.name,
        candidate.media_kind,
        CASE candidate.media_kind
          WHEN 'COMIC_ARCHIVE' THEN 'COMIC'
          WHEN 'EPUB' THEN 'NOVEL'
          ELSE 'BOOK'
        END,
        candidate.file_size, candidate.file_modified_ms,
        '', '', 0, NULL, candidate.oneshot, ?, ?
      FROM catalog_scan_candidate candidate
      JOIN series ON series.library_id = ?
        AND series.relative_uri = candidate.series_relative_path
      WHERE candidate.session_id = ? AND candidate.change_type = 'NEW'
      """.trimIndent(),
      libraryId,
      nowMillis,
      nowMillis,
      libraryId,
      sessionId.value,
    )
  }

  private fun DSLContext.bindNewBooks(
    sessionId: ScanSessionId,
    libraryId: String,
  ) {
    execute(
      """
      UPDATE catalog_scan_candidate AS candidate SET
        matched_book_id = (
          SELECT book.id FROM book
          WHERE book.library_id = ? AND book.relative_uri = candidate.relative_path
        )
      WHERE candidate.session_id = ? AND candidate.change_type = 'NEW'
      """.trimIndent(),
      libraryId,
      sessionId.value,
    )
  }

  private fun DSLContext.softDeleteMissingBooks(
    sessionId: ScanSessionId,
    libraryId: String,
    nowMillis: Long,
  ) {
    execute(
      """
      UPDATE book SET deleted_at_ms = ?, updated_at_ms = ?
      WHERE library_id = ? AND deleted_at_ms IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM catalog_scan_candidate candidate
          WHERE candidate.session_id = ? AND candidate.matched_book_id = book.id
        )
      """.trimIndent(),
      nowMillis,
      nowMillis,
      libraryId,
      sessionId.value,
    )
  }

  private fun DSLContext.updateScannedSeries(
    sessionId: ScanSessionId,
    libraryId: String,
    completedAtMillis: Long,
    allowCountDecrease: Boolean,
  ) {
    execute(
      """
      UPDATE series SET
        source_item_id = aggregate.source_item_id,
        name = aggregate.series_name,
        file_modified_ms = aggregate.file_modified_ms,
        book_count = CASE WHEN ? THEN aggregate.book_count
          ELSE max(series.book_count, aggregate.book_count) END,
        deleted_at_ms = NULL,
        oneshot = aggregate.oneshot,
        updated_at_ms = ?
      FROM (
        SELECT
          series_relative_path,
          min(series_source_item_id) AS source_item_id,
          min(series_name) AS series_name,
          max(file_modified_ms) AS file_modified_ms,
          count(*) AS book_count,
          max(oneshot) AS oneshot
        FROM catalog_scan_candidate
        WHERE session_id = ?
        GROUP BY series_relative_path
      ) aggregate
      WHERE series.library_id = ?
        AND series.relative_uri = aggregate.series_relative_path
      """.trimIndent(),
      allowCountDecrease.toSqliteInt(),
      completedAtMillis,
      sessionId.value,
      libraryId,
    )
  }

  private fun DSLContext.softDeleteMissingSeries(
    sessionId: ScanSessionId,
    libraryId: String,
    nowMillis: Long,
  ) {
    execute(
      """
      UPDATE series SET deleted_at_ms = ?, book_count = 0, updated_at_ms = ?
      WHERE library_id = ? AND deleted_at_ms IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM catalog_scan_candidate candidate
          WHERE candidate.session_id = ? AND candidate.series_relative_path = series.relative_uri
        )
      """.trimIndent(),
      nowMillis,
      nowMillis,
      libraryId,
      sessionId.value,
    )
  }

  private fun DSLContext.enqueueAnalysis(
    sessionId: ScanSessionId,
    deep: Boolean,
    nowMillis: Long,
  ) {
    execute(
      """
      INSERT INTO task (
        id, task_type, payload_json, priority, group_id, state, attempt_count,
        max_attempts, available_at_ms, created_at_ms, updated_at_ms
      )
      SELECT
        'ANALYZE_BOOK_' || book.id,
        'ANALYZE_BOOK',
        json_object('bookId', book.id),
        ?,
        book.series_id,
        'PENDING',
        0,
        3,
        ?,
        ?,
        ?
      FROM catalog_scan_candidate candidate
      JOIN book ON book.id = candidate.matched_book_id
      WHERE candidate.session_id = ?
        AND (? OR candidate.change_type <> 'UNCHANGED')
      ON CONFLICT(id) DO UPDATE SET
        payload_json = excluded.payload_json,
        priority = max(task.priority, excluded.priority),
        group_id = excluded.group_id,
        available_at_ms = min(task.available_at_ms, excluded.available_at_ms),
        updated_at_ms = excluded.updated_at_ms
      WHERE task.state = 'PENDING'
      """.trimIndent(),
      TaskPriority.DEFAULT,
      nowMillis,
      nowMillis,
      nowMillis,
      sessionId.value,
      deep.toSqliteInt(),
    )
  }

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

  private fun Record?.countValue(): Long =
    this?.get(0)?.let { value ->
      require(value is Number) { "Database count must be numeric" }
      value.toLong()
    } ?: 0L

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0

  private data class CatalogEventSnapshot(
    val newSeriesPaths: Set<String>,
    val updatedSeriesIds: Set<String>,
    val deletedBooks: List<CatalogMutationEvent.Book>,
    val deletedSeries: List<CatalogMutationEvent.Series>,
  )

  private data class CatalogCompletion(
    val result: CatalogReconciliationResult,
    val events: List<CatalogMutationEvent>,
  )

  private companion object {
    val STAGE_CANDIDATE_SQL =
      """
      INSERT INTO catalog_scan_candidate (
        session_id, relative_path, source_item_id, source_identity, name,
        media_kind, file_size, file_modified_ms, series_relative_path,
        series_source_item_id, series_name, oneshot
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent()
  }
}
