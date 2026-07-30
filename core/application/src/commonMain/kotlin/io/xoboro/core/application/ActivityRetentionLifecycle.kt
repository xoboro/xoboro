package io.xoboro.core.application

import io.xoboro.core.domain.HistoricalEventRepository

/**
 * How long recorded activity is kept.
 *
 * Zero means "keep forever", which is the default for both windows. Retention deletes audit rows, and
 * a default that quietly discarded them would be the wrong way round: an operator who has not decided
 * on a policy has not asked for their history to be pruned. Turning it on is a deliberate act.
 *
 * Windows are in days because that is the unit an operator reasons in, and because a day is coarse
 * enough that the sweep interval — hours — can never be the thing that decides whether a row survives.
 */
data class ActivityRetention(
  val historyDays: Long = KEEP_FOREVER,
  val authenticationActivityDays: Long = KEEP_FOREVER,
) {
  init {
    require(historyDays >= 0) { "History retention must not be negative" }
    require(authenticationActivityDays >= 0) {
      "Authentication activity retention must not be negative"
    }
  }

  companion object {
    const val KEEP_FOREVER: Long = 0
  }
}

/** What a single retention sweep removed. */
data class ActivityRetentionResult(
  val historyDeleted: Int = 0,
  val authenticationActivityDeleted: Int = 0,
) {
  val total: Int get() = historyDeleted + authenticationActivityDeleted
}

/**
 * Deletes activity older than the configured windows.
 *
 * The two windows are independent on purpose. Authentication activity and catalog history answer
 * different questions — "who tried to get in" versus "what happened to the library" — and an operator
 * who wants a short security-log window rarely wants to lose a year of catalog history with it.
 *
 * [retention] is read on every sweep rather than captured at construction, so a change through the
 * settings API takes effect at the next sweep instead of at the next restart.
 */
class ActivityRetentionLifecycle(
  private val history: HistoricalEventRepository,
  private val authenticationActivity: AuthenticationActivityLifecycle,
  private val retention: () -> ActivityRetention,
  private val currentTimeMillis: () -> Long,
) {
  fun sweep(): ActivityRetentionResult {
    val policy = retention()
    val now = currentTimeMillis()
    require(now >= 0) { "Retention timestamp must not be negative" }
    return ActivityRetentionResult(
      historyDeleted =
        cutoff(now, policy.historyDays)?.let(history::deleteOlderThan) ?: 0,
      authenticationActivityDeleted =
        cutoff(now, policy.authenticationActivityDays)
          ?.let(authenticationActivity::deleteOlderThan)
          ?: 0,
    )
  }

  /**
   * The instant before which rows are removed, or null when the window keeps everything.
   *
   * Returns null rather than a very old cutoff for [ActivityRetention.KEEP_FOREVER]: issuing a delete
   * that is guaranteed to match nothing still takes a write lock on every sweep, and "keep forever"
   * should cost no writes at all.
   *
   * A window longer than the time since the epoch clamps to null for the same reason — on a fresh
   * install with a large window the cutoff would otherwise be negative, and a negative cutoff is a
   * delete that means nothing.
   */
  private fun cutoff(
    nowMillis: Long,
    days: Long,
  ): Long? {
    if (days <= ActivityRetention.KEEP_FOREVER) return null
    val window = days * MILLIS_PER_DAY
    if (window / MILLIS_PER_DAY != days) return null
    return (nowMillis - window).takeIf { it > 0 }
  }

  private companion object {
    const val MILLIS_PER_DAY = 86_400_000L
  }
}
