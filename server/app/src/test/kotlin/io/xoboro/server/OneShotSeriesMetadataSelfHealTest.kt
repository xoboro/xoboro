package io.xoboro.server

import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.ArtworkProcessor
import io.xoboro.core.application.BookMetadataPatch
import io.xoboro.core.application.BookMetadataProvider
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.LocalArtworkRefreshLifecycle
import io.xoboro.core.application.MetadataRefreshLifecycle
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.metadata.OneShotSeriesMetadataProvider
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqArtworkRepository
import io.xoboro.server.persistence.JooqBookMetadataRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.tasks.RefreshBookMetadataTaskHandler
import io.xoboro.server.tasks.RefreshMetadataTaskEmitter
import io.xoboro.server.tasks.RefreshSeriesMetadataTaskHandler
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * Regression coverage for the one-shot series title defect described in
 * `docs/architecture/0077-one-shot-navigation-parity.md`.
 *
 * If a `RefreshSeriesMetadataTask` for a one-shot series runs before the
 * `RefreshBookMetadataTask` for its (only) book, [OneShotSeriesMetadataProvider]
 * promotes whatever `book_metadata.title` held at that moment - typically the
 * file-name seed value inserted by the `initialize_book_metadata` trigger.
 * Before the fix, nothing re-ran the series refresh once the book's title was
 * corrected, so the wrong title stuck permanently.
 *
 * The fix makes `RefreshBookMetadataTaskHandler` re-enqueue a series metadata
 * refresh after every successful book refresh, via [BookMetadataRefreshCompletion]
 * - the exact production class `XoboroRuntime` wires as
 * `RefreshBookMetadataTaskHandler.afterRefresh`, not a parallel copy of its
 * logic. This test reproduces the "series ran first" ordering directly and
 * asserts the series title is corrected once the book refresh runs, against a
 * real, SQLite-backed catalog. Reverting `BookMetadataRefreshCompletion`'s
 * re-enqueue (or reverting `XoboroRuntime` back to not using it at all) makes
 * this test fail: the queue never gets a series task, `claimNext` returns
 * null, and the `requireNotNull` below throws.
 */
class OneShotSeriesMetadataSelfHealTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `book refresh re-triggers a one-shot series refresh so the correct title wins`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("oneshot-selfheal.sqlite"))).use {
        database ->
      val libraries = JooqLibraryRepository(database)
      val books = JooqBookRepository(database)
      val series = JooqSeriesRepository(database)
      val bookMetadata = JooqBookMetadataRepository(database)
      val seriesMetadata = JooqSeriesMetadataRepository(database)
      val queue = JooqDurableTaskQueue(database)

      libraries.insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      series.insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "standalone",
          relativePath = "standalone.cbz",
          sourceItemId = "file:///synthetic/standalone.cbz",
          fileModifiedAtMillis = 1,
          bookCount = 1,
          oneshot = true,
          createdAtMillis = 1,
        ),
      )
      books.insert(
        Book(
          id = BOOK_ID,
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "standalone",
          relativePath = "standalone.cbz",
          sourceItemId = "file:///synthetic/standalone.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )

      // Seeded by the `initialize_book_metadata` trigger: the book's title starts
      // out as its file name, exactly like the observed "standalone" defect.
      assertEquals("standalone", bookMetadata.findByBookIdOrNull(BOOK_ID)?.title)

      val clock = AtomicLong(100)
      val lifecycle =
        MetadataRefreshLifecycle(
          libraries = libraries,
          books = books,
          series = series,
          bookMetadata = bookMetadata,
          seriesMetadata = seriesMetadata,
          bookProviders =
            listOf(
              BookMetadataProvider { _, _ -> BookMetadataPatch(title = "Synthetic Standalone") },
            ),
          seriesProviders = listOf(OneShotSeriesMetadataProvider(bookMetadata)),
          currentTimeMillis = clock::getAndIncrement,
        )

      // Reproduce the observed ordering inversion directly: the series refresh
      // completes while the book's title is still the file-name seed value.
      lifecycle.refreshSeries(SERIES_ID)
      assertEquals("standalone", seriesMetadata.findBySeriesIdOrNull(SERIES_ID)?.title)

      val emitter =
        RefreshMetadataTaskEmitter(
          books = books,
          series = series,
          queue = queue,
          currentTimeMillis = clock::getAndIncrement,
        )

      // The exact production class XoboroRuntime wires as
      // RefreshBookMetadataTaskHandler.afterRefresh, not a parallel copy of its logic: this
      // is what makes a revert of the production wiring fail this test. Local artwork import
      // is not exercised here (accesses is empty, so LocalArtworkRefreshLifecycle.refreshBook
      // short-circuits before touching the artwork repository/processor below).
      val localArtworkRefresh =
        LocalArtworkRefreshLifecycle(
          libraries = libraries,
          books = books,
          series = series,
          artwork =
            ArtworkLifecycle(
              artwork = JooqArtworkRepository(database),
              processor = ArtworkProcessor { error("not used") },
              idFactory = { error("not used") },
              currentTimeMillis = clock::getAndIncrement,
            ),
          accesses = emptyList(),
        )
      val generatedCovers = mutableListOf<BookId>()
      val bookHandler =
        RefreshBookMetadataTaskHandler(
          lifecycle,
          afterRefresh =
            BookMetadataRefreshCompletion(
              books = books,
              localArtworkRefresh = localArtworkRefresh,
              refreshMetadataTaskEmitter = emitter,
              generateCover = { generatedCovers += it },
            ),
        )

      bookHandler.handle(
        DurableTask(
          id = RefreshMetadataTaskEmitter.bookTaskId(BOOK_ID),
          type = RefreshBookMetadataTaskHandler.TASK_TYPE,
          payloadJson = """{"bookId":"${BOOK_ID.value}"}""",
          priority = TaskPriority.LOW,
          availableAtMillis = 0,
        ),
      )

      assertEquals("Synthetic Standalone", bookMetadata.findByBookIdOrNull(BOOK_ID)?.title)
      assertEquals(listOf(BOOK_ID), generatedCovers)

      val claimedSeriesTask =
        requireNotNull(
          queue.claimNext(
            workerId = "worker-1",
            leaseToken = "lease-series",
            nowMillis = clock.getAndIncrement(),
            leaseDurationMillis = 1_000,
          ),
        ) { "Expected the book refresh to have re-enqueued a series metadata refresh" }
      assertEquals(RefreshMetadataTaskEmitter.seriesTaskId(SERIES_ID), claimedSeriesTask.task.id)
      assertEquals(TaskPriority.LOW, claimedSeriesTask.task.priority)

      RefreshSeriesMetadataTaskHandler(lifecycle).handle(claimedSeriesTask.task)

      assertEquals("Synthetic Standalone", seriesMetadata.findBySeriesIdOrNull(SERIES_ID)?.title)
    }
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
  }
}
