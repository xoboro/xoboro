package io.xoboro.server.tasks

import io.xoboro.core.application.ActivityRetentionLifecycle
import io.xoboro.core.application.ActivityRetentionResult

/**
 * Runs the activity retention sweep periodically.
 *
 * Reuses [FixedRateTaskScheduler] rather than introducing a second scheduling mechanism, and does not
 * go through the durable task queue. A retention sweep is idempotent and cheap, and it carries no
 * result anyone waits on — durability would buy nothing while adding a row per sweep to the very kind
 * of table this exists to keep small.
 *
 * The sweep runs on a fixed interval regardless of the configured windows, and
 * [ActivityRetentionLifecycle] re-reads the policy each time. A "keep forever" window costs no writes
 * at all, so an unconfigured deployment pays only a wake-up.
 *
 * An [initialDelayMillis] of one interval, rather than zero, is deliberate: sweeping during startup
 * would compete for the write lock with the scan and analysis work that a restart kicks off, and
 * nothing about retention is urgent enough to justify that.
 */
class ActivityRetentionScheduler(
  private val retention: ActivityRetentionLifecycle,
  private val scheduler: FixedRateTaskScheduler,
  private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
  private val onSwept: (ActivityRetentionResult) -> Unit = {},
) : AutoCloseable {
  private var registration: ScheduledTaskRegistration? = null

  init {
    require(intervalMillis > 0) { "Retention sweep interval must be positive" }
  }

  fun start() {
    check(registration == null) { "Retention sweeps are already scheduled" }
    registration =
      scheduler.schedule(
        initialDelayMillis = intervalMillis,
        intervalMillis = intervalMillis,
      ) {
        retention.sweep().takeIf { it.total > 0 }?.let(onSwept)
      }
  }

  override fun close() {
    registration?.close()
    registration = null
  }

  companion object {
    /**
     * Six hours. Retention windows are whole days, so any interval well under a day makes the sweep
     * cadence invisible in the outcome; six hours keeps a deployment that shortens its window from
     * waiting most of a day to see it take effect.
     */
    const val DEFAULT_INTERVAL_MILLIS: Long = 6 * 60 * 60 * 1_000
  }
}
