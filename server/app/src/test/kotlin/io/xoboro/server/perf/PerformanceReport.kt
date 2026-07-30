package io.xoboro.server.perf

import java.util.Locale
import kotlin.math.ceil

/**
 * Aggregated wall-clock latency samples for one measured operation. Reported as p50 and max
 * (not a mean) so noise and outliers stay visible instead of being averaged away.
 */
data class LatencyStats(
  val sampleCount: Int,
  val p50Millis: Double,
  val maxMillis: Double,
) {
  companion object {
    fun of(elapsedNanosSamples: List<Long>): LatencyStats {
      require(elapsedNanosSamples.isNotEmpty()) { "At least one latency sample is required" }
      val sortedMillis = elapsedNanosSamples.map { it / 1_000_000.0 }.sorted()
      val p50Index = (ceil(sortedMillis.size * 0.5).toInt() - 1).coerceIn(0, sortedMillis.size - 1)
      return LatencyStats(
        sampleCount = sortedMillis.size,
        p50Millis = sortedMillis[p50Index],
        maxMillis = sortedMillis.last(),
      )
    }
  }
}

/** One reported measurement: a stable machine-readable key, the item count it covers, and a value. */
private data class PerformanceMetric(
  val key: String,
  val itemCount: Long,
  val value: String,
  val unit: String,
)

/**
 * Collects the metrics the performance harness produces and renders them twice: as stable
 * `key=value` lines for scripts/diffing across runs, and as a markdown table for humans reading
 * the CI log or PR body.
 */
class PerformanceReport {
  private val metrics = mutableListOf<PerformanceMetric>()

  fun recordMillis(
    key: String,
    itemCount: Long,
    millis: Double,
  ) {
    metrics += PerformanceMetric(key, itemCount, String.format(Locale.ROOT, "%.1f", millis), "ms")
  }

  /**
   * Records p50/max latency plus the sample counts. [retriedSamples] is the number of measured
   * requests that needed a retry and were therefore excluded from [stats] (see
   * `PerformanceHarnessTest.measureRepeated`) — always recorded, even when zero, so a reader never
   * has to guess whether retries happened.
   */
  fun recordLatency(
    key: String,
    itemCount: Long,
    stats: LatencyStats,
    retriedSamples: Int,
  ) {
    recordMillis("$key.p50", itemCount, stats.p50Millis)
    recordMillis("$key.max", itemCount, stats.maxMillis)
    metrics += PerformanceMetric("$key.samples", itemCount, stats.sampleCount.toString(), "requests")
    metrics += PerformanceMetric("$key.retried_samples", itemCount, retriedSamples.toString(), "requests")
  }

  fun recordBytes(
    key: String,
    itemCount: Long,
    bytes: Long,
  ) {
    metrics += PerformanceMetric(key, itemCount, bytes.toString(), "bytes")
  }

  /**
   * Records a single, one-time cold-read observation: the full elapsed wall time of the call
   * (including any retries it needed) plus the attempt count it took. Unlike [recordLatency], this
   * is not a steady-state statistic to be averaged — it is one sample of a cost that only happens
   * once, so [attempts] is reported alongside it rather than excluding a retried result from the
   * number. See `PerformanceHarnessTest`'s `api.first_series_read_after_scan` call site.
   */
  fun recordColdRead(
    key: String,
    itemCount: Long,
    millis: Double,
    attempts: Int,
  ) {
    recordMillis(key, itemCount, millis)
    metrics += PerformanceMetric("$key.attempts", itemCount, attempts.toString(), "requests")
  }

  fun toMachineReadableLines(): List<String> =
    metrics.map { "xoboro.perf.${it.key}=${it.value} unit=${it.unit} items=${it.itemCount}" }

  fun toMarkdownTable(): String =
    buildString {
      appendLine("| Metric | Items | Value | Unit |")
      appendLine("|---|---|---|---|")
      metrics.forEach { appendLine("| ${it.key} | ${it.itemCount} | ${it.value} | ${it.unit} |") }
    }
}
