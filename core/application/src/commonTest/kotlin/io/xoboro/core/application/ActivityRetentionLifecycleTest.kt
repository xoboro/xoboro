package io.xoboro.core.application

import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.AuthenticationActivity
import io.xoboro.core.domain.AuthenticationActivityPage
import io.xoboro.core.domain.AuthenticationActivityPageRequest
import io.xoboro.core.domain.AuthenticationActivityRepository
import io.xoboro.core.domain.HistoricalEvent
import io.xoboro.core.domain.HistoricalEventPage
import io.xoboro.core.domain.HistoricalEventPageRequest
import io.xoboro.core.domain.HistoricalEventRepository
import io.xoboro.core.domain.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActivityRetentionLifecycleTest {
  @Test
  fun `keeps everything and issues no delete when both windows are zero`() {
    val history = RecordingHistory()
    val activity = RecordingActivity()
    val lifecycle = lifecycle(history, activity) { ActivityRetention() }

    val result = lifecycle.sweep()

    assertEquals(ActivityRetentionResult(), result)
    // Not merely "deleted nothing": issued no statement at all. A delete guaranteed to match nothing
    // still takes a write lock every sweep, and "keep forever" must cost no writes.
    assertEquals(emptyList(), history.cutoffs)
    assertEquals(emptyList(), activity.cutoffs)
  }

  @Test
  fun `deletes strictly older than the window`() {
    val history = RecordingHistory(deleted = 3)
    val activity = RecordingActivity(deleted = 7)
    val lifecycle =
      lifecycle(history, activity, nowMillis = 30L * DAY) {
        ActivityRetention(historyDays = 10, authenticationActivityDays = 2)
      }

    val result = lifecycle.sweep()

    assertEquals(ActivityRetentionResult(historyDeleted = 3, authenticationActivityDeleted = 7), result)
    assertEquals(listOf(20L * DAY), history.cutoffs)
    assertEquals(listOf(28L * DAY), activity.cutoffs)
  }

  @Test
  fun `applies the two windows independently`() {
    val history = RecordingHistory()
    val activity = RecordingActivity()
    // A short security-log window is a common wish; losing a year of catalog history with it is not.
    val lifecycle =
      lifecycle(history, activity, nowMillis = 400L * DAY) {
        ActivityRetention(historyDays = ActivityRetention.KEEP_FOREVER, authenticationActivityDays = 30)
      }

    lifecycle.sweep()

    assertEquals(emptyList(), history.cutoffs)
    assertEquals(listOf(370L * DAY), activity.cutoffs)
  }

  @Test
  fun `issues no delete when the window reaches past the epoch`() {
    val history = RecordingHistory()
    val activity = RecordingActivity()
    // A fresh install with a large window: the cutoff would be negative, and a negative cutoff is a
    // delete that means nothing. Skipped rather than executed.
    val lifecycle =
      lifecycle(history, activity, nowMillis = DAY) {
        ActivityRetention(historyDays = 365, authenticationActivityDays = 365)
      }

    lifecycle.sweep()

    assertEquals(emptyList(), history.cutoffs)
    assertEquals(emptyList(), activity.cutoffs)
  }

  @Test
  fun `issues no delete when the window overflows`() {
    val history = RecordingHistory()
    val activity = RecordingActivity()
    val lifecycle =
      lifecycle(history, activity, nowMillis = 1_000L * DAY) {
        ActivityRetention(historyDays = Long.MAX_VALUE / 1_000, authenticationActivityDays = 1)
      }

    lifecycle.sweep()

    // days * MILLIS_PER_DAY would wrap to a small or negative number, which would delete far more
    // than asked. Detected and skipped.
    assertEquals(emptyList(), history.cutoffs)
    assertEquals(listOf(999L * DAY), activity.cutoffs)
  }

  @Test
  fun `re-reads the policy on every sweep`() {
    val history = RecordingHistory()
    val activity = RecordingActivity()
    var days = ActivityRetention.KEEP_FOREVER
    val lifecycle =
      lifecycle(history, activity, nowMillis = 100L * DAY) {
        ActivityRetention(historyDays = days)
      }

    lifecycle.sweep()
    assertEquals(emptyList(), history.cutoffs)

    // A change through the settings API must take effect at the next sweep, not the next restart.
    days = 10
    lifecycle.sweep()
    assertEquals(listOf(90L * DAY), history.cutoffs)
  }

  @Test
  fun `rejects a negative window`() {
    assertFailsWith<IllegalArgumentException> { ActivityRetention(historyDays = -1) }
    assertFailsWith<IllegalArgumentException> {
      ActivityRetention(authenticationActivityDays = -1)
    }
  }

  private fun lifecycle(
    history: RecordingHistory,
    activity: RecordingActivity,
    nowMillis: Long = 100L * DAY,
    retention: () -> ActivityRetention,
  ): ActivityRetentionLifecycle =
    ActivityRetentionLifecycle(
      history = history,
      authenticationActivity =
        AuthenticationActivityLifecycle(
          activities = activity,
          currentTimeMillis = { nowMillis },
        ),
      retention = retention,
      currentTimeMillis = { nowMillis },
    )

  private class RecordingHistory(
    private val deleted: Int = 0,
  ) : HistoricalEventRepository {
    val cutoffs = mutableListOf<Long>()

    override fun findAll(request: HistoricalEventPageRequest): HistoricalEventPage =
      HistoricalEventPage(emptyList(), 0, request)

    override fun insert(event: HistoricalEvent) = Unit

    override fun deleteOlderThan(timestampMillis: Long): Int {
      cutoffs += timestampMillis
      return deleted
    }
  }

  private class RecordingActivity(
    private val deleted: Int = 0,
  ) : AuthenticationActivityRepository {
    val cutoffs = mutableListOf<Long>()

    override fun findAll(request: AuthenticationActivityPageRequest): AuthenticationActivityPage =
      AuthenticationActivityPage(emptyList(), 0, request)

    override fun findAllByUser(
      user: User,
      request: AuthenticationActivityPageRequest,
    ): AuthenticationActivityPage = AuthenticationActivityPage(emptyList(), 0, request)

    override fun findMostRecentByUser(
      user: User,
      apiKeyId: ApiKeyId?,
    ): AuthenticationActivity? = null

    override fun insert(activity: AuthenticationActivity) = Unit

    override fun deleteOlderThan(dateTimeMillis: Long): Int {
      cutoffs += dateTimeMillis
      return deleted
    }
  }

  private companion object {
    const val DAY = 86_400_000L
  }
}
