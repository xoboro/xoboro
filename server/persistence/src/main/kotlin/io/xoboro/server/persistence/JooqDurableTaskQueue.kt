package io.xoboro.server.persistence

import io.xoboro.core.application.ClaimedTask
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.application.TaskEnqueue
import io.xoboro.core.application.TaskPriority
import java.util.logging.Level
import java.util.logging.Logger
import org.jooq.Record
import org.jooq.exception.DataAccessException

class JooqDurableTaskQueue(
  private val database: XoboroDatabase,
) : DurableTaskQueue {
  /**
   * Inserts [task], or updates an existing row with the same id.
   *
   * Task ids are deterministic for most types (`REFRESH_SERIES_METADATA_<seriesId>` and friends),
   * so a re-enqueue routinely collides with a row that is already there. State decides what
   * happens:
   *
   * - `PENDING` — refreshed in place, keeping the earliest `available_at_ms`.
   * - `DEAD` — revived back to `PENDING`, **without resetting `attempt_count`**. `claimNext`
   *   filters candidates on state, availability and group but not on attempts, so a revived row
   *   that has already exhausted `max_attempts` is claimed once, increments past the limit, and
   *   returns to `DEAD` after that single run. A task that keeps dying therefore costs one
   *   attempt per external re-enqueue rather than a fresh `max_attempts` budget, and
   *   `attempt_count` and `last_error` survive as the record of how often it has died.
   * - `RUNNING` — left alone, reported as [TaskEnqueue.ALREADY_RUNNING]. Mutating a row under a
   *   live lease would race `complete`/`fail`, which key on `lease_token`. A lease that is
   *   genuinely stuck is recovered by [claimNext], which is the right place for it.
   *
   * A locked store answers [TaskEnqueue.UNAVAILABLE] rather than throwing. SQLite admits one
   * writer and a library scan holds the lock for minutes, so this insert can fail for reasons that
   * have nothing to do with the task. Letting it escape turned
   * `POST /api/v1/libraries/{id}/metadata/refresh` into a `500` mid-way through its fan-out,
   * against a library of 145,105 archives. Distinct from [TaskEnqueue.ALREADY_RUNNING] because
   * that one means the work is in flight while this one means the caller still owes it.
   *
   * Reviving matters because the previous guard only matched `PENDING`: a single death made a
   * deterministic id permanently un-enqueueable, silently, with no log line, and the only
   * remediation on offer deletes pending work along with the corpse.
   *
   * Note for whoever adds a deterministic-id task whose payload is not a pure function of its
   * id: a `RUNNING` collision discards the newer payload, and lease recovery keeps the old one.
   * Every current deterministic id derives its payload from the id, so nothing depends on that
   * today.
   */
  override fun enqueue(
    task: DurableTask,
    nowMillis: Long,
  ): TaskEnqueue {
    require(nowMillis >= 0) { "Current timestamp must not be negative" }
    val written =
      try {
        database.dsl.execute(
          """
          INSERT INTO task (
            id, task_type, payload_json, priority, group_id, exclusion_key, state, attempt_count,
            max_attempts, available_at_ms, created_at_ms, updated_at_ms
          ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?)
          ON CONFLICT(id) DO UPDATE SET
            task_type = excluded.task_type,
            payload_json = excluded.payload_json,
            priority = max(task.priority, excluded.priority),
            group_id = excluded.group_id,
            exclusion_key = excluded.exclusion_key,
            max_attempts = excluded.max_attempts,
            state = 'PENDING',
            available_at_ms =
              CASE
                WHEN task.state = 'DEAD' THEN excluded.available_at_ms
                ELSE min(task.available_at_ms, excluded.available_at_ms)
              END,
            updated_at_ms = excluded.updated_at_ms
          WHERE task.state IN ('PENDING', 'DEAD')
          """.trimIndent(),
          task.id,
          task.type,
          task.payloadJson,
          task.priority,
          task.groupId,
          task.exclusionKey,
          task.maxAttempts,
          task.availableAtMillis,
          nowMillis,
          nowMillis,
        )
      } catch (failure: DataAccessException) {
        if (failure.isDatabaseLocked()) return TaskEnqueue.UNAVAILABLE else throw failure
      }
    return if (written > 0) TaskEnqueue.QUEUED else TaskEnqueue.ALREADY_RUNNING
  }

  override fun claimNext(
    workerId: String,
    leaseToken: String,
    nowMillis: Long,
    leaseDurationMillis: Long,
  ): ClaimedTask? {
    require(workerId.isNotBlank()) { "Worker ID must not be blank" }
    require(leaseToken.isNotBlank()) { "Lease token must not be blank" }
    if (!hasActionableTask(nowMillis)) {
      return null
    }
    val leaseExpiresAtMillis = leaseExpiration(nowMillis, leaseDurationMillis)

    return database.transaction { transaction ->
      // RETURNING lets this report every row it dead-letters, not just the one (if any) this
      // call goes on to claim below: any worker's claimNext can recover any other worker's
      // expired lease, so a crashed worker's task can go silently DEAD here with nobody else
      // ever seeing the transition otherwise.
      transaction
        .fetch(
          """
          UPDATE task SET
            state = CASE WHEN attempt_count >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
            available_at_ms = CASE
              WHEN attempt_count >= max_attempts THEN available_at_ms
              ELSE ?
            END,
            lease_owner = NULL,
            lease_token = NULL,
            lease_expires_at_ms = NULL,
            last_error = CASE
              WHEN attempt_count >= max_attempts THEN coalesce(last_error, 'Lease expired')
              ELSE last_error
            END,
            updated_at_ms = ?
          WHERE state = 'RUNNING' AND lease_expires_at_ms <= ?
          RETURNING id, task_type, attempt_count, max_attempts, last_error, state
          """.trimIndent(),
          nowMillis,
          nowMillis,
          nowMillis,
        )
        .filter { record -> record.requiredString("state") == "DEAD" }
        .forEach(::logLeaseExpiredDeadLetter)

      transaction
        .fetchOne(
          """
          -- A background task waiting two minutes competes at DEFAULT priority, ordered by its
          -- original creation time. The two inner candidates stay indexable; only their two rows
          -- are sorted together, avoiding a computed-priority sort over the entire pending queue.
          WITH normal_candidate AS (
            SELECT candidate.id, candidate.priority AS effective_priority,
              candidate.created_at_ms
            FROM task candidate
            WHERE candidate.state = 'PENDING'
              AND candidate.available_at_ms <= ?
              AND (
                candidate.group_id IS NULL
                OR NOT EXISTS (
                  SELECT 1
                  FROM task active
                  WHERE active.state = 'RUNNING'
                    AND active.group_id = candidate.group_id
                    AND active.lease_expires_at_ms > ?
                )
              )
              AND (
                candidate.exclusion_key IS NULL
                OR NOT EXISTS (
                  SELECT 1
                  FROM task active
                  WHERE active.state = 'RUNNING'
                    AND active.exclusion_key = candidate.exclusion_key
                    AND active.lease_expires_at_ms > ?
                )
              )
            ORDER BY candidate.priority DESC, candidate.created_at_ms, candidate.id
            LIMIT 1
          ),
          aged_candidate AS (
            SELECT candidate.id, $BACKGROUND_EFFECTIVE_PRIORITY AS effective_priority,
              candidate.created_at_ms
            FROM task AS candidate INDEXED BY task_pending_background_age_idx
            WHERE candidate.state = 'PENDING'
              AND candidate.priority < $BACKGROUND_EFFECTIVE_PRIORITY
              AND candidate.created_at_ms <= ?
              AND candidate.available_at_ms <= ?
              AND (
                candidate.group_id IS NULL
                OR NOT EXISTS (
                  SELECT 1
                  FROM task active
                  WHERE active.state = 'RUNNING'
                    AND active.group_id = candidate.group_id
                    AND active.lease_expires_at_ms > ?
                )
              )
              AND (
                candidate.exclusion_key IS NULL
                OR NOT EXISTS (
                  SELECT 1
                  FROM task active
                  WHERE active.state = 'RUNNING'
                    AND active.exclusion_key = candidate.exclusion_key
                    AND active.lease_expires_at_ms > ?
                )
              )
            ORDER BY candidate.created_at_ms, candidate.id
            LIMIT 1
          ),
          chosen_candidate AS (
            SELECT id
            FROM (
              SELECT * FROM normal_candidate
              UNION ALL
              SELECT * FROM aged_candidate
            ) candidates
            ORDER BY effective_priority DESC, created_at_ms, id
            LIMIT 1
          )
          UPDATE task SET
            state = 'RUNNING',
            attempt_count = attempt_count + 1,
            lease_owner = ?,
            lease_token = ?,
            lease_expires_at_ms = ?,
            updated_at_ms = ?
          WHERE id = (SELECT id FROM chosen_candidate)
          AND state = 'PENDING'
          RETURNING
            id, task_type, payload_json, priority, group_id, exclusion_key, max_attempts,
            attempt_count, lease_owner, lease_token,
            CAST(available_at_ms AS TEXT) AS available_at_ms_64,
            CAST(lease_expires_at_ms AS TEXT) AS lease_expires_at_ms_64
          """.trimIndent(),
          nowMillis,
          nowMillis,
          nowMillis,
          nowMillis - BACKGROUND_STARVATION_LIMIT_MILLIS,
          nowMillis,
          nowMillis,
          nowMillis,
          workerId,
          leaseToken,
          leaseExpiresAtMillis,
          nowMillis,
        )
        ?.toClaimedTask()
    }
  }

  /**
   * The only signal an operator gets that [record]'s task was dead-lettered by lease-expiry
   * recovery rather than an explicit handler failure - the worker that held the lease may have
   * crashed before ever calling `fail()`, which is exactly the death an operator most needs to
   * know about. Mirrors [io.xoboro.server.tasks.DurableTaskWorker]'s dead-letter log (WARNING,
   * capped error text, no raw throwable - there isn't one here regardless), but lives here
   * because this transition is decided entirely inside this bulk `UPDATE ... RETURNING`, with no
   * single caller positioned to observe it otherwise.
   */
  private fun logLeaseExpiredDeadLetter(record: Record) {
    logger.log(
      Level.WARNING,
      "Task ${record.requiredString("id")} (${record.requiredString("task_type")}) " +
        "dead-lettered after its lease expired at ${record.requiredInt("attempt_count")}/" +
        "${record.requiredInt("max_attempts")} attempts: " +
        record.requiredString("last_error").take(DEAD_LETTER_LOG_ERROR_LIMIT),
    )
  }

  /**
   * Decides whether [claimNext] has anything at all to do, so an idle poll costs a read instead of
   * a write.
   *
   * Without this, every poll opens a write transaction unconditionally: the lease-recovery
   * `UPDATE` runs before the claim query, and SQLite takes the WAL write lock when an `UPDATE`
   * starts, whether or not it matches a row. At the production 500ms poll interval that is a
   * permanent stream of no-op writes competing with real writers for the same lock.
   *
   * Two deliberate choices:
   *
   * 1. **Runs outside the write transaction, not as its first statement.** Opening the write
   *    transaction with a `SELECT` would turn `claimNext` into the read-then-write shape that
   *    fails with `SQLITE_BUSY_SNAPSHOT` when it later upgrades to a writer (see
   *    [JooqBookMetadataAggregationRepository]). Keeping that transaction write-first preserves
   *    its immunity.
   * 2. **Issued on the autocommit connection, not inside [XoboroDatabase.transaction].** A pooled
   *    connection may have been left in `IMMEDIATE` transaction mode by another repository, which
   *    would make even a read-only `BEGIN` acquire the write lock - reintroducing the very cost
   *    this probe exists to avoid. A single autocommit statement never begins a transaction.
   *
   * The predicate is intentionally looser than the claim query: it ignores group serialization, so
   * it can admit a poll that goes on to claim nothing (same write as today), but it can never hide
   * a claimable task.
   */
  private fun hasActionableTask(nowMillis: Long): Boolean =
    database.dsl.fetchOne(
      """
      SELECT 1 AS actionable
      WHERE EXISTS (
        SELECT 1 FROM task WHERE state = 'RUNNING' AND lease_expires_at_ms <= ?
      ) OR EXISTS (
        SELECT 1 FROM task WHERE state = 'PENDING' AND available_at_ms <= ?
      )
      """.trimIndent(),
      nowMillis,
      nowMillis,
    ) != null

  override fun renewLease(
    taskId: String,
    leaseToken: String,
    nowMillis: Long,
    leaseDurationMillis: Long,
  ): Boolean {
    require(taskId.isNotBlank()) { "Task ID must not be blank" }
    require(leaseToken.isNotBlank()) { "Lease token must not be blank" }
    val leaseExpiresAtMillis = leaseExpiration(nowMillis, leaseDurationMillis)
    return database.dsl.execute(
      """
      UPDATE task SET lease_expires_at_ms = ?, updated_at_ms = ?
      WHERE id = ? AND state = 'RUNNING' AND lease_token = ?
        AND lease_expires_at_ms > ?
      """.trimIndent(),
      leaseExpiresAtMillis,
      nowMillis,
      taskId,
      leaseToken,
      nowMillis,
    ) == 1
  }

  override fun complete(
    taskId: String,
    leaseToken: String,
  ): Boolean {
    require(taskId.isNotBlank()) { "Task ID must not be blank" }
    require(leaseToken.isNotBlank()) { "Lease token must not be blank" }
    return database.dsl.execute(
      "DELETE FROM task WHERE id = ? AND state = 'RUNNING' AND lease_token = ?",
      taskId,
      leaseToken,
    ) == 1
  }

  override fun fail(
    taskId: String,
    leaseToken: String,
    error: String,
    retryAtMillis: Long?,
    nowMillis: Long,
  ): Boolean {
    require(taskId.isNotBlank()) { "Task ID must not be blank" }
    require(leaseToken.isNotBlank()) { "Lease token must not be blank" }
    require(error.isNotBlank()) { "Task failure must not be blank" }
    require(nowMillis >= 0) { "Current timestamp must not be negative" }
    require(retryAtMillis == null || retryAtMillis >= nowMillis) {
      "Task retry timestamp must not precede the current timestamp"
    }
    val shouldRetry = retryAtMillis != null
    return database.dsl.execute(
      """
      UPDATE task SET
        state = CASE
          WHEN ? AND attempt_count < max_attempts THEN 'PENDING'
          ELSE 'DEAD'
        END,
        available_at_ms = CASE
          WHEN ? AND attempt_count < max_attempts THEN ?
          ELSE available_at_ms
        END,
        lease_owner = NULL,
        lease_token = NULL,
        lease_expires_at_ms = NULL,
        last_error = ?,
        updated_at_ms = ?
      WHERE id = ? AND state = 'RUNNING' AND lease_token = ?
      """.trimIndent(),
      shouldRetry.toSqliteInt(),
      shouldRetry.toSqliteInt(),
      retryAtMillis ?: nowMillis,
      error,
      nowMillis,
      taskId,
      leaseToken,
    ) == 1
  }

  override fun release(
    taskId: String,
    leaseToken: String,
    reason: String,
    retryAtMillis: Long,
    nowMillis: Long,
  ): Boolean {
    require(taskId.isNotBlank()) { "Task ID must not be blank" }
    require(leaseToken.isNotBlank()) { "Lease token must not be blank" }
    require(reason.isNotBlank()) { "Task release reason must not be blank" }
    require(nowMillis >= 0) { "Current timestamp must not be negative" }
    require(retryAtMillis >= nowMillis) {
      "Task retry timestamp must not precede the current timestamp"
    }
    // `claimNext` charged the attempt on the way in, so handing it back is a decrement rather than a
    // no-op. Clamped at zero so a row that somehow arrives here uncharged cannot go negative and
    // violate the table's non-negative check, and clamped below `max_attempts` at the top: a task
    // revived from `DEAD` keeps a count already past its ceiling, and leaving it there would mean a
    // release that claims the attempt never happened still left the row one recovery sweep from
    // being dead-lettered for having spent them all.
    return database.dsl.execute(
      """
      UPDATE task SET
        state = 'PENDING',
        attempt_count = CASE
          WHEN attempt_count >= max_attempts THEN max_attempts - 1
          WHEN attempt_count > 0 THEN attempt_count - 1
          ELSE 0
        END,
        available_at_ms = ?,
        lease_owner = NULL,
        lease_token = NULL,
        lease_expires_at_ms = NULL,
        last_error = ?,
        updated_at_ms = ?
      WHERE id = ? AND state = 'RUNNING' AND lease_token = ?
      """.trimIndent(),
      retryAtMillis,
      reason,
      nowMillis,
      taskId,
      leaseToken,
    ) == 1
  }

  override fun counts(): TaskCounts {
    val counts =
      database.dsl
        .fetch("SELECT state, count(*) AS task_count FROM task GROUP BY state")
        .associate { record ->
          record.requiredString("state") to record.requiredNumberLong("task_count")
        }
    return TaskCounts(
      pending = counts["PENDING"] ?: 0L,
      running = counts["RUNNING"] ?: 0L,
      dead = counts["DEAD"] ?: 0L,
    )
  }

  /**
   * Counts only `PENDING` and `RUNNING` rows. This feeds the Komga-compat `TaskQueueStatus` SSE
   * event, where `count`/`countByType` are read by operators as "work that is queued or running
   * right now". A `DEAD` task has given up permanently, so including it here would report a
   * permanently-failed task as if it were about to run - the opposite of what the field means.
   */
  fun countsByType(): Map<String, Int> =
    database.dsl
      .fetch(
        """
        SELECT task_type, count(*) AS task_count
        FROM task
        WHERE state <> 'DEAD'
        GROUP BY task_type
        ORDER BY task_type
        """.trimIndent(),
      )
      .associate { record ->
        record.requiredString("task_type") to
          requireNotNull(record.get("task_count") as? Number) {
            "Database field 'task_count' must be numeric"
          }.toInt()
      }

  override fun clearUnclaimed(): Int =
    database.dsl.execute("DELETE FROM task WHERE state <> 'RUNNING'")

  override fun clearPending(): Int =
    database.dsl.execute("DELETE FROM task WHERE state = 'PENDING'")

  override fun clearDead(): Int =
    database.dsl.execute("DELETE FROM task WHERE state = 'DEAD'")

  private fun Record.toClaimedTask(): ClaimedTask =
    ClaimedTask(
      task =
        DurableTask(
          id = requiredString("id"),
          type = requiredString("task_type"),
          payloadJson = requiredString("payload_json"),
          priority = requiredInt("priority"),
          groupId = get("group_id", String::class.java),
          exclusionKey = get("exclusion_key", String::class.java),
          availableAtMillis = requiredLongText("available_at_ms_64"),
          maxAttempts = requiredInt("max_attempts"),
        ),
      attempt = requiredInt("attempt_count"),
      leaseOwner = requiredString("lease_owner"),
      leaseToken = requiredString("lease_token"),
      leaseExpiresAtMillis = requiredLongText("lease_expires_at_ms_64"),
    )

  private fun leaseExpiration(
    nowMillis: Long,
    durationMillis: Long,
  ): Long {
    require(nowMillis >= 0) { "Current timestamp must not be negative" }
    require(durationMillis > 0) { "Lease duration must be positive" }
    require(nowMillis <= Long.MAX_VALUE - durationMillis) { "Lease expiration overflows" }
    return nowMillis + durationMillis
  }

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredInt(field: String): Int =
    requireNotNull(get(field, Int::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.requiredNumberLong(field: String): Long =
    requireNotNull(get(field) as? Number) { "Database field '$field' must be numeric" }
      .toLong()

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0

  private companion object {
    private val logger = Logger.getLogger(JooqDurableTaskQueue::class.java.name)
    private const val DEAD_LETTER_LOG_ERROR_LIMIT = 500
    private const val BACKGROUND_EFFECTIVE_PRIORITY: Int = TaskPriority.DEFAULT
    private const val BACKGROUND_STARVATION_LIMIT_MILLIS: Long = 120_000L
  }
}
