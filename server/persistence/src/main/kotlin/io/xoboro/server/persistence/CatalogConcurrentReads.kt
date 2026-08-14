package io.xoboro.server.persistence

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Runs a listing's two reads - how many rows match, and which rows this page holds - at the same
 * time rather than one after the other.
 *
 * A page and its total are independent questions asked of the same snapshot-free connection pool,
 * and SQLite in WAL mode serves concurrent readers without blocking them. Sequentially they cost
 * their sum; together they cost the slower of the two. It is worth doing only where the count is
 * still expensive after [CatalogSqlFrom] has narrowed it - a filter that reaches into series
 * metadata keeps that join and costs 177ms against the live catalog, next to a 143ms page.
 *
 * The two reads see independently-taken snapshots, so a write landing between them can leave the
 * total one out of step with the page. That was equally true when they ran in sequence: they were
 * always two statements, never one transaction.
 */
internal class CatalogConcurrentReads(
  private val executor: Executor = SHARED_EXECUTOR,
) {
  /**
   * [background] and [foreground] both run, and their results come back together.
   *
   * When no worker is free, [background] runs on the calling thread before [foreground] does -
   * the pair degrades to the sequence it replaced rather than queueing behind a busy pool and
   * holding a Hikari connection while it waits.
   */
  fun <A, B> both(
    background: () -> A,
    foreground: () -> B,
  ): Pair<A, B> {
    val pending = CompletableFuture.supplyAsync(background, executor)
    val foregroundResult = foreground()
    return pending.joinUnwrapped() to foregroundResult
  }

  /** [CompletableFuture.join] reports the original failure, not a wrapper around it. */
  private fun <A> CompletableFuture<A>.joinUnwrapped(): A =
    try {
      join()
    } catch (failure: CompletionException) {
      throw failure.cause ?: failure
    }

  private companion object {
    /**
     * Small on purpose. Every read running here borrows a second connection for the length of one
     * query, and the pool it borrows from holds at most
     * [DatabaseConfig.Companion.poolSizeForWorkers] connections in total.
     */
    private const val MAXIMUM_CONCURRENT_READS: Int = 4

    private val SHARED_EXECUTOR: Executor =
      ThreadPoolExecutor(
        0,
        MAXIMUM_CONCURRENT_READS,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable ->
          Thread(runnable, "xoboro-catalog-read").apply { isDaemon = true }
        },
        ThreadPoolExecutor.CallerRunsPolicy(),
      )
  }
}
