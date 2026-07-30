package io.xoboro.server.perf

import java.util.Locale
import kotlin.math.ceil

/**
 * Aggregated wall-clock latency samples for one measured operation. Reported as p50 (median),
 * min, and max (not a mean) so noise and outliers stay visible instead of being averaged away.
 */
data class LatencyStats(
  val sampleCount: Int,
  val p50Millis: Double,
  val minMillis: Double,
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
        minMillis = sortedMillis.first(),
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
   * Records p50 (median)/min/max latency plus the sample counts. [retriedSamples] is the number of
   * measured requests that needed a retry and were therefore excluded from [stats] (see
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
    recordMillis("$key.min", itemCount, stats.minMillis)
    recordMillis("$key.max", itemCount, stats.maxMillis)
    metrics += PerformanceMetric("$key.samples", itemCount, stats.sampleCount.toString(), "requests")
    metrics += PerformanceMetric("$key.retried_samples", itemCount, retriedSamples.toString(), "requests")
  }

  /**
   * Records p50 (median)/min/max from repeated measurements of a locally repeatable operation —
   * e.g. re-running a rescan against an already-warm runtime, as opposed to [recordLatency]'s HTTP
   * calls. There is no retry concept here (nothing is failing and being retried), so unlike
   * [recordLatency] this only reports the sample count, not a retried-sample count.
   */
  fun recordRepeatedMillis(
    key: String,
    itemCount: Long,
    stats: LatencyStats,
  ) {
    recordMillis("$key.p50", itemCount, stats.p50Millis)
    recordMillis("$key.min", itemCount, stats.minMillis)
    recordMillis("$key.max", itemCount, stats.maxMillis)
    metrics += PerformanceMetric("$key.samples", itemCount, stats.sampleCount.toString(), "runs")
  }

  fun recordBytes(
    key: String,
    itemCount: Long,
    bytes: Long,
  ) {
    metrics += PerformanceMetric(key, itemCount, bytes.toString(), "bytes")
  }

  /**
   * Records a single, one-time cold-read observation as three separate numbers, not one, because
   * they answer different questions:
   * - [successfulAttemptMillis]: the isolated duration of the one call that actually succeeded —
   *   the real, server-side cost, unpolluted by any prior failed attempt or backoff sleep.
   * - [harnessWallMillis]: the full wall-clock time of the whole retry loop, including every
   *   failed attempt and every backoff sleep. This is harness time, not server time, and is
   *   labeled `.harness_wall` so it is never mistaken for a server latency — a total dominated by
   *   jittered backoff sleep across several failures is mostly measuring the harness's own retry
   *   loop, and reporting it as *the* number would repeat the exact "polluted max" mistake this
   *   harness was built to avoid.
   * - [attempts] and [lastFailureType] (null when the first attempt succeeded) describe how many
   *   tries it took and what the last failure looked like, so a reader can tell "the call itself is
   *   slow" apart from "the call is fast but got blocked repeatedly" — different problems with
   *   different fixes.
   *
   * Unlike [recordLatency], none of this is a steady-state statistic to be averaged — it is one
   * sample of a cost that only happens once. See `PerformanceHarnessTest`'s
   * `api.first_series_read_after_scan` call site.
   */
  fun recordColdRead(
    key: String,
    itemCount: Long,
    successfulAttemptMillis: Double,
    harnessWallMillis: Double,
    attempts: Int,
    lastFailureType: String?,
  ) {
    recordMillis("$key.successful_attempt", itemCount, successfulAttemptMillis)
    recordMillis("$key.harness_wall", itemCount, harnessWallMillis)
    metrics += PerformanceMetric("$key.attempts", itemCount, attempts.toString(), "requests")
    metrics += PerformanceMetric("$key.failed_attempts", itemCount, (attempts - 1).toString(), "requests")
    metrics += PerformanceMetric("$key.last_failure_type", itemCount, lastFailureType ?: "none", "label")
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
