package io.xoboro.server

import io.xoboro.core.application.LocalArtworkRefreshLifecycle
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import io.xoboro.server.tasks.RefreshMetadataTaskEmitter
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Runs after `RefreshBookMetadataTaskHandler` successfully applies a book metadata refresh.
 *
 * Refreshes local artwork and generates the book and series covers, then re-enqueues a series
 * metadata refresh for the book's series. Cover generation stays in this durable background task
 * instead of delaying the analysis task that makes a book readable.
 *
 * The re-enqueue is what makes a one-shot series title self-heal when its
 * `RefreshSeriesMetadataTask` happened to run before this book's own refresh completed (see ADR
 * 0077): `OneShotSeriesMetadataProvider` promotes the book's title into the series, so once the
 * book's title is corrected here, re-running the series refresh lets it pick up the correction
 * instead of leaving a stale value permanently.
 *
 * Extracted into its own class (rather than an inline lambda in `XoboroRuntime.kt`) so this
 * behavior can be exercised directly by tests instead of only through a parallel copy of the
 * wiring.
 */
class BookMetadataRefreshCompletion(
  private val books: BookRepository,
  private val localArtworkRefresh: LocalArtworkRefreshLifecycle,
  private val refreshMetadataTaskEmitter: RefreshMetadataTaskEmitter,
  private val generateCover: (BookId) -> Unit = {},
  private val logger: Logger = Logger.getLogger(BookMetadataRefreshCompletion::class.java.name),
) : (BookId, Int) -> Unit {
  override fun invoke(
    bookId: BookId,
    priority: Int,
  ) {
    localArtworkRefresh.refreshBook(bookId)
    generateCover(bookId)
    val book = books.findByIdOrNull(bookId) ?: return
    // Preserve the parent task's priority: an automatic LOW refresh must not create HIGH work that
    // jumps back ahead of the DEFAULT analyses it was deliberately scheduled behind.
    val enqueued = refreshMetadataTaskEmitter.refreshSeriesMetadata(book.seriesId, priority)
    if (!enqueued) {
      // enqueue() drops this request if the series task row is already RUNNING (see
      // JooqDurableTaskQueue.enqueue). The mechanism behind the ordering inversion this class
      // exists to fix is not fully understood -- the book/series task pair share a groupId
      // that claimNext is supposed to serialise, and that was already shown not to guarantee
      // the ordering it appeared to. So this branch is not provably unreachable; log it rather
      // than assume it away, so a recurrence surfaces as a diagnosable warning instead of
      // silently reproducing the original defect with an empty task queue.
      logger.log(
        Level.WARNING,
        "Series metadata refresh for series ${book.seriesId.value} " +
          "(triggered by book ${bookId.value}) was not re-enqueued: " +
          "a refresh was already running for that series",
      )
    }
  }
}
