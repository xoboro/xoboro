package io.xoboro.server.tasks

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

fun interface LeaseHeartbeat {
  fun start(
    intervalMillis: Long,
    renew: () -> Boolean,
  ): AutoCloseable
}

class ScheduledLeaseHeartbeat(
  private val executor: ScheduledExecutorService = newHeartbeatExecutor(),
) : LeaseHeartbeat,
  AutoCloseable {
  override fun start(
    intervalMillis: Long,
    renew: () -> Boolean,
  ): AutoCloseable {
    require(intervalMillis > 0) { "Heartbeat interval must be positive" }
    val future =
      executor.scheduleAtFixedRate(
        { renew() },
        intervalMillis,
        intervalMillis,
        TimeUnit.MILLISECONDS,
      )
    return AutoCloseable { future.cancel(false) }
  }

  override fun close() {
    executor.shutdownNow()
  }

  companion object {
    private fun newHeartbeatExecutor(): ScheduledThreadPoolExecutor =
      ScheduledThreadPoolExecutor(
        1,
      ) { runnable ->
        Thread(runnable, "xoboro-task-heartbeat").apply { isDaemon = true }
      }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
      }
  }
}
