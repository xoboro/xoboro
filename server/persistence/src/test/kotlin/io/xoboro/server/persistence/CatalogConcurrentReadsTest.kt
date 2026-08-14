package io.xoboro.server.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogConcurrentReadsTest {
  @Test
  fun `both reads are in flight at the same time`() {
    val backgroundStarted = CountDownLatch(1)
    val foregroundStarted = CountDownLatch(1)

    // Each read waits for the other to start. Run sequentially, the first would wait forever.
    val (background, foreground) =
      CatalogConcurrentReads().both(
        background = {
          backgroundStarted.countDown()
          assertTrue(foregroundStarted.await(10, TimeUnit.SECONDS), "the page never started")
          "count"
        },
        foreground = {
          foregroundStarted.countDown()
          assertTrue(backgroundStarted.await(10, TimeUnit.SECONDS), "the count never started")
          "page"
        },
      )

    assertEquals("count" to "page", background to foreground)
  }

  @Test
  fun `a failing count reaches the caller as itself, not as a wrapper`() {
    val failure =
      assertFailsWith<IllegalStateException> {
        CatalogConcurrentReads().both(
          background = { throw IllegalStateException("count failed") },
          foreground = { "page" },
        )
      }

    assertEquals("count failed", failure.message)
  }

  @Test
  fun `a failing page reaches the caller as itself`() {
    val failure =
      assertFailsWith<IllegalStateException> {
        CatalogConcurrentReads().both(
          background = { "count" },
          foreground = { throw IllegalStateException("page failed") },
        )
      }

    assertEquals("page failed", failure.message)
  }

  /**
   * A saturated pool must not park the caller behind a queue while it holds a database connection.
   * Both reads still happen; they just happen one after the other, which is what they did before.
   */
  @Test
  fun `with no worker free the count runs on the calling thread`() {
    val callingThread = Thread.currentThread()
    val ranOnCallingThread = AtomicBoolean(false)
    val saturated = Executor { runnable -> runnable.run() }

    val (background, foreground) =
      CatalogConcurrentReads(saturated).both(
        background = {
          ranOnCallingThread.set(Thread.currentThread() === callingThread)
          "count"
        },
        foreground = { "page" },
      )

    assertTrue(ranOnCallingThread.get(), "the count did not fall back to the calling thread")
    assertEquals("count" to "page", background to foreground)
  }
}
