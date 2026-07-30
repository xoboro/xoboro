package io.xoboro.server.persistence

import io.xoboro.core.application.ClaimedTask
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.application.TaskPriority
import java.nio.file.Path
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
      assertTrue(queue.enqueue(taskFixture(payload = """{"version":1}"""), nowMillis = 1L))
      assertTrue(
        queue.enqueue(
          taskFixture(payload = """{"version":2}""", priority = TaskPriority.HIGHEST),
          nowMillis = 2L,
        ),
      )
      val claim = queue.claim("worker", "lease-1", nowMillis = 10L)
      assertEquals("""{"version":2}""", claim?.task?.payloadJson)
      assertEquals(TaskPriority.HIGHEST, claim?.task?.priority)

      assertFalse(
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
      assertTrue(queue.enqueue(taskFixture(maxAttempts = 1), nowMillis = 1L))
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
      assertTrue(
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
      assertTrue(queue.enqueue(taskFixture(maxAttempts = 1), nowMillis = 1L))
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
        assertTrue(
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
}
