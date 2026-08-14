package io.xoboro.server.tasks

import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.application.CatalogMutationKind
import io.xoboro.core.application.SeriesAggregationSweep
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeriesAggregationSchedulerTest {
  @Test
  fun `stops a tick once the backlog is drained`() {
    var sweeps = 0
    val scheduler =
      SeriesAggregationScheduler(
        sweepOnce = {
          sweeps += 1
          sweep(moreRemaining = sweeps < 2)
        },
        scheduler = ManualScheduler(),
        maximumBatchesPerRun = 4,
      )

    scheduler.sweep()

    assertEquals(2, sweeps, "an empty backlog must not be swept again")
  }

  @Test
  fun `caps a tick so a large backlog is drained across wake-ups`() {
    var sweeps = 0
    val scheduler =
      SeriesAggregationScheduler(
        sweepOnce = {
          sweeps += 1
          sweep(moreRemaining = true)
        },
        scheduler = ManualScheduler(),
        maximumBatchesPerRun = 3,
      )

    scheduler.sweep()

    // The write lock is held for the duration of a batch, so an uncapped drain is the very stall
    // this scheduler exists to move off the request path in the first place.
    assertEquals(3, sweeps)
  }

  @Test
  fun `sweeps on the configured cadence rather than at startup`() {
    val manual = ManualScheduler()
    var sweeps = 0
    val scheduler =
      SeriesAggregationScheduler(
        sweepOnce = {
          sweeps += 1
          sweep()
        },
        scheduler = manual,
        intervalMillis = 1_000,
      )

    scheduler.start()

    assertEquals(0, sweeps, "scheduling must not sweep before the first interval elapses")
    assertEquals(1_000, manual.initialDelayMillis)
    assertEquals(1_000, manual.intervalMillis)

    manual.runTask()

    assertEquals(1, sweeps)
  }

  @Test
  fun `refuses to schedule twice and releases its registration on close`() {
    val manual = ManualScheduler()
    val scheduler =
      SeriesAggregationScheduler(sweepOnce = { sweep() }, scheduler = manual)

    scheduler.start()
    assertFailsWith<IllegalStateException> { scheduler.start() }

    scheduler.close()
    assertTrue(manual.closed)
    scheduler.start()
  }

  /**
   * The rebuild is where a series gains its authors and tags, and nothing said so.
   *
   * Measured against a running server: the screen showed a work with no author while the route it
   * had asked would by then answer with two, because the sweep landed after the page loaded and
   * announced nothing at all.
   */
  @Test
  fun `announces each series a tick rebuilt`() {
    val published = mutableListOf<CatalogMutationEvent>()
    val scheduler =
      SeriesAggregationScheduler(
        sweepOnce = { sweep(series = listOf("a", "b")) },
        scheduler = ManualScheduler(),
        publisher = { published += it },
        maximumBatchesPerRun = 1,
      )

    scheduler.sweep()

    assertEquals(
      listOf(SeriesId("a"), SeriesId("b")),
      published.filterIsInstance<CatalogMutationEvent.Series>().map { it.seriesId },
    )
    assertTrue(
      published.filterIsInstance<CatalogMutationEvent.Series>().all {
        it.kind == CatalogMutationKind.UPDATED
      },
    )
  }

  /**
   * One change to whoever is looking at it.
   *
   * A series can be dirtied by several of its books and land in two batches of the same tick.
   * Announcing per batch would tell a reader twice that the same series changed, and each telling
   * costs every subscriber a re-read.
   */
  @Test
  fun `announces a series rebuilt twice in one tick only once`() {
    val published = mutableListOf<CatalogMutationEvent>()
    var sweeps = 0
    val scheduler =
      SeriesAggregationScheduler(
        sweepOnce = {
          sweeps += 1
          sweep(series = listOf("a"), moreRemaining = sweeps < 2)
        },
        scheduler = ManualScheduler(),
        publisher = { published += it },
        maximumBatchesPerRun = 4,
      )

    scheduler.sweep()

    assertEquals(2, sweeps)
    assertEquals(1, published.size)
  }

  /**
   * Past the cap, silence is cheaper than the flood.
   *
   * A batch is 500 rows and four run per tick, so a cold scan can rebuild two thousand series a
   * minute. One event each would push past the native hub's buffer and hand every subscriber
   * `stream.resync-required` anyway — after the server had paid to build, authorize and encode all
   * of them. The transport's own overflow is the cheaper way to say "a lot changed".
   */
  @Test
  fun `announces nothing when a tick rebuilt more than the cap`() {
    val published = mutableListOf<CatalogMutationEvent>()
    val scheduler =
      SeriesAggregationScheduler(
        sweepOnce = { sweep(series = listOf("a", "b", "c")) },
        scheduler = ManualScheduler(),
        publisher = { published += it },
        maximumBatchesPerRun = 1,
        maximumAnnouncementsPerRun = 2,
      )

    scheduler.sweep()

    assertEquals(emptyList(), published)
  }

  /** A tick that rebuilt nothing changed nothing, and must not wake a single subscriber. */
  @Test
  fun `announces nothing when a tick rebuilt nothing`() {
    val published = mutableListOf<CatalogMutationEvent>()
    val scheduler =
      SeriesAggregationScheduler(
        sweepOnce = { sweep() },
        scheduler = ManualScheduler(),
        publisher = { published += it },
      )

    scheduler.sweep()

    assertEquals(emptyList(), published)
  }

  private fun sweep(
    series: List<String> = emptyList(),
    moreRemaining: Boolean = false,
  ): SeriesAggregationSweep =
    SeriesAggregationSweep(
      rebuilt =
        series.map {
          CatalogMutationEvent.Series(
            kind = CatalogMutationKind.UPDATED,
            seriesId = SeriesId(it),
            libraryId = LibraryId("library-$it"),
          )
        },
      moreRemaining = moreRemaining,
    )

  private class ManualScheduler : FixedRateTaskScheduler {
    var initialDelayMillis: Long? = null
    var intervalMillis: Long? = null
    var closed = false
    private var task: (() -> Unit)? = null

    override fun schedule(
      initialDelayMillis: Long,
      intervalMillis: Long,
      task: () -> Unit,
    ): ScheduledTaskRegistration {
      this.initialDelayMillis = initialDelayMillis
      this.intervalMillis = intervalMillis
      this.task = task
      return ScheduledTaskRegistration { closed = true }
    }

    fun runTask() = requireNotNull(task) { "nothing was scheduled" }.invoke()

    override fun close() = Unit
  }
}
