package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.application.TaskStoreUnavailableException
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.XoboroDatabase
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
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
  fun `logs a warning naming the task, attempt count, and error when dead lettered`() {
    withQueue("dead-letter-log") { queue ->
      queue.enqueue(task(maxAttempts = 1), nowMillis = 1L)
      val worker =
        worker(
          queue = queue,
          handler = handler { error("synthetic dead letter failure") },
          times = ArrayDeque(listOf(10L, 11L)),
        )

      val records = collectLogRecords(DurableTaskWorker::class.java.name) {
        assertEquals(
          TaskRunResult.Failed("task-1", willRetry = false),
          worker.runOnce("worker-1"),
        )
      }

      val record = records.single { it.level == Level.WARNING }
      assertTrue(record.message.contains("task-1"))
      assertTrue(record.message.contains("SYNTHETIC"))
      assertTrue(record.message.contains("1/1"))
      assertTrue(record.message.contains("synthetic dead letter failure"))
    }
  }

  @Test
  fun `does not log a warning for a retry that will run again`() {
    withQueue("retry-no-log") { queue ->
      queue.enqueue(task(maxAttempts = 2), nowMillis = 1L)
      val worker =
        worker(
          queue = queue,
          handler = handler { error("synthetic retryable failure") },
          times = ArrayDeque(listOf(10L, 11L)),
        )

      val records = collectLogRecords(DurableTaskWorker::class.java.name) {
        assertEquals(
          TaskRunResult.Failed("task-1", willRetry = true),
          worker.runOnce("worker-1"),
        )
      }

      assertTrue(records.none { it.level == Level.WARNING })
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

  @Test
  fun `a busy store defers the task instead of spending one of its attempts`() {
    withQueue("store-busy") { queue ->
      // One attempt, so anything charged as a failure dead-letters. Contention alone did exactly
      // that to a REFRESH_LIBRARY_METADATA fan-out at 10/10 attempts, and its library's metadata
      // was never filled in.
      queue.enqueue(task(maxAttempts = 1), nowMillis = 1)
      val worker =
        worker(
          queue = queue,
          handler = handler { throw TaskStoreUnavailableException("task-2") },
          times = ArrayDeque(listOf(100L, 100L)),
        )

      assertEquals(TaskRunResult.Deferred("task-1"), worker.runOnce("worker-1"))
      assertEquals(TaskCounts(pending = 1, running = 0, dead = 0), queue.counts())
    }
  }

  @Test
  fun `a busy store reported through a wrapping failure still defers`() {
    withQueue("store-busy-wrapped") { queue ->
      queue.enqueue(task(maxAttempts = 1), nowMillis = 1)
      val worker =
        worker(
          queue = queue,
          handler =
            handler {
              throw IllegalStateException("fan-out failed", TaskStoreUnavailableException("task-2"))
            },
          times = ArrayDeque(listOf(100L, 100L)),
        )

      assertEquals(TaskRunResult.Deferred("task-1"), worker.runOnce("worker-1"))
      assertEquals(TaskCounts(pending = 1, running = 0, dead = 0), queue.counts())
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
