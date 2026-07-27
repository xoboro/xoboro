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
}
