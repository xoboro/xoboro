package io.xoboro.server.tasks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskWorkerPoolTest {
  @Test
  fun `starts configured workers and stops idempotently`() {
    val observedWorkers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val started = CountDownLatch(2)
    val runner =
      TaskRunner { workerId ->
        if (observedWorkers.add(workerId)) started.countDown()
        TaskRunResult.Idle
      }
    val pool =
      TaskWorkerPool(
        runner,
        TaskWorkerPoolPolicy(
          workerCount = 2,
          idlePollMillis = 10,
          failurePollMillis = 10,
          shutdownTimeoutMillis = 1_000,
        ),
      )

    pool.start()
    pool.start()
    assertTrue(started.await(2, TimeUnit.SECONDS))
    pool.close()
    pool.close()

    assertEquals(setOf("worker-1", "worker-2"), observedWorkers)
  }

  @Test
  fun `backs off after idle polls and runner failures`() {
    val calls = AtomicInteger()
    val failures = AtomicInteger()
    val enoughCalls = CountDownLatch(1)
    val pool =
      TaskWorkerPool(
        runner =
          TaskRunner {
            when (calls.incrementAndGet()) {
              1 -> error("synthetic runner failure")
              3 -> enoughCalls.countDown()
            }
            TaskRunResult.Idle
          },
        policy =
          TaskWorkerPoolPolicy(
            idlePollMillis = 20,
            failurePollMillis = 20,
            shutdownTimeoutMillis = 1_000,
          ),
        onFailure = { failures.incrementAndGet() },
      )

    pool.start()
    assertTrue(enoughCalls.await(2, TimeUnit.SECONDS))
    pool.close()

    assertEquals(1, failures.get())
    assertTrue(calls.get() < 20)
  }

  @Test
  fun `resizes a running pool and assigns stable unique worker ids`() {
    val observedWorkers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val threeWorkers = CountDownLatch(3)
    val runner =
      TaskRunner { workerId ->
        if (observedWorkers.add(workerId)) threeWorkers.countDown()
        TaskRunResult.Idle
      }
    val pool =
      TaskWorkerPool(
        runner,
        TaskWorkerPoolPolicy(
          workerCount = 1,
          idlePollMillis = 10,
          failurePollMillis = 10,
          shutdownTimeoutMillis = 1_000,
        ),
      )

    pool.start()
    pool.resize(3)
    assertTrue(threeWorkers.await(2, TimeUnit.SECONDS))
    assertEquals(3, pool.workerCount())
    pool.resize(1)
    assertEquals(1, pool.workerCount())
    pool.close()

    assertEquals(setOf("worker-1", "worker-2", "worker-3"), observedWorkers)
  }

  @Test
  fun `applies resize requested before startup`() {
    val started = CountDownLatch(2)
    val observedWorkers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val pool =
      TaskWorkerPool(
        TaskRunner { workerId ->
          if (observedWorkers.add(workerId)) started.countDown()
          TaskRunResult.Idle
        },
        TaskWorkerPoolPolicy(idlePollMillis = 10),
      )

    pool.resize(2)
    assertEquals(0, pool.workerCount())
    pool.start()
    assertTrue(started.await(2, TimeUnit.SECONDS))
    assertEquals(2, pool.workerCount())
    pool.close()
  }

  @Test
  fun `retiring a worker never interrupts its active task`() {
    val bothStarted = CountDownLatch(2)
    val release = CountDownLatch(1)
    val interrupted = AtomicInteger()
    val completed = CountDownLatch(2)
    val pool =
      TaskWorkerPool(
        TaskRunner {
          bothStarted.countDown()
          try {
            release.await(2, TimeUnit.SECONDS)
          } catch (_: InterruptedException) {
            interrupted.incrementAndGet()
          } finally {
            completed.countDown()
          }
          TaskRunResult.Idle
        },
        TaskWorkerPoolPolicy(
          workerCount = 2,
          idlePollMillis = 10,
          failurePollMillis = 10,
          shutdownTimeoutMillis = 1_000,
        ),
      )

    pool.start()
    assertTrue(bothStarted.await(2, TimeUnit.SECONDS))
    pool.resize(1)
    release.countDown()
    assertTrue(completed.await(2, TimeUnit.SECONDS))
    assertEquals(0, interrupted.get())
    pool.close()
  }

  @Test
  fun `validates pool limits`() {
    kotlin.test.assertFailsWith<IllegalArgumentException> {
      TaskWorkerPoolPolicy(workerCount = 0)
    }
    kotlin.test.assertFailsWith<IllegalArgumentException> {
      TaskWorkerPoolPolicy(workerCount = 65)
    }
    kotlin.test.assertFailsWith<IllegalArgumentException> {
      TaskWorkerPoolPolicy(idlePollMillis = 0)
    }
  }
}
