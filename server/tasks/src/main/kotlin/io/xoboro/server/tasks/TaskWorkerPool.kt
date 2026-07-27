package io.xoboro.server.tasks

import java.util.concurrent.Executors
import java.util.concurrent.Future
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
  private val lock = Any()
  private val running = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  private var desiredWorkerCount = policy.workerCount
  private var workerSequence = 0
  private val workers = linkedMapOf<Int, WorkerHandle>()
  private val executor =
    Executors.newCachedThreadPool { runnable ->
      Thread(runnable).apply { isDaemon = true }
    }

  fun start() {
    synchronized(lock) {
      check(!closed.get()) { "Task worker pool is closed" }
      if (!running.compareAndSet(false, true)) return
      reconcileWorkers()
    }
  }

  fun resize(workerCount: Int) {
    require(workerCount in 1..64) { "Task worker count must be between 1 and 64" }
    synchronized(lock) {
      check(!closed.get()) { "Task worker pool is closed" }
      desiredWorkerCount = workerCount
      if (running.get()) reconcileWorkers()
    }
  }

  fun workerCount(): Int =
    synchronized(lock) { workers.size }

  private fun reconcileWorkers() {
    while (workers.size < desiredWorkerCount) {
      workerSequence += 1
      val sequence = workerSequence
      val enabled = AtomicBoolean(true)
      val future =
        executor.submit {
          val workerId = "worker-$sequence"
          Thread.currentThread().name = "xoboro-$workerId"
          runLoop(workerId, enabled)
        }
      workers[sequence] = WorkerHandle(enabled, future)
    }
    while (workers.size > desiredWorkerCount) {
      val sequence = requireNotNull(workers.keys.maxOrNull())
      requireNotNull(workers.remove(sequence)).retire()
    }
  }

  private fun runLoop(
    workerId: String,
    enabled: AtomicBoolean,
  ) {
    while (running.get() && enabled.get() && !Thread.currentThread().isInterrupted) {
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
    synchronized(lock) {
      if (!closed.compareAndSet(false, true)) return
      running.set(false)
      workers.values.forEach(WorkerHandle::stopNow)
      workers.clear()
    }
    executor.shutdown()
    if (!executor.awaitTermination(policy.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
      executor.shutdownNow()
      executor.awaitTermination(policy.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)
    }
  }

  private data class WorkerHandle(
    val enabled: AtomicBoolean,
    val future: Future<*>,
  ) {
    fun retire() {
      enabled.set(false)
    }

    fun stopNow() {
      enabled.set(false)
      future.cancel(true)
    }
  }
}
