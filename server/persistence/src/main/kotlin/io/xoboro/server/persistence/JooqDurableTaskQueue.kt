package io.xoboro.server.persistence

import io.xoboro.core.application.ClaimedTask
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.TaskCounts
import java.util.logging.Level
import java.util.logging.Logger
import org.jooq.Record

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
   * - `RUNNING` — left alone, and the enqueue reports false. Mutating a row under a live lease
   *   would race `complete`/`fail`, which key on `lease_token`. A lease that is genuinely stuck
   *   is recovered by [claimNext], which is the right place for it.
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
  ): Boolean {
    require(nowMillis >= 0) { "Current timestamp must not be negative" }
    return database.dsl.execute(
      """
      INSERT INTO task (
        id, task_type, payload_json, priority, group_id, state, attempt_count,
        max_attempts, available_at_ms, created_at_ms, updated_at_ms
      ) VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        task_type = excluded.task_type,
        payload_json = excluded.payload_json,
        priority = excluded.priority,
        group_id = excluded.group_id,
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
      task.maxAttempts,
      task.availableAtMillis,
      nowMillis,
      nowMillis,
    ) > 0
  }

  override fun claimNext(
    workerId: String,
    leaseToken: String,
    nowMillis: Long,
    leaseDurationMillis: Long,
  ): ClaimedTask? {
    require(workerId.isNotBlank()) { "Worker ID must not be blank" }
    require(leaseToken.isNotBlank()) { "Lease token must not be blank" }
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
          UPDATE task SET
            state = 'RUNNING',
            attempt_count = attempt_count + 1,
            lease_owner = ?,
            lease_token = ?,
            lease_expires_at_ms = ?,
            updated_at_ms = ?
          WHERE id = (
            SELECT candidate.id
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
            ORDER BY candidate.priority DESC, candidate.created_at_ms, candidate.id
            LIMIT 1
          )
          AND state = 'PENDING'
          RETURNING
            id, task_type, payload_json, priority, group_id, max_attempts,
            attempt_count, lease_owner, lease_token,
            CAST(available_at_ms AS TEXT) AS available_at_ms_64,
            CAST(lease_expires_at_ms AS TEXT) AS lease_expires_at_ms_64
          """.trimIndent(),
          workerId,
          leaseToken,
          leaseExpiresAtMillis,
          nowMillis,
          nowMillis,
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

  private fun Record.toClaimedTask(): ClaimedTask =
    ClaimedTask(
      task =
        DurableTask(
          id = requiredString("id"),
          type = requiredString("task_type"),
          payloadJson = requiredString("payload_json"),
          priority = requiredInt("priority"),
          groupId = get("group_id", String::class.java),
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
  }
}
