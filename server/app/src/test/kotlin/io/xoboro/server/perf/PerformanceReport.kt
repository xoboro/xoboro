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

  fun recordLatency(
    key: String,
    itemCount: Long,
    stats: LatencyStats,
  ) {
    recordMillis("$key.p50", itemCount, stats.p50Millis)
    recordMillis("$key.max", itemCount, stats.maxMillis)
    metrics += PerformanceMetric("$key.samples", itemCount, stats.sampleCount.toString(), "requests")
  }

  fun recordBytes(
    key: String,
    itemCount: Long,
    bytes: Long,
  ) {
    metrics += PerformanceMetric(key, itemCount, bytes.toString(), "bytes")
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
