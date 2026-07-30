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

interface DurableTaskQueue {
  fun enqueue(
    task: DurableTask,
    nowMillis: Long,
  ): Boolean

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
