package io.xoboro.core.application

data class OperationalStatusSnapshot(
  val ready: Boolean,
  val uptimeSeconds: Double,
  val activeRequests: Long,
  val totalRequests: Long,
  val requestsByStatusClass: Map<String, Long>,
  val taskQueue: TaskCounts,
  val taskWorkerCount: Int,
) {
  init {
    require(uptimeSeconds >= 0) { "Uptime must not be negative" }
    require(activeRequests >= 0) { "Active request count must not be negative" }
    require(totalRequests >= 0) { "Total request count must not be negative" }
    require(taskWorkerCount >= 0) { "Task worker count must not be negative" }
  }
}

/** Bounded, in-process operational status for the native administration surface. */
fun interface OperationalMetricsSnapshotProvider {
  fun snapshot(): OperationalStatusSnapshot
}
