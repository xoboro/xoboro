package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.TaskCounts
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.XoboroDatabase
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DurableTaskWorkerTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `completes a handled task and removes it from the queue`() {
    withQueue("complete") { queue ->
      queue.enqueue(task(), nowMillis = 1)
      var handled = false
      val worker =
        worker(
          queue = queue,
          handler =
            handler {
              handled = true
            },
          times = ArrayDeque(listOf(10L)),
        )

      assertEquals(TaskRunResult.Completed("task-1"), worker.runOnce("worker-1"))
      assertTrue(handled)
      assertEquals(TaskCounts(0, 0, 0), queue.counts())
    }
  }

  @Test
  fun `retries with exponential backoff then dead letters the final failure`() {
    withQueue("retry") { queue ->
      queue.enqueue(task(maxAttempts = 2), nowMillis = 1)
      val worker =
        worker(
          queue = queue,
          handler = handler { error("synthetic failure") },
          times = ArrayDeque(listOf(100L, 100L, 109L, 110L, 110L)),
        )

      assertEquals(
        TaskRunResult.Failed("task-1", willRetry = true),
        worker.runOnce("worker-1"),
      )
      assertEquals(
        TaskRunResult.Idle,
        worker.runOnce("worker-1"),
      )
      assertEquals(
        TaskRunResult.Failed("task-1", willRetry = false),
        worker.runOnce("worker-1"),
      )
      assertEquals(TaskCounts(0, 0, 1), queue.counts())
    }
  }

  @Test
  fun `unknown task types are dead lettered without retry`() {
    withQueue("unknown") { queue ->
      queue.enqueue(task(type = "UNKNOWN"), nowMillis = 1)
      val worker =
        DurableTaskWorker(
          queue = queue,
          handlers = emptyList(),
          heartbeat = noHeartbeat(),
          currentTimeMillis = ArrayDeque(listOf(10L, 10L))::removeFirst,
          leaseTokenFactory = { "lease-1" },
        )

      assertEquals(
        TaskRunResult.Failed("task-1", willRetry = false),
        worker.runOnce("worker-1"),
      )
      assertEquals(TaskCounts(0, 0, 1), queue.counts())
    }
  }

  @Test
  fun `does not mutate completion state after heartbeat reports a lost lease`() {
    withQueue("lost") { queue ->
      queue.enqueue(task(), nowMillis = 1)
      var handled = false
      val heartbeat =
        LeaseHeartbeat { _, renew ->
          assertEquals(false, renew())
          AutoCloseable {}
        }
      val worker =
        worker(
          queue = queue,
          handler = handler { handled = true },
          heartbeat = heartbeat,
          times = ArrayDeque(listOf(100L, 131L)),
        )

      assertEquals(TaskRunResult.LeaseLost("task-1"), worker.runOnce("worker-1"))
      assertTrue(handled)
      assertEquals(TaskCounts(0, 1, 0), queue.counts())
    }
  }

  @Test
  fun `scheduled heartbeat invokes renewal and cancels cleanly`() {
    val invoked = CountDownLatch(1)
    ScheduledLeaseHeartbeat().use { heartbeat ->
      heartbeat.start(intervalMillis = 10) {
        invoked.countDown()
        true
      }.use {
        assertTrue(invoked.await(2, TimeUnit.SECONDS))
      }
    }
  }

  private fun worker(
    queue: JooqDurableTaskQueue,
    handler: TaskHandler,
    times: ArrayDeque<Long>,
    heartbeat: LeaseHeartbeat = noHeartbeat(),
  ): DurableTaskWorker =
    DurableTaskWorker(
      queue = queue,
      handlers = listOf(handler),
      heartbeat = heartbeat,
      currentTimeMillis = times::removeFirst,
      leaseTokenFactory = { "lease-${times.size}" },
      policy =
        TaskWorkerPolicy(
          leaseDurationMillis = 30,
          initialRetryDelayMillis = 10,
          maximumRetryDelayMillis = 40,
        ),
    )

  private fun handler(block: (DurableTask) -> Unit): TaskHandler =
    object : TaskHandler {
      override val taskType: String = "SYNTHETIC"

      override fun handle(task: DurableTask) = block(task)
    }

  private fun noHeartbeat(): LeaseHeartbeat =
    LeaseHeartbeat { _, _ -> AutoCloseable {} }

  private fun task(
    type: String = "SYNTHETIC",
    maxAttempts: Int = 3,
  ): DurableTask =
    DurableTask(
      id = "task-1",
      type = type,
      payloadJson = "{}",
      availableAtMillis = 1,
      maxAttempts = maxAttempts,
    )

  private fun withQueue(
    name: String,
    block: (JooqDurableTaskQueue) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$name.sqlite"))).use { database ->
      block(JooqDurableTaskQueue(database))
    }
  }
}
