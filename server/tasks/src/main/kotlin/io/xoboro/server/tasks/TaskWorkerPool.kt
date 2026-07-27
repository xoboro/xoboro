package io.xoboro.server.tasks

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class TaskWorkerPoolPolicy(
  val workerCount: Int = 1,
  val idlePollMillis: Long = 500,
  val failurePollMillis: Long = 1_000,
  val shutdownTimeoutMillis: Long = 30_000,
) {
  init {
    require(workerCount in 1..64) { "Task worker count must be between 1 and 64" }
    require(idlePollMillis > 0) { "Idle poll duration must be positive" }
    require(failurePollMillis > 0) { "Failure poll duration must be positive" }
    require(shutdownTimeoutMillis > 0) { "Shutdown timeout must be positive" }
  }
}

class TaskWorkerPool(
  private val runner: TaskRunner,
  private val policy: TaskWorkerPoolPolicy = TaskWorkerPoolPolicy(),
  private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
  private val running = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  private val executor =
    Executors.newFixedThreadPool(policy.workerCount) { runnable ->
      Thread(runnable).apply { isDaemon = true }
    }

  fun start() {
    check(!closed.get()) { "Task worker pool is closed" }
    if (!running.compareAndSet(false, true)) return
    repeat(policy.workerCount) { index ->
      executor.submit {
        val workerId = "worker-${index + 1}"
        Thread.currentThread().name = "xoboro-$workerId"
        runLoop(workerId)
      }
    }
  }

  private fun runLoop(workerId: String) {
    while (running.get() && !Thread.currentThread().isInterrupted) {
      val delayMillis =
        try {
          when (runner.runOnce(workerId)) {
            TaskRunResult.Idle -> policy.idlePollMillis
            else -> 0
          }
        } catch (failure: Throwable) {
          runCatching { onFailure(failure) }
          policy.failurePollMillis
        }
      if (delayMillis > 0 && !sleep(delayMillis)) return
    }
  }

  private fun sleep(delayMillis: Long): Boolean =
    try {
      Thread.sleep(delayMillis)
      true
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    running.set(false)
    executor.shutdown()
    if (!executor.awaitTermination(policy.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
      executor.shutdownNow()
      executor.awaitTermination(policy.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)
    }
  }
}
