package io.xoboro.server.persistence

import io.xoboro.core.application.ClaimedTask
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.application.TaskEnqueue
import io.xoboro.core.application.TaskPriority
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqDurableTaskQueueTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `claims available tasks by priority then FIFO order`() {
    withQueue("priority") { queue, _ ->
      queue.enqueue(taskFixture(id = "low", priority = TaskPriority.LOW), nowMillis = 1L)
      queue.enqueue(taskFixture(id = "high-first", priority = TaskPriority.HIGH), nowMillis = 2L)
      queue.enqueue(taskFixture(id = "high-second", priority = TaskPriority.HIGH), nowMillis = 3L)

      val first = queue.claim("worker", "lease-1", nowMillis = 10L)
      assertEquals("high-first", first?.task?.id)
      assertTrue(queue.complete("high-first", "lease-1"))
      val second = queue.claim("worker", "lease-2", nowMillis = 10L)
      assertEquals("high-second", second?.task?.id)
      assertTrue(queue.complete("high-second", "lease-2"))
      val third = queue.claim("worker", "lease-3", nowMillis = 10L)
      assertEquals("low", third?.task?.id)
    }
  }

  @Test
  fun `counts queued and running tasks by compatibility type`() {
    withQueue("counts-by-type") { queue, _ ->
      queue.enqueue(taskFixture(id = "scan-1"), nowMillis = 1L)
      queue.enqueue(
        taskFixture(id = "scan-2", payload = """{"kind":"second"}"""),
        nowMillis = 2L,
      )
      queue.enqueue(
        taskFixture(id = "analyze-1").copy(type = "ANALYZE_BOOK"),
        nowMillis = 3L,
      )
      queue.claim("worker", "lease", nowMillis = 10L)

      assertEquals(
        mapOf("ANALYZE_BOOK" to 1, "SYNTHETIC" to 2),
        queue.countsByType(),
      )
    }
  }

  @Test
  fun `excludes dead tasks from counts by type`() {
    withQueue("counts-by-type-dead") { queue, _ ->
      queue.enqueue(taskFixture(id = "dead-1", maxAttempts = 1), nowMillis = 1L)
      queue.enqueue(taskFixture(id = "pending-1"), nowMillis = 2L)
      queue.enqueue(
        taskFixture(id = "analyze-1").copy(type = "ANALYZE_BOOK"),
        nowMillis = 3L,
      )
      // "dead-1" was enqueued first, so it is the one claimed and dead-lettered below.
      queue.claim("worker", "lease", nowMillis = 10L)
      assertTrue(
        queue.fail(
          taskId = "dead-1",
          leaseToken = "lease",
          error = "synthetic failure",
          retryAtMillis = null,
          nowMillis = 11L,
        ),
      )
      assertEquals(TaskCounts(pending = 2L, running = 0L, dead = 1L), queue.counts())

      // A dead task is permanently abandoned, not queued or running work, so it must not inflate
      // a count that operators read as "this is about to run".
      assertEquals(
        mapOf("ANALYZE_BOOK" to 1, "SYNTHETIC" to 1),
        queue.countsByType(),
      )
    }
  }

  @Test
  fun `allows only one running task per non-null group`() {
    withQueue("groups") { queue, _ ->
      queue.enqueue(taskFixture(id = "group-first", groupId = "series-1"), nowMillis = 1L)
      queue.enqueue(
        taskFixture(id = "group-second", groupId = "series-1", priority = TaskPriority.HIGH),
        nowMillis = 2L,
      )
      queue.enqueue(taskFixture(id = "independent"), nowMillis = 3L)

      val grouped = queue.claim("worker-1", "lease-1", nowMillis = 10L)
      assertEquals("group-second", grouped?.task?.id)
      val independent = queue.claim("worker-2", "lease-2", nowMillis = 10L)
      assertEquals("independent", independent?.task?.id)
      assertNull(queue.claim("worker-3", "lease-3", nowMillis = 10L))

      assertTrue(queue.complete("group-second", "lease-1"))
      assertEquals(
        "group-first",
        queue.claim("worker-3", "lease-4", nowMillis = 10L)?.task?.id,
      )
    }
  }

  @Test
  fun `deduplicates pending and running task IDs without mutating a running lease`() {
    withQueue("deduplicate") { queue, _ ->
      assertQueued(queue.enqueue(taskFixture(payload = """{"version":1}"""), nowMillis = 1L))
      assertQueued(
        queue.enqueue(
          taskFixture(payload = """{"version":2}""", priority = TaskPriority.HIGHEST),
          nowMillis = 2L,
        ),
      )
      val claim = queue.claim("worker", "lease-1", nowMillis = 10L)
      assertEquals("""{"version":2}""", claim?.task?.payloadJson)
      assertEquals(TaskPriority.HIGHEST, claim?.task?.priority)

      assertLeftAlone(
        queue.enqueue(
          taskFixture(payload = """{"version":3}"""),
          nowMillis = 11L,
        ),
      )
      assertTrue(queue.complete("task-1", "lease-1"))
    }
  }

  @Test
  fun `reclaims an expired lease and dead-letters an exhausted task`() {
    withQueue("lease-recovery") { queue, _ ->
      val baseTime = 1_700_000_000_000L
      queue.enqueue(
        taskFixture(maxAttempts = 2, availableAtMillis = baseTime),
        nowMillis = baseTime,
      )

      val first =
        queue.claim(
          "worker-1",
          "lease-1",
          nowMillis = baseTime,
          leaseDuration = 10_000L,
        )
      assertEquals(1, first?.attempt)
      assertEquals(baseTime, first?.task?.availableAtMillis)
      assertEquals(baseTime + 10_000L, first?.leaseExpiresAtMillis)
      val second =
        queue.claim(
          "worker-2",
          "lease-2",
          nowMillis = baseTime + 10_000L,
          leaseDuration = 10_000L,
        )
      assertEquals(2, second?.attempt)
      assertEquals("worker-2", second?.leaseOwner)
      assertEquals(baseTime + 20_000L, second?.leaseExpiresAtMillis)
      assertNull(
        queue.claim(
          "worker-3",
          "lease-3",
          nowMillis = baseTime + 20_000L,
          leaseDuration = 10_000L,
        ),
      )
      assertEquals(TaskCounts(pending = 0, running = 0, dead = 1), queue.counts())
    }
  }

  @Test
  fun `logs a warning when lease-expiry recovery dead-letters a task`() {
    withQueue("lease-recovery-log") { queue, _ ->
      val baseTime = 1_700_000_000_000L
      queue.enqueue(
        taskFixture(maxAttempts = 1, availableAtMillis = baseTime),
        nowMillis = baseTime,
      )
      queue.claim("worker-1", "lease-1", nowMillis = baseTime, leaseDuration = 10_000L)

      // A crashed worker never calls fail(), so this recovery step - run here by an unrelated
      // worker polling for its own next task - is the only place this task's death is decided.
      val records =
        collectLogRecords(JooqDurableTaskQueue::class.java.name) {
          assertNull(
            queue.claim(
              "worker-2",
              "lease-2",
              nowMillis = baseTime + 10_000L,
              leaseDuration = 10_000L,
            ),
          )
        }

      val record = records.single { it.level == Level.WARNING }
      assertTrue(record.message.contains("task-1"))
      assertTrue(record.message.contains("SYNTHETIC"))
      assertTrue(record.message.contains("1/1"))
      assertTrue(record.message.contains("Lease expired"))
      assertEquals(TaskCounts(pending = 0, running = 0, dead = 1), queue.counts())
    }
  }

  @Test
  fun `retries failures after backoff and rejects stale lease tokens`() {
    withQueue("retry") { queue, _ ->
      queue.enqueue(taskFixture(maxAttempts = 2), nowMillis = 1L)
      queue.claim("worker", "lease-1", nowMillis = 10L)

      assertFalse(
        queue.fail(
          taskId = "task-1",
          leaseToken = "stale-token",
          error = "synthetic failure",
          retryAtMillis = 50L,
          nowMillis = 20L,
        ),
      )
      assertTrue(
        queue.fail(
          taskId = "task-1",
          leaseToken = "lease-1",
          error = "synthetic failure",
          retryAtMillis = 50L,
          nowMillis = 20L,
        ),
      )
      assertNull(queue.claim("worker", "lease-2", nowMillis = 49L))
      assertNotNull(queue.claim("worker", "lease-2", nowMillis = 50L))
      assertTrue(
        queue.fail(
          taskId = "task-1",
          leaseToken = "lease-2",
          error = "final synthetic failure",
          retryAtMillis = 60L,
          nowMillis = 55L,
        ),
      )
      assertEquals(TaskCounts(pending = 0, running = 0, dead = 1), queue.counts())
    }
  }

  @Test
  fun `renews and completes only a currently owned lease`() {
    withQueue("renew") { queue, _ ->
      queue.enqueue(taskFixture(), nowMillis = 1L)
      queue.claim("worker", "lease-1", nowMillis = 10L, leaseDuration = 10L)

      assertFalse(queue.renewLease("task-1", "wrong", nowMillis = 15L, leaseDurationMillis = 20L))
      assertTrue(queue.renewLease("task-1", "lease-1", nowMillis = 15L, leaseDurationMillis = 20L))
      assertFalse(queue.complete("task-1", "wrong"))
      assertTrue(queue.complete("task-1", "lease-1"))
      assertEquals(TaskCounts(pending = 0, running = 0, dead = 0), queue.counts())
    }
  }

  @Test
  fun `clears pending and dead tasks without cancelling active leases`() {
    withQueue("clear-unclaimed") { queue, _ ->
      queue.enqueue(
        taskFixture(id = "running", priority = TaskPriority.HIGH),
        nowMillis = 1L,
      )
      queue.enqueue(taskFixture(id = "pending"), nowMillis = 2L)
      queue.enqueue(
        taskFixture(id = "dead", priority = TaskPriority.HIGHEST, maxAttempts = 1),
        nowMillis = 3L,
      )
      queue.claim("worker", "dead-lease", nowMillis = 10L)
      assertTrue(
        queue.fail(
          taskId = "dead",
          leaseToken = "dead-lease",
          error = "synthetic failure",
          retryAtMillis = null,
          nowMillis = 11L,
        ),
      )
      queue.claim("worker", "running-lease", nowMillis = 12L)

      assertEquals(2, queue.clearUnclaimed())
      assertEquals(TaskCounts(pending = 0, running = 1, dead = 0), queue.counts())
      assertTrue(queue.complete("running", "running-lease"))
    }
  }

  @Test
  fun `clears only pending tasks and leaves dead and running work intact`() {
    withQueue("clear-pending") { queue, _ ->
      queue.enqueue(taskFixture(id = "pending"), nowMillis = 1L)
      queue.enqueue(taskFixture(id = "running", priority = TaskPriority.HIGH), nowMillis = 2L)
      queue.enqueue(
        taskFixture(id = "dead", priority = TaskPriority.HIGHEST, maxAttempts = 1),
        nowMillis = 3L,
      )
      queue.claim("worker", "dead-lease", nowMillis = 10L)
      assertTrue(
        queue.fail(
          taskId = "dead",
          leaseToken = "dead-lease",
          error = "synthetic failure",
          retryAtMillis = null,
          nowMillis = 11L,
        ),
      )
      queue.claim("worker", "running-lease", nowMillis = 12L)

      assertEquals(1, queue.clearPending())
      // The two halves that matter: the pending row is gone, and the dead row (still worth
      // inspecting) and the in-flight running row are both untouched by an endpoint literally
      // named "unclaimed".
      assertEquals(TaskCounts(pending = 0, running = 1, dead = 1), queue.counts())
      assertTrue(queue.complete("running", "running-lease"))
    }
  }

  @Test
  fun `clears only dead tasks and leaves pending and running work intact`() {
    withQueue("clear-dead") { queue, _ ->
      queue.enqueue(taskFixture(id = "pending"), nowMillis = 1L)
      queue.enqueue(taskFixture(id = "running", priority = TaskPriority.HIGH), nowMillis = 2L)
      queue.enqueue(
        taskFixture(id = "dead", priority = TaskPriority.HIGHEST, maxAttempts = 1),
        nowMillis = 3L,
      )
      queue.claim("worker", "dead-lease", nowMillis = 10L)
      assertTrue(
        queue.fail(
          taskId = "dead",
          leaseToken = "dead-lease",
          error = "synthetic failure",
          retryAtMillis = null,
          nowMillis = 11L,
        ),
      )
      queue.claim("worker", "running-lease", nowMillis = 12L)

      assertEquals(1, queue.clearDead())
      // The two halves that matter: the dead row is gone, and the still-queued "pending" row and
      // the in-flight "running" row are both untouched by a call meant only to clear the corpse.
      assertEquals(TaskCounts(pending = 1, running = 1, dead = 0), queue.counts())
      assertTrue(queue.complete("running", "running-lease"))
    }
  }

  @Test
  fun `concurrent workers cannot claim the same task`() {
    val path = tempDirectory.resolve("concurrent.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { firstDatabase ->
      XoboroDatabase.open(DatabaseConfig(path, acquireProcessLock = false)).use {
          secondDatabase ->
        val firstQueue = JooqDurableTaskQueue(firstDatabase)
        val secondQueue = JooqDurableTaskQueue(secondDatabase)
        firstQueue.enqueue(taskFixture(), nowMillis = 1L)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
          val first =
            executor.submit<ClaimedTask?> {
              start.await()
              firstQueue.claim("worker-1", "lease-1", nowMillis = 10L)
            }
          val second =
            executor.submit<ClaimedTask?> {
              start.await()
              secondQueue.claim("worker-2", "lease-2", nowMillis = 10L)
            }
          start.countDown()

          val claims =
            listOf(
              first.get(10, TimeUnit.SECONDS),
              second.get(10, TimeUnit.SECONDS),
            ).filterNotNull()
          assertEquals(1, claims.size)
          assertEquals("task-1", claims.single().task.id)
        } finally {
          executor.shutdownNow()
        }
      }
    }
  }

  @Test
  fun `revives a dead task on re-enqueue without resetting its attempts`() {
    withQueue("dead-revival") { queue, _ ->
      assertQueued(queue.enqueue(taskFixture(maxAttempts = 1), nowMillis = 1L))
      assertEquals(1, queue.claim("worker-1", "lease-1", nowMillis = 10L)?.attempt)
      assertTrue(
        queue.fail(
          taskId = "task-1",
          leaseToken = "lease-1",
          error = "synthetic failure",
          retryAtMillis = null,
          nowMillis = 20L,
        ),
      )
      assertEquals(TaskCounts(pending = 0L, running = 0L, dead = 1L), queue.counts())

      // Task ids are deterministic, so before the revival this enqueue was silently discarded
      // and the id could never be queued again.
      assertQueued(
        queue.enqueue(taskFixture(maxAttempts = 1, availableAtMillis = 30L), nowMillis = 30L),
      )
      assertEquals(TaskCounts(pending = 1L, running = 0L, dead = 0L), queue.counts())

      // Attempts are preserved rather than reset, so this is the second attempt. Resetting the
      // counter would report 1 here and hand a task that keeps dying a fresh budget every time.
      assertEquals(2, queue.claim("worker-2", "lease-2", nowMillis = 31L)?.attempt)
    }
  }

  @Test
  fun `charges a repeatedly dying task one attempt per re-enqueue`() {
    withQueue("dead-poison-pill") { queue, _ ->
      assertQueued(queue.enqueue(taskFixture(maxAttempts = 1), nowMillis = 1L))
      var availableAt = 1L
      // A task that fails deterministically must cost one claim per external re-enqueue, and its
      // attempt count must keep climbing so "this has died repeatedly" stays visible.
      (1..3).forEach { expectedAttempt ->
        val claim = queue.claim("worker", "lease-$expectedAttempt", nowMillis = availableAt + 1L)
        assertEquals(expectedAttempt, claim?.attempt)
        assertTrue(
          queue.fail(
            taskId = "task-1",
            leaseToken = "lease-$expectedAttempt",
            error = "synthetic failure $expectedAttempt",
            retryAtMillis = null,
            nowMillis = availableAt + 2L,
          ),
        )
        assertEquals(TaskCounts(pending = 0L, running = 0L, dead = 1L), queue.counts())
        availableAt += 10L
        assertQueued(
          queue.enqueue(
            taskFixture(maxAttempts = 1, availableAtMillis = availableAt),
            nowMillis = availableAt,
          ),
        )
      }
      // Exactly one claim became available per re-enqueue: the fourth claim is the revival above,
      // and nothing further is waiting behind it.
      assertNotNull(queue.claim("worker", "lease-4", nowMillis = availableAt + 1L))
      assertNull(queue.claim("worker", "lease-5", nowMillis = availableAt + 2L))
    }
  }

  @Test
  fun `polling an idle queue does not contend for the write lock`() {
    withQueue("idle-poll") { queue, database ->
      val writeLockHeld = CountDownLatch(1)
      val releaseWriteLock = CountDownLatch(1)
      val executor = Executors.newSingleThreadExecutor()
      try {
        val holder =
          executor.submit {
            database.transaction { transaction ->
              // Any write claims the WAL write lock for the rest of this transaction.
              transaction.execute(
                "INSERT INTO server_setting (setting_key, setting_value) VALUES (?, ?)",
                "idle-poll-write-lock",
                "held",
              )
              writeLockHeld.countDown()
              releaseWriteLock.await(LOCK_HANDOFF_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
          }
        assertTrue(writeLockHeld.await(LOCK_HANDOFF_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        // Nothing to recover and nothing to claim, so this poll needs no write at all. Before the
        // read probe it opened a write transaction regardless, blocked here until the busy timeout
        // expired, and then failed with SQLITE_BUSY.
        assertNull(queue.claim("worker", "lease", nowMillis = 10L))

        releaseWriteLock.countDown()
        holder.get(LOCK_HANDOFF_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      } finally {
        releaseWriteLock.countDown()
        executor.shutdownNow()
      }
    }
  }

  @Test
  fun `reports a locked store as unavailable rather than as a running collision`() {
    // Found against a real library of 145,105 archives: a scan held the write lock while
    // POST /api/v1/libraries/{id}/metadata/refresh fanned out one enqueue per book, and the
    // escaping exception failed the request mid-way with a 500 and a partially queued library.
    //
    // UNAVAILABLE has to be distinct from ALREADY_RUNNING, because a caller reads them in opposite
    // directions: a live lease means the work is in flight and can be forgotten about, while a busy
    // store means nothing was written and the caller still owes it. Collapsing both into `false` is
    // what let a fan-out count a dropped task as a skip.
    //
    // The lock is taken for real from a second connection rather than simulated, because what is
    // under test is the driver's result code surviving jOOQ's wrapping.
    val path = tempDirectory.resolve("locked-enqueue.sqlite")
    XoboroDatabase.open(DatabaseConfig(path, busyTimeoutMillis = 50)).use { database ->
      val queue = JooqDurableTaskQueue(database)

      DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { holder ->
        holder.autoCommit = false
        holder.createStatement().use { it.executeUpdate("UPDATE task SET updated_at_ms = updated_at_ms") }
        try {
          assertEquals(TaskEnqueue.UNAVAILABLE, queue.enqueue(taskFixture(), nowMillis = 1L))
        } finally {
          holder.rollback()
        }
      }

      // Nothing was queued while the lock was held, and the same task queues once it is gone - so
      // UNAVAILABLE really was "not yet" rather than "already there".
      assertEquals(TaskCounts(pending = 0L, running = 0L, dead = 0L), queue.counts())
      assertQueued(queue.enqueue(taskFixture(), nowMillis = 2L))
      assertEquals(TaskCounts(pending = 1L, running = 0L, dead = 0L), queue.counts())
    }
  }

  @Test
  fun `releasing a task hands its attempt back and keeps it claimable`() {
    withQueue("release") { queue, database ->
      assertQueued(queue.enqueue(taskFixture(maxAttempts = 1), 1L))
      val claim = requireNotNull(queue.claim("worker-1", "lease-1", 1L))
      assertEquals(1, claim.attempt)

      assertTrue(queue.release("task-1", "lease-1", "store busy", 40L, 10L))

      // maxAttempts is 1, so a released task is only still claimable if the attempt was given back:
      // `fail` in the same position would have dead-lettered it.
      assertEquals(0, database.attemptCountOf("task-1"))
      assertEquals("PENDING", database.stateOf("task-1"))
      assertNull(queue.claim("worker-1", "lease-2", 39L), "the retry delay must be respected")
      assertEquals(1, requireNotNull(queue.claim("worker-1", "lease-3", 40L)).attempt)
    }
  }

  @Test
  fun `releasing a task revived past its ceiling brings it back under the ceiling`() {
    withQueue("release-revived") { queue, database ->
      // A task revived from DEAD keeps a count already at or past its ceiling, by design: a task that
      // keeps dying costs one attempt per re-enqueue rather than getting a fresh budget. Releasing it
      // has to undo more than one attempt then, or a release that claims the attempt never happened
      // leaves the row one recovery sweep from being dead-lettered for having spent them all - which
      // is how contention alone killed a REFRESH_LIBRARY_METADATA task at 12 attempts of 10.
      assertQueued(queue.enqueue(taskFixture(maxAttempts = 2), 1L))
      database.dsl.execute("UPDATE task SET attempt_count = 5 WHERE id = ?", "task-1")
      val claim = requireNotNull(queue.claim("worker-1", "lease-1", 1L))
      assertEquals(6, claim.attempt)

      assertTrue(queue.release("task-1", "lease-1", "store busy", 40L, 10L))

      assertEquals(1, database.attemptCountOf("task-1"), "must land below max_attempts")
      assertEquals(2, requireNotNull(queue.claim("worker-1", "lease-2", 40L)).attempt)
    }
  }

  @Test
  fun `releasing a task whose lease is gone reports the loss`() {
    withQueue("release-lost-lease") { queue, _ ->
      assertQueued(queue.enqueue(taskFixture(), 1L))
      queue.claim("worker-1", "lease-1", 1L)

      assertFalse(queue.release("task-1", "someone-elses-lease", "store busy", 40L, 10L))
    }
  }

  private fun XoboroDatabase.attemptCountOf(taskId: String): Int? =
    dsl
      .fetchOne("SELECT attempt_count FROM task WHERE id = ?", taskId)
      ?.get("attempt_count", Int::class.java)

  private fun XoboroDatabase.stateOf(taskId: String): String? =
    dsl.fetchOne("SELECT state FROM task WHERE id = ?", taskId)?.get("state", String::class.java)

  private fun withQueue(
    name: String,
    block: (JooqDurableTaskQueue, XoboroDatabase) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$name.sqlite"))).use { database ->
      block(JooqDurableTaskQueue(database), database)
    }
  }

  /** Attaches a temporary [Handler] to the named logger for the duration of [block]. */
  private fun collectLogRecords(
    loggerName: String,
    block: () -> Unit,
  ): List<LogRecord> {
    val records = mutableListOf<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
      }
    val logger = Logger.getLogger(loggerName)
    logger.addHandler(handler)
    try {
      block()
    } finally {
      logger.removeHandler(handler)
    }
    return records
  }

  private fun JooqDurableTaskQueue.claim(
    workerId: String,
    leaseToken: String,
    nowMillis: Long,
    leaseDuration: Long = 100L,
  ): ClaimedTask? =
    claimNext(
      workerId = workerId,
      leaseToken = leaseToken,
      nowMillis = nowMillis,
      leaseDurationMillis = leaseDuration,
    )

  private fun taskFixture(
    id: String = "task-1",
    payload: String = "{}",
    priority: Int = TaskPriority.DEFAULT,
    groupId: String? = null,
    maxAttempts: Int = 3,
    availableAtMillis: Long = 1L,
  ): DurableTask =
    DurableTask(
      id = id,
      type = "SYNTHETIC",
      payloadJson = payload,
      priority = priority,
      groupId = groupId,
      availableAtMillis = availableAtMillis,
      maxAttempts = maxAttempts,
    )

  private companion object {
    /**
     * Upper bound on handing the write lock between threads. Generous on purpose: it only has to
     * exceed real handoff latency, and it must stay above the 10s SQLite busy timeout so a
     * regression surfaces as this assertion failing rather than as a timeout race.
     */
    const val LOCK_HANDOFF_TIMEOUT_SECONDS = 30L
  }
}

/**
 * The enqueue outcomes this suite asserts on, named so the paren structure of the original
 * `assertTrue`/`assertFalse` calls survives the move to a three-state result.
 */
private fun assertQueued(outcome: TaskEnqueue) {
  assertEquals(TaskEnqueue.QUEUED, outcome)
}

/** A collision with a live lease. Distinct from a busy store, which is [TaskEnqueue.UNAVAILABLE]. */
private fun assertLeftAlone(outcome: TaskEnqueue) {
  assertEquals(TaskEnqueue.ALREADY_RUNNING, outcome)
}
