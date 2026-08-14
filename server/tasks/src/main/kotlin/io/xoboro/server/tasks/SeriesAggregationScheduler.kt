package io.xoboro.server.tasks

import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.application.CatalogMutationEventPublisher
import io.xoboro.core.application.SeriesAggregationSweep
import io.xoboro.core.domain.SeriesId

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
  private val sweepOnce: () -> SeriesAggregationSweep,
  private val scheduler: FixedRateTaskScheduler,
  private val publisher: CatalogMutationEventPublisher = CatalogMutationEventPublisher {},
  private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
  private val maximumBatchesPerRun: Int = DEFAULT_MAXIMUM_BATCHES_PER_RUN,
  private val maximumAnnouncementsPerRun: Int = DEFAULT_MAXIMUM_ANNOUNCEMENTS_PER_RUN,
) : AutoCloseable {
  private var registration: ScheduledTaskRegistration? = null

  init {
    require(intervalMillis > 0) { "Aggregation sweep interval must be positive" }
    require(maximumBatchesPerRun > 0) { "Aggregation sweep batch count must be positive" }
    require(maximumAnnouncementsPerRun > 0) {
      "Aggregation announcement cap must be positive"
    }
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

  /**
   * Visible so a caller can drain synchronously; [start] is what runs it on a cadence.
   *
   * The whole tick is announced at the end rather than each batch as it lands, and that is what
   * makes the announcement honest about what happened: a series dirtied twice inside one tick is
   * one change to whoever is looking at it, and announcing per batch would say it twice.
   */
  fun sweep() {
    val rebuilt = LinkedHashMap<SeriesId, CatalogMutationEvent.Series>()
    // A loop rather than `repeat`, because draining early has to leave the loop and still announce
    // what it drained: `return@repeat` only ends the iteration, and a non-local return would skip
    // the announcement entirely - the two ways of writing this that quietly do nothing.
    for (batch in 0 until maximumBatchesPerRun) {
      val swept = sweepOnce()
      swept.rebuilt.forEach { event -> rebuilt.putIfAbsent(event.seriesId, event) }
      if (!swept.moreRemaining) break
    }
    announce(rebuilt.values)
  }

  /**
   * Tells subscribers which series now read differently, up to the cap.
   *
   * The cap is not a nicety. A batch is 500 rows and four run per tick, so a cold scan can rebuild
   * two thousand series a minute; one event each would push far past the native hub's buffer and
   * every subscriber would be handed `stream.resync-required` anyway — after the server had paid
   * to build, authorize and encode all of them. Past the cap the transport's own overflow is the
   * cheaper way to say "a lot changed", and it is the way clients already handle.
   *
   * Under the cap — a metadata edit, a rescan touching a handful of series — each subscriber is
   * told exactly which series to re-read, which is what a screen showing one series needs in order
   * to ignore the rest.
   */
  private fun announce(rebuilt: Collection<CatalogMutationEvent.Series>) {
    if (rebuilt.isEmpty()) return
    if (rebuilt.size > maximumAnnouncementsPerRun) return
    rebuilt.forEach(publisher::publish)
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

    /**
     * The most series one tick will name individually.
     *
     * Two hundred, which is below the native event hub's 1024-frame buffer with room for whatever
     * else is happening, so an ordinary tick never costs a subscriber their stream position. Above
     * it the tick is a scan draining, and a scan already announces its own series as it goes — the
     * hub's overflow, which clients handle as a resync, says "a lot changed" for less than two
     * thousand individually authorized and encoded frames would.
     */
    const val DEFAULT_MAXIMUM_ANNOUNCEMENTS_PER_RUN: Int = 200
  }
}
