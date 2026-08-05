package io.xoboro.server.tasks

import io.xoboro.core.application.ClaimedTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.TaskStoreUnavailableException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

data class TaskWorkerPolicy(
  val leaseDurationMillis: Long = 600_000,
  val initialRetryDelayMillis: Long = 1_000,
  val maximumRetryDelayMillis: Long = 300_000,
  val maximumErrorLength: Int = 4_000,
  /**
   * How long a task waits after the store turned out to be unwritable. Matches the `Retry-After` the
   * request paths advertise for the same condition, so a client polling and a worker retrying agree
   * on how long contention is worth waiting out.
   */
  val storeBusyRetryDelayMillis: Long = 30_000,
) {
  init {
    require(leaseDurationMillis >= 3) { "Task lease duration must be at least 3 ms" }
    require(initialRetryDelayMillis > 0) { "Initial retry delay must be positive" }
    require(maximumRetryDelayMillis >= initialRetryDelayMillis) {
      "Maximum retry delay must not be smaller than its initial value"
    }
    require(maximumErrorLength > 0) { "Maximum task error length must be positive" }
    require(storeBusyRetryDelayMillis > 0) { "Store busy retry delay must be positive" }
  }
}

sealed interface TaskRunResult {
  data object Idle : TaskRunResult

  data class Completed(
    val taskId: String,
  ) : TaskRunResult

  data class Failed(
    val taskId: String,
    val willRetry: Boolean,
  ) : TaskRunResult

  /**
   * The task could not run because the store was unwritable, and is queued again untouched. Distinct
   * from [Failed] with `willRetry`, which has spent one of the task's attempts to learn the same
   * thing.
   */
  data class Deferred(
    val taskId: String,
  ) : TaskRunResult

  data class LeaseLost(
    val taskId: String,
  ) : TaskRunResult
}

fun interface TaskRunner {
  fun runOnce(workerId: String): TaskRunResult
}

class DurableTaskWorker(
  private val queue: DurableTaskQueue,
  handlers: Collection<TaskHandler>,
  private val heartbeat: LeaseHeartbeat,
  private val currentTimeMillis: () -> Long,
  private val leaseTokenFactory: () -> String,
  private val policy: TaskWorkerPolicy = TaskWorkerPolicy(),
  /**
   * Recognises store contention that reaches the worker as a driver failure rather than as a
   * [TaskStoreUnavailableException].
   *
   * A handler that writes through the store directly - a scan staging its candidates, say - reports a
   * busy database as whatever the driver raised, and only the persistence module can read a driver
   * result code. Without this, that failure spent one of the task's attempts: a scan competing with a
   * metadata fan-out for the write lock dead-lettered at 3 of 3 with nothing wrong with it.
   */
  private val isStoreBusy: (Throwable) -> Boolean = { false },
) : TaskRunner {
  private val handlersByType = handlers.associateBy(TaskHandler::taskType)

  init {
    require(handlers.none { it.taskType.isBlank() }) { "Task handler types must not be blank" }
    require(handlersByType.size == handlers.size) { "Task handler types must be unique" }
  }

  override fun runOnce(workerId: String): TaskRunResult {
    require(workerId.isNotBlank()) { "Worker ID must not be blank" }
    val leaseToken = leaseTokenFactory()
    require(leaseToken.isNotBlank()) { "Lease token must not be blank" }
    val claim =
      queue.claimNext(
        workerId = workerId,
        leaseToken = leaseToken,
        nowMillis = now(),
        leaseDurationMillis = policy.leaseDurationMillis,
      ) ?: return TaskRunResult.Idle
    return execute(claim)
  }

  private fun execute(claim: ClaimedTask): TaskRunResult {
    val leaseLost = AtomicBoolean(false)
    val heartbeatRegistration =
      heartbeat.start(policy.leaseDurationMillis / 3) {
        val renewed =
          try {
            queue.renewLease(
              taskId = claim.task.id,
              leaseToken = claim.leaseToken,
              nowMillis = now(),
              leaseDurationMillis = policy.leaseDurationMillis,
            )
          } catch (failure: Throwable) {
            // A busy store means this tick could not ask, not that the lease is gone. Reading it as
            // loss made the worker abandon the task without recording anything, which left the row
            // RUNNING until its lease really did expire - and the recovery path dead-letters an
            // expired row whose attempts are spent. That is how contention alone killed a
            // REFRESH_LIBRARY_METADATA task carrying "the task store was busy" as its last error.
            // There are three renewal ticks per lease, so declining to panic on one costs nothing.
            if (isStoreBusy(failure)) return@start true
            false
          }
        if (!renewed) leaseLost.set(true)
        renewed
      }
    var failure: Throwable? = null
    try {
      val handler =
        handlersByType[claim.task.type]
          ?: throw UnknownTaskTypeException(claim.task.type)
      handler.handle(claim.task)
    } catch (caught: Throwable) {
      failure = caught
    }
    try {
      heartbeatRegistration.close()
    } catch (closeFailure: Throwable) {
      if (failure == null) {
        failure = closeFailure
      } else {
        failure.addSuppressed(closeFailure)
      }
    }

    if (leaseLost.get()) return TaskRunResult.LeaseLost(claim.task.id)
    if (failure == null) {
      return if (queue.complete(claim.task.id, claim.leaseToken)) {
        TaskRunResult.Completed(claim.task.id)
      } else {
        TaskRunResult.LeaseLost(claim.task.id)
      }
    }

    val failureTimeMillis = now()
    if (failure.isStoreUnavailable()) {
      return if (
        queue.release(
          taskId = claim.task.id,
          leaseToken = claim.leaseToken,
          reason = failure.toTaskError(),
          retryAtMillis = failureTimeMillis + policy.storeBusyRetryDelayMillis,
          nowMillis = failureTimeMillis,
        )
      ) {
        TaskRunResult.Deferred(claim.task.id)
      } else {
        TaskRunResult.LeaseLost(claim.task.id)
      }
    }
    val retryAtMillis =
      if (failure is UnknownTaskTypeException || claim.attempt >= claim.task.maxAttempts) {
        null
      } else {
        retryTime(failureTimeMillis, claim.attempt)
      }
    val taskError = failure.toTaskError()
    val failed =
      queue.fail(
        taskId = claim.task.id,
        leaseToken = claim.leaseToken,
        error = taskError,
        retryAtMillis = retryAtMillis,
        nowMillis = failureTimeMillis,
      )
    if (failed && retryAtMillis == null) {
      logDeadLetter(claim, taskError)
    }
    return if (failed) {
      TaskRunResult.Failed(claim.task.id, willRetry = retryAtMillis != null)
    } else {
      TaskRunResult.LeaseLost(claim.task.id)
    }
  }

  /**
   * The only signal an operator gets that [claim]'s task has been permanently abandoned, since a
   * `DEAD` transition otherwise leaves no trace beyond the row itself. [error] is already the
   * same, policy-truncated string persisted as `last_error`; it is capped again here (well below
   * [TaskWorkerPolicy.maximumErrorLength]) purely to keep the log line itself short - task
   * handler messages are typically a file path or a wrapped I/O error, not a secret, but an
   * operator's log aggregator is not the place for an unbounded copy of one regardless. The raw
   * [Throwable] is deliberately not attached: doing so would print its full, untruncated message
   * via the stack trace and defeat that cap.
   */
  private fun logDeadLetter(
    claim: ClaimedTask,
    error: String,
  ) {
    logger.log(
      Level.WARNING,
      "Task ${claim.task.id} (${claim.task.type}) dead-lettered after " +
        "${claim.attempt}/${claim.task.maxAttempts} attempts: ${error.take(DEAD_LETTER_LOG_ERROR_LIMIT)}",
    )
  }

  private fun retryTime(
    nowMillis: Long,
    attempt: Int,
  ): Long {
    var delay = policy.initialRetryDelayMillis
    repeat((attempt - 1).coerceIn(0, 63)) {
      delay =
        if (delay >= policy.maximumRetryDelayMillis / 2) {
          policy.maximumRetryDelayMillis
        } else {
          delay * 2
        }
    }
    return if (nowMillis > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowMillis + delay
  }

  /**
   * Walks the cause chain, because a handler is free to wrap what it caught and the distinction
   * between "this task is broken" and "the store was busy" has to survive that wrapping.
   */
  private fun Throwable.isStoreUnavailable(): Boolean {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
      if (current is TaskStoreUnavailableException || isStoreBusy(current)) return true
      current = current.cause
    }
    return false
  }

  private fun Throwable.toTaskError(): String =
    (message?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Task handler failed")
      .take(policy.maximumErrorLength)

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Worker timestamp must not be negative" } }

  private companion object {
    private val logger = Logger.getLogger(DurableTaskWorker::class.java.name)
    private const val DEAD_LETTER_LOG_ERROR_LIMIT = 500
  }
}
