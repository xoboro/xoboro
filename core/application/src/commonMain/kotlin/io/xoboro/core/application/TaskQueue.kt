package io.xoboro.core.application

object TaskPriority {
  const val HIGHEST: Int = 8
  const val HIGH: Int = 6
  const val DEFAULT: Int = 4
  const val LOW: Int = 2
  const val LOWEST: Int = 0
}

data class DurableTask(
  val id: String,
  val type: String,
  val payloadJson: String,
  val priority: Int = TaskPriority.DEFAULT,
  val groupId: String? = null,
  val availableAtMillis: Long,
  val maxAttempts: Int = 3,
) {
  init {
    require(id.isNotBlank()) { "Task ID must not be blank" }
    require(type.isNotBlank()) { "Task type must not be blank" }
    require(payloadJson.isNotBlank()) { "Task payload must not be blank" }
    require(priority in TaskPriority.LOWEST..TaskPriority.HIGHEST) {
      "Task priority must be between ${TaskPriority.LOWEST} and ${TaskPriority.HIGHEST}"
    }
    require(groupId == null || groupId.isNotBlank()) { "Task group ID must be null or non-blank" }
    require(availableAtMillis >= 0) { "Task availability timestamp must not be negative" }
    require(maxAttempts > 0) { "Task max attempts must be positive" }
  }
}

data class ClaimedTask(
  val task: DurableTask,
  val attempt: Int,
  val leaseOwner: String,
  val leaseToken: String,
  val leaseExpiresAtMillis: Long,
) {
  init {
    require(attempt > 0) { "Claim attempt must be positive" }
    require(leaseOwner.isNotBlank()) { "Lease owner must not be blank" }
    require(leaseToken.isNotBlank()) { "Lease token must not be blank" }
    require(leaseExpiresAtMillis >= 0) { "Lease expiration must not be negative" }
  }
}

enum class TaskState {
  PENDING,
  RUNNING,
  DEAD,
}

data class TaskCounts(
  val pending: Long,
  val running: Long,
  val dead: Long,
)

/**
 * What became of a [DurableTaskQueue.enqueue].
 *
 * Three states rather than a boolean, because "not queued" covers two situations that ask the
 * caller for opposite things. [ALREADY_RUNNING] means the work is in flight and there is nothing to
 * do; [UNAVAILABLE] means nothing was written at all and the caller still owes the work. Reporting
 * both as `false` is what let a busy store look like a completed enqueue.
 */
enum class TaskEnqueue {
  /** Queued - freshly inserted, or refreshed/revived in place over an existing row. */
  QUEUED,

  /** A row with this id holds a live lease, so it was left untouched. See [DurableTaskQueue.enqueue]. */
  ALREADY_RUNNING,

  /**
   * The store could not be written to, and the task is not queued.
   *
   * SQLite admits one writer, and a library scan holds the write lock for minutes - past any
   * `busy_timeout` worth configuring. A caller on a request path should report this as retryable
   * rather than as a failure; a caller already inside a task should let the task retry.
   */
  UNAVAILABLE,
}

/**
 * Thrown when a task could not be queued because the store was busy.
 *
 * Carries the task id rather than a generic message so a dead-letter record names what was
 * deferred. See [enqueueOrRetry] for who is expected to let this propagate.
 */
class TaskStoreUnavailableException(
  taskId: String,
) : RuntimeException("The task store was busy; $taskId was not queued")

/**
 * Enqueues [task], throwing [TaskStoreUnavailableException] when the store is busy.
 *
 * This is the idiom for a caller **already running inside a durable task**. A throw fails that
 * task, the worker retries it with backoff, and task ids are deterministic - so the retry
 * re-enqueues idempotently and the work is deferred rather than lost.
 *
 * A caller on a **request path must not use this**. It should enqueue one fan-out task and report
 * [TaskEnqueue.UNAVAILABLE] as retryable, because a request cannot be replayed by the worker.
 *
 * Returns whether the task was newly queued, so a `count {}` over many tasks still reports how many
 * it added - the distinction this preserves is that a busy store now stops the loop instead of
 * being tallied as a skip. Counting it as a skip is what let a fan-out report success while
 * silently dropping most of its work.
 */
fun DurableTaskQueue.enqueueOrRetry(
  task: DurableTask,
  nowMillis: Long,
): Boolean =
  when (enqueue(task, nowMillis)) {
    TaskEnqueue.QUEUED -> true
    TaskEnqueue.ALREADY_RUNNING -> false
    TaskEnqueue.UNAVAILABLE -> throw TaskStoreUnavailableException(task.id)
  }

interface DurableTaskQueue {
  fun enqueue(
    task: DurableTask,
    nowMillis: Long,
  ): TaskEnqueue

  fun claimNext(
    workerId: String,
    leaseToken: String,
    nowMillis: Long,
    leaseDurationMillis: Long,
  ): ClaimedTask?

  fun renewLease(
    taskId: String,
    leaseToken: String,
    nowMillis: Long,
    leaseDurationMillis: Long,
  ): Boolean

  fun complete(
    taskId: String,
    leaseToken: String,
  ): Boolean

  fun fail(
    taskId: String,
    leaseToken: String,
    error: String,
    retryAtMillis: Long?,
    nowMillis: Long,
  ): Boolean

  /**
   * Returns a claimed task to `PENDING` **without charging it an attempt**.
   *
   * [fail] spends part of a task's `max_attempts` budget, which is right when the task itself is at
   * fault and wrong when the store merely could not be written to. A library scan holds SQLite's one
   * write lock for as long as its reconciliation takes, and a fan-out that has to enqueue thousands
   * of children will meet that lock; charged as failures, contention alone dead-lettered a
   * `REFRESH_LIBRARY_METADATA` task at 10/10 attempts and left a library's metadata unfilled. The
   * work was never invalid, so the attempt is given back and the task waits instead.
   *
   * This has no attempt ceiling of its own, deliberately: a store that stays unwritable forever is
   * an outage, and the task deferring until it clears is better than the task being discarded during
   * it. [retryAtMillis] is what keeps that from becoming a spin.
   *
   * Returns whether the lease still held, on the same terms as [fail].
   */
  fun release(
    taskId: String,
    leaseToken: String,
    reason: String,
    retryAtMillis: Long,
    nowMillis: Long,
  ): Boolean

  fun counts(): TaskCounts

  /**
   * Deletes every row not currently `RUNNING` (`PENDING` and `DEAD` together). This backs the
   * Komga-compat task-clearing route, which mirrors real Komga's "clear the queue" behavior and
   * makes no PENDING/DEAD distinction of its own - narrowing this predicate would change that
   * compat surface's behavior, so it stays as-is. A native caller wanting only one of the two
   * states should use [clearPending] or [clearDead] instead.
   */
  fun clearUnclaimed(): Int =
    error("Clearing unclaimed tasks is not supported by this queue")

  /**
   * Deletes every `PENDING` row and leaves `RUNNING` and `DEAD` rows untouched. This is the
   * native-only counterpart to [clearUnclaimed] that an endpoint literally named "unclaimed" can
   * honor without also silently discarding the dead-task history an operator may still want to
   * inspect.
   */
  fun clearPending(): Int =
    error("Clearing pending tasks is not supported by this queue")

  /**
   * Deletes every `DEAD` row and leaves `PENDING` and `RUNNING` rows untouched. Task ids are
   * deterministic and a dead task revives back to `PENDING` on its next enqueue without resetting
   * its attempt count (see [enqueue]), so a task that keeps dying costs one attempt per
   * re-enqueue rather than a fresh budget. Removing its row entirely is the only way to give it a
   * fresh budget, and doing so here never discards work that is still queued to run.
   */
  fun clearDead(): Int =
    error("Clearing dead tasks is not supported by this queue")
}
