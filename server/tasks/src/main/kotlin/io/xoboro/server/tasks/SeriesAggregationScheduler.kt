package io.xoboro.server.tasks

/**
 * Drains the series aggregation backlog off the request path.
 *
 * The aggregation is denormalized and marked dirty by trigger, so something has to rebuild it. That
 * used to be whichever read arrived next, which made every listing a writer against a database that
 * admits one: a scan dirties every series in a library, and the reader that turned up first paid for
 * the whole backlog — 22s for one page of `GET /series`. Bounding the batch only spread that cost over
 * more readers instead of removing it. Sweeping here means a read pays for the freshness of the page
 * it returns and for nothing else.
 *
 * Follows [ActivityRetentionScheduler] in reusing [FixedRateTaskScheduler] instead of the durable
 * queue: a sweep is idempotent, carries no result anyone waits on, and would otherwise add a queue row
 * per tick. It also must not be serialized behind the scan whose writes create the backlog.
 *
 * [sweepOnce] rebuilds one batch and answers whether more remains; it is injected because the
 * aggregation store lives in the persistence module, which this module does not depend on.
 * [maximumBatchesPerRun] caps a tick so a large backlog is drained across several wake-ups rather than
 * by one long hold of the write lock — the failure this whole class exists to stop.
 */
class SeriesAggregationScheduler(
  private val sweepOnce: () -> Boolean,
  private val scheduler: FixedRateTaskScheduler,
  private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
  private val maximumBatchesPerRun: Int = DEFAULT_MAXIMUM_BATCHES_PER_RUN,
) : AutoCloseable {
  private var registration: ScheduledTaskRegistration? = null

  init {
    require(intervalMillis > 0) { "Aggregation sweep interval must be positive" }
    require(maximumBatchesPerRun > 0) { "Aggregation sweep batch count must be positive" }
  }

  fun start() {
    check(registration == null) { "Aggregation sweeps are already scheduled" }
    registration =
      scheduler.schedule(
        initialDelayMillis = intervalMillis,
        intervalMillis = intervalMillis,
      ) {
        sweep()
      }
  }

  /** Visible so a caller can drain synchronously; [start] is what runs it on a cadence. */
  fun sweep() {
    repeat(maximumBatchesPerRun) {
      if (!sweepOnce()) return
    }
  }

  override fun close() {
    registration?.close()
    registration = null
  }

  companion object {
    /**
     * One minute. The backlog only matters while it is stale, and a series whose aggregation has not
     * been rebuilt reads as empty rather than as an error, so the window this leaves is a card with
     * no authors or tags on it for up to a minute after a scan touched that series.
     */
    const val DEFAULT_INTERVAL_MILLIS: Long = 60 * 1_000

    /**
     * Four batches, so a library of a few thousand series drains within a handful of ticks while no
     * single tick holds the write lock for more than a few batches' worth of rebuilding.
     */
    const val DEFAULT_MAXIMUM_BATCHES_PER_RUN: Int = 4
  }
}
