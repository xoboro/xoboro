package io.xoboro.compatibility.komga.api

import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest

/**
 * Covers the rule the two quarantined `SseRoutesTest` stream assertions could not observe: an open
 * stream re-resolves its subscriber on every wake, at most once per recheck period, and cuts delivery
 * off when the subscriber's authorization changed or the account is gone.
 *
 * These run against the extracted gate rather than through `testApplication`, which is the whole
 * point — the gate has no coroutine and no transport, so there is no server-side SSE coroutine left
 * alive to be reported as `UncompletedCoroutinesError` instead of an assertion.
 */
class KomgaSseAuthorizationGateTest {
  @Test
  fun `admits events without a lookup until the recheck period elapses`() =
    runTest {
      val time = TestTimeSource()
      var lookups = 0
      val gate = gateFor(time) { lookups++; CONNECTED }

      // The gate is woken far more often than it is due; a stream busy with catalog events looks
      // exactly like this.
      repeat(50) { assertTrue(gate.admitsAnotherEvent()) }
      assertEquals(0, lookups, "an undue gate must not query the snapshot")

      time += RECHECK
      assertTrue(gate.admitsAnotherEvent())
      assertEquals(1, lookups)
    }

  @Test
  fun `queries the snapshot once per period regardless of how often it is woken`() =
    runTest {
      val time = TestTimeSource()
      var lookups = 0
      val gate = gateFor(time) { lookups++; CONNECTED }

      repeat(4) {
        time += RECHECK
        // Many wakes inside one period; only the first may spend a query.
        repeat(20) { assertTrue(gate.admitsAnotherEvent()) }
      }

      assertEquals(4, lookups)
    }

  @Test
  fun `stops delivering once the subscriber's authorization changes`() =
    runTest {
      val narrowed =
        CONNECTED.copy(sharedLibraryIds = setOf(LibraryId("library-1")), sharesAllLibraries = false)
      assertDeniedAfterRecheck { narrowed }
      assertDeniedAfterRecheck { CONNECTED.copy(roles = setOf(UserRole.FILE_DOWNLOAD)) }
      assertDeniedAfterRecheck { CONNECTED.copy(email = "moved@example.invalid") }
      assertDeniedAfterRecheck {
        CONNECTED.copy(restrictions = ContentRestrictions(labelsExclude = setOf("synthetic")))
      }
    }

  @Test
  fun `stops delivering once the subscriber's account is deleted`() =
    runTest {
      assertDeniedAfterRecheck { null }
    }

  @Test
  fun `keeps delivering across a password rehash`() =
    runTest {
      val time = TestTimeSource()
      // An ordinary successful login rewrites these two fields via the adaptive hasher. Closing the
      // stream on that would disconnect a subscriber merely for signing in elsewhere.
      val rehashed = CONNECTED.copy(passwordHash = "synthetic-rehash", updatedAtMillis = 99)
      val gate = gateFor(time) { rehashed }

      time += RECHECK
      assertTrue(gate.admitsAnotherEvent())
      time += RECHECK
      assertTrue(gate.admitsAnotherEvent())
    }

  @Test
  fun `stays denied on every later wake`() =
    runTest {
      val time = TestTimeSource()
      var lookups = 0
      val gate = gateFor(time) { lookups++; null }

      time += RECHECK
      assertFalse(gate.admitsAnotherEvent())

      // The route closes the stream on the first denial, so this asserts the gate does not report a
      // revoked subscriber as admissible again if it is asked — a denial is not a transient state.
      time += RECHECK
      assertFalse(gate.admitsAnotherEvent())
      assertEquals(2, lookups)
    }

  private suspend fun assertDeniedAfterRecheck(current: () -> User?) {
    val time = TestTimeSource()
    val gate = gateFor(time, current)

    assertTrue(gate.admitsAnotherEvent(), "must admit before the first recheck is due")
    time += RECHECK
    assertFalse(gate.admitsAnotherEvent())
  }

  private fun gateFor(
    time: TestTimeSource,
    current: () -> User?,
  ): KomgaSseAuthorizationGate =
    KomgaSseAuthorizationGate(
      connected = CONNECTED,
      recheckPeriod = RECHECK,
      timeSource = time,
      currentOrNull = { current() },
    )

  private companion object {
    val RECHECK = 10.seconds
    val CONNECTED =
      User(
        id = UserId("user-1"),
        email = "reader@example.invalid",
        passwordHash = "synthetic-hash",
        roles = setOf(UserRole.PAGE_STREAMING),
        createdAtMillis = 1,
      )
  }
}
