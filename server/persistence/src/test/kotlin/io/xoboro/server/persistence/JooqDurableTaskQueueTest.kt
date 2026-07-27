package io.xoboro.server.persistence

import io.xoboro.core.application.ClaimedTask
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.application.TaskPriority
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
  fun `concurrent workers cannot claim the same task`() {
    val path = tempDirectory.resolve("concurrent.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { firstDatabase ->
      XoboroDatabase.open(DatabaseConfig(path)).use { secondDatabase ->
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

  private fun withQueue(
    name: String,
    block: (JooqDurableTaskQueue, XoboroDatabase) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$name.sqlite"))).use { database ->
      block(JooqDurableTaskQueue(database), database)
    }
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
