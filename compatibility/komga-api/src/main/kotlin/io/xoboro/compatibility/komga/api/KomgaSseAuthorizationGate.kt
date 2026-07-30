package io.xoboro.compatibility.komga.api

import io.xoboro.core.domain.User
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Decides whether an open SSE stream may deliver one more event to the subscriber it was opened for.
 *
 * The rule is that a subscriber whose authorization has been revoked, narrowed, or whose account has
 * been deleted learns nothing further from a stream that is already open. Two properties make that
 * hold, and both live here rather than inline in the route loop:
 *
 * - **Checked on every wake, not only on the task-poll timeout.** A stream busy with catalog events
 *   never times out, so a check confined to the timeout branch would keep a revoked subscriber live
 *   for as long as events keep arriving. This gate has no notion of *why* it was woken, so the route
 *   cannot reintroduce that distinction by accident.
 * - **Rate-limited to one lookup per [recheckPeriod] per subscriber.** The snapshot is a database
 *   read; without the deadline an event-heavy stream would issue one query per event.
 *
 * This was extracted so those properties could be asserted deterministically. Covering them through
 * `testApplication` was tried and quarantined: every shape attempted left the server-side SSE
 * coroutine alive under CI load, which `runTest` reports as `UncompletedCoroutinesError` rather than
 * as the assertion the test meant to make. The decision logic has no coroutine and no transport, so
 * tested here it simply has no such failure mode. What remains verified by inspection rather than by
 * an HTTP test is the route's single call site — see ADR 0091.
 */
internal class KomgaSseAuthorizationGate(
  private val connected: User,
  private val recheckPeriod: Duration,
  timeSource: TimeSource = TimeSource.Monotonic,
  private val currentOrNull: suspend () -> User?,
) {
  init {
    require(recheckPeriod.isPositive()) { "SSE authorization recheck period must be positive" }
  }

  private val timeSource = timeSource
  private var nextCheck = timeSource.markNow() + recheckPeriod

  /**
   * Returns false once the subscriber must be cut off. Call this on every wake of the stream loop,
   * before deciding what to send.
   *
   * The deadline is re-armed from the moment the check completed rather than from the moment it came
   * due, so a slow lookup cannot make the next check immediately overdue and turn the rate limit
   * into a per-event query.
   */
  suspend fun admitsAnotherEvent(): Boolean {
    if (!nextCheck.hasPassedNow()) return true
    val current = currentOrNull() ?: return false
    if (current.invalidatesKomgaSessionFrom(connected)) return false
    nextCheck = timeSource.markNow() + recheckPeriod
    return true
  }
}
