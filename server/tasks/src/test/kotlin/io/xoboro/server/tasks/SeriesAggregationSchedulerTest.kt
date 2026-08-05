package io.xoboro.server.tasks

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
          sweeps < 2
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
          true
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
          false
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
      SeriesAggregationScheduler(sweepOnce = { false }, scheduler = manual)

    scheduler.start()
    assertFailsWith<IllegalStateException> { scheduler.start() }

    scheduler.close()
    assertTrue(manual.closed)
    scheduler.start()
  }

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
