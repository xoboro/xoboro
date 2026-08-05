package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.EmptyTrashResult
import io.xoboro.core.application.LibraryTrashStore
import io.xoboro.core.application.TaskEnqueue
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.application.TaskStoreUnavailableException
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LibraryMaintenanceTaskTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `a library request queues one fan-out task instead of one task per book`() {
    // The shape this pins is the whole point of the fix. Fanning out inline meant a request
    // performed one insert per book and per series - 24,696 of them for a library of 314 webtoon
    // series - which held the write lock for the duration and had nowhere to go when the lock was
    // already held by a scan. It died mid-loop with a 500 and a half-queued library.
    //
    // Asserting on the count rather than on the absence of an exception, because a fan-out that
    // merely stopped throwing would still be wrong: the request must do a fixed, small amount of
    // work no matter how large the library is.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("fan-out.sqlite"))).use { database ->
      val queue = JooqDurableTaskQueue(database)
      insertCatalog(database)
      val metadata =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = queue,
          currentTimeMillis = { 100 },
        )
      val analysis =
        AnalyzeBookTaskEmitter(
          books = JooqBookRepository(database),
          queue = queue,
          currentTimeMillis = { 100 },
        )

      assertEquals(TaskEnqueue.QUEUED, metadata.refreshLibraryDeferred(LIBRARY_ID))
      assertEquals(TaskEnqueue.QUEUED, analysis.analyzeLibraryDeferred(LIBRARY_ID))

      // Two rows for two requests. The inline emitters that these defer to would have queued three
      // between them against this same catalog (see the two tests below).
      assertEquals(2L, queue.counts().pending)
      assertEquals(
        mapOf(
          RefreshLibraryMetadataTaskHandler.TASK_TYPE to 1,
          AnalyzeLibraryTaskHandler.TASK_TYPE to 1,
        ),
        queue.countsByType(),
      )
    }
  }

  @Test
  fun `the fan-out handlers run the per-book emission a request used to do inline`() {
    // A fan-out task that is queued but never handled is worse than the bug it replaced: the
    // request reports 202 and nothing whatsoever happens. This pins that each handler accepts its
    // own type's payload and performs the emission.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("fan-out-handled.sqlite"))).use {
        database ->
      val queue = JooqDurableTaskQueue(database)
      insertCatalog(database)
      val metadata =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = queue,
          currentTimeMillis = { 100 },
        )
      val fanned = mutableListOf<LibraryId>()

      RefreshLibraryMetadataTaskHandler(refreshLibrary = { fanned += it }).handle(
        DurableTask(
          id = RefreshMetadataTaskEmitter.libraryTaskId(LIBRARY_ID),
          type = RefreshLibraryMetadataTaskHandler.TASK_TYPE,
          payloadJson = """{"libraryId":"${LIBRARY_ID.value}"}""",
          availableAtMillis = 100,
        ),
      )
      assertEquals(listOf(LIBRARY_ID), fanned)

      // And the emission it delegates to is the pre-existing inline one, unchanged: two tasks for
      // this catalog's one active book and one series.
      assertEquals(2, metadata.refreshLibrary(LIBRARY_ID))
    }
  }

  @Test
  fun `a fan-out stops on a busy store rather than counting the dropped task as a skip`() {
    // Under `enqueue`'s old boolean, a busy store and a live lease were both `false`, so a fan-out
    // tallied a task it had failed to queue as one it had deliberately skipped: the loop ran to
    // completion, the count came back short, and nobody could tell short-because-busy from
    // short-because-already-queued. Those books' metadata then simply never refreshed.
    //
    // Inside a task a throw is the correct outcome - the worker retries with backoff and the
    // deterministic ids make the retry idempotent - so what this pins is that the drop is loud.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("busy-fan-out.sqlite"))).use {
        database ->
      insertCatalog(database)
      val metadata =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = UnavailableQueue(JooqDurableTaskQueue(database)),
          currentTimeMillis = { 100 },
        )

      assertFailsWith<TaskStoreUnavailableException> { metadata.refreshLibrary(LIBRARY_ID) }
    }
  }

  @Test
  fun `a fan-out queues series metadata before it queues books`() {
    // Contention stops the fan-out partway and the retry restarts it from the top, so whatever it
    // emits second may never be reached at all. A library's series carry the sidecar with the title,
    // summary and status a refresh visibly produces, and they are outnumbered by its books two orders
    // of magnitude - 314 against 24,696 in one real library. With books emitted first, that library
    // sat at 27 of 314 series filled while the queue looked perfectly healthy.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("fan-out-order.sqlite"))).use {
        database ->
      insertCatalog(database)
      val recorder = RecordingOrderQueue(JooqDurableTaskQueue(database))
      val metadata =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = recorder,
          currentTimeMillis = { 100 },
        )

      metadata.refreshLibrary(LIBRARY_ID)

      assertEquals(
        listOf(
          RefreshSeriesMetadataTaskHandler.TASK_TYPE,
          RefreshBookMetadataTaskHandler.TASK_TYPE,
        ),
        recorder.types,
      )
    }
  }

  /** Records the order types were queued in, delegating everything else to the real queue. */
  private class RecordingOrderQueue(
    private val delegate: JooqDurableTaskQueue,
  ) : DurableTaskQueue by delegate {
    val types = mutableListOf<String>()

    override fun enqueue(
      task: DurableTask,
      nowMillis: Long,
    ): TaskEnqueue {
      if (types.lastOrNull() != task.type) types += task.type
      return delegate.enqueue(task, nowMillis)
    }
  }

  /** A queue whose store is permanently busy, delegating everything else to the real one. */
  private class UnavailableQueue(
    private val delegate: JooqDurableTaskQueue,
  ) : DurableTaskQueue by delegate {
    override fun enqueue(
      task: DurableTask,
      nowMillis: Long,
    ): TaskEnqueue = TaskEnqueue.UNAVAILABLE
  }

  @Test
  fun `analysis emitter queues only active books with high priority and series exclusion`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("analysis.sqlite"))).use { database ->
      val books = JooqBookRepository(database)
      val queue = JooqDurableTaskQueue(database)
      insertCatalog(database)
      val emitter =
        AnalyzeBookTaskEmitter(
          books = books,
          queue = queue,
          currentTimeMillis = { 100 },
        )

      assertEquals(1, emitter.analyzeLibrary(LIBRARY_ID))
      val claimed =
        requireNotNull(
          queue.claimNext(
            workerId = "worker-1",
            leaseToken = "lease-1",
            nowMillis = 100,
            leaseDurationMillis = 1_000,
          ),
        )
      assertEquals("ANALYZE_BOOK_book-active", claimed.task.id)
      assertEquals(AnalyzeBookTaskHandler.TASK_TYPE, claimed.task.type)
      assertEquals("""{"bookId":"book-active"}""", claimed.task.payloadJson)
      assertEquals(TaskPriority.HIGH, claimed.task.priority)
      assertEquals(SERIES_ID.value, claimed.task.groupId)
    }
  }

  @Test
  fun `empty trash task is durable deduplicated and validates its payload`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("empty-trash.sqlite"))).use {
        database ->
      val queue = JooqDurableTaskQueue(database)
      val emitter = EmptyLibraryTrashTaskEmitter(queue, currentTimeMillis = { 200 })
      assertTrue(emitter.emptyTrash(LIBRARY_ID))
      assertTrue(emitter.emptyTrash(LIBRARY_ID))
      val claimed =
        requireNotNull(
          queue.claimNext(
            workerId = "worker-1",
            leaseToken = "lease-1",
            nowMillis = 200,
            leaseDurationMillis = 1_000,
          ),
        )
      assertEquals("EMPTY_TRASH_library-1", claimed.task.id)
      assertEquals(TaskPriority.HIGH, claimed.task.priority)

      val emptied = mutableListOf<LibraryId>()
      val handler =
        EmptyLibraryTrashTaskHandler(
          trash =
            LibraryTrashStore { libraryId ->
              emptied += libraryId
              EmptyTrashResult(1, 1)
            },
        )
      handler.handle(claimed.task)
      assertEquals(listOf(LIBRARY_ID), emptied)
      assertFailsWith<IllegalArgumentException> {
        handler.handle(
          DurableTask(
            id = "invalid-empty-trash",
            type = EmptyLibraryTrashTaskHandler.TASK_TYPE,
            payloadJson = """{"libraryId":""}""",
            availableAtMillis = 0,
          ),
        )
      }
    }
  }

  @Test
  fun `metadata refresh queues active books before their series in one exclusion group`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("metadata.sqlite"))).use { database ->
      val queue = JooqDurableTaskQueue(database)
      insertCatalog(database)
      val emitter =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = queue,
          currentTimeMillis = { 300 },
        )

      assertEquals(2, emitter.refreshLibrary(LIBRARY_ID))
      val bookTask =
        requireNotNull(
          queue.claimNext(
            workerId = "worker-1",
            leaseToken = "lease-book",
            nowMillis = 300,
            leaseDurationMillis = 1_000,
          ),
        )
      assertEquals("REFRESH_BOOK_METADATA_book-active", bookTask.task.id)
      assertEquals(RefreshBookMetadataTaskHandler.TASK_TYPE, bookTask.task.type)
      assertEquals(SERIES_ID.value, bookTask.task.groupId)
      assertTrue(queue.complete(bookTask.task.id, "lease-book"))

      val seriesTask =
        requireNotNull(
          queue.claimNext(
            workerId = "worker-1",
            leaseToken = "lease-series",
            nowMillis = 300,
            leaseDurationMillis = 1_000,
          ),
        )
      assertEquals("REFRESH_SERIES_METADATA_series-1", seriesTask.task.id)
      assertEquals(RefreshSeriesMetadataTaskHandler.TASK_TYPE, seriesTask.task.type)
      assertEquals(SERIES_ID.value, seriesTask.task.groupId)
    }
  }

  @Test
  fun `incremental series metadata refresh queues only the series`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("series-metadata.sqlite"))).use {
        database ->
      val queue = JooqDurableTaskQueue(database)
      insertCatalog(database)
      val emitter =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = queue,
          currentTimeMillis = { 350 },
        )

      assertTrue(emitter.refreshSeriesMetadata(SERIES_ID))
      val claimed =
        requireNotNull(
          queue.claimNext(
            workerId = "worker-1",
            leaseToken = "lease-series",
            nowMillis = 350,
            leaseDurationMillis = 1_000,
          ),
        )
      assertEquals("REFRESH_SERIES_METADATA_series-1", claimed.task.id)
      assertEquals(RefreshSeriesMetadataTaskHandler.TASK_TYPE, claimed.task.type)
      assertEquals(SERIES_ID.value, claimed.task.groupId)
      assertTrue(queue.complete(claimed.task.id, "lease-series"))
      assertEquals(
        null,
        queue.claimNext(
          workerId = "worker-1",
          leaseToken = "lease-none",
          nowMillis = 350,
          leaseDurationMillis = 1_000,
        ),
      )
    }
  }

  @Test
  fun `catalog requester emits target scoped durable work and clears only queued work`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("catalog-maintenance.sqlite"))).use {
        database ->
      insertCatalog(database)
      val queue = JooqDurableTaskQueue(database)
      val requester =
        DurableCatalogMaintenanceRequester(
          analysis =
            AnalyzeBookTaskEmitter(
              JooqBookRepository(database),
              queue,
              currentTimeMillis = { 400 },
            ),
          metadata =
            RefreshMetadataTaskEmitter(
              JooqBookRepository(database),
              JooqSeriesRepository(database),
              queue,
              currentTimeMillis = { 400 },
            ),
          queue = queue,
        )

      assertTrue(requester.analyzeBook(BookId("book-active")))
      assertFalse(requester.analyzeBook(BookId("missing")))
      assertEquals(1, requester.analyzeSeries(SERIES_ID))
      assertEquals(2, requester.refreshSeriesMetadata(SERIES_ID))
      assertFalse(requester.refreshBookMetadata(BookId("book-deleted")))
      assertEquals(3, requester.clearUnclaimedTasks())
      assertEquals(0, queue.counts().pending)
    }
  }

  private fun insertCatalog(database: XoboroDatabase) {
    JooqLibraryRepository(database).insert(
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation("local", "file:///synthetic"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = SERIES_ID,
        libraryId = LIBRARY_ID,
        name = "Synthetic series",
        relativePath = "series",
        sourceItemId = "file:///synthetic/series",
        fileModifiedAtMillis = 1,
        bookCount = 2,
        createdAtMillis = 1,
      ),
    )
    JooqBookRepository(database).insertAll(
      listOf(
        bookFixture("book-active", deletedAtMillis = null),
        bookFixture("book-deleted", deletedAtMillis = 2),
      ),
    )
  }

  private fun bookFixture(
    id: String,
    deletedAtMillis: Long?,
  ): Book =
    Book(
      id = BookId(id),
      libraryId = LIBRARY_ID,
      seriesId = SERIES_ID,
      name = "Synthetic $id",
      relativePath = "series/$id.cbz",
      sourceItemId = "file:///synthetic/series/$id.cbz",
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1,
      deletedAtMillis = deletedAtMillis,
      createdAtMillis = 1,
      updatedAtMillis = deletedAtMillis ?: 1,
    )

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
  }
}
