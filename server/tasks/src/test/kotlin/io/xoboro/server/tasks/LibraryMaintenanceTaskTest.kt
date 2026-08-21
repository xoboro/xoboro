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
import kotlin.test.assertNotEquals
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

      RefreshLibraryMetadataTaskHandler(refreshLibrary = { id, _, _ -> fanned += id }).handle(
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

  @Test
  fun `a series-only fan-out queues the series and stops before the books`() {
    // The gap that caused the incident. A series' cover comes from its sidecar and is rewritten by a
    // series refresh, so asking for covers meant asking for a whole library refresh - which also
    // queued a book metadata refresh for all 145,105 books, the most expensive per-item operation
    // there is. The host reached 758% CPU on 10 cores. Here the scope is the whole point: what must
    // be asserted is not that series are queued but that books are *not*.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("series-only.sqlite"))).use { database ->
      insertCatalog(database)
      val recorder = RecordingOrderQueue(JooqDurableTaskQueue(database))
      val metadata =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = recorder,
          currentTimeMillis = { 100 },
        )

      val emitted = metadata.refreshLibrary(LIBRARY_ID, seriesOnly = true)

      assertEquals(1, emitted)
      assertEquals(listOf(RefreshSeriesMetadataTaskHandler.TASK_TYPE), recorder.types)
    }
  }

  @Test
  fun `a series-only fan-out does not chain a books successor when its chunk fills`() {
    // A chunk that fills hands the remainder to a successor, and the remainder of a series-only pass
    // must never be the books stage. Getting this wrong would make the cheap request expand into the
    // expensive one one chunk later, which is worse than not having the request at all: it would
    // look like it worked.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("series-only-chunk.sqlite"))).use {
        database ->
      insertCatalog(database)
      val queue = JooqDurableTaskQueue(database)
      val metadata =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = queue,
          chunkSize = 1,
          currentTimeMillis = { 100 },
        )

      assertEquals(1, metadata.refreshLibrary(LIBRARY_ID, seriesOnly = true))

      assertFalse(
        RefreshBookMetadataTaskHandler.TASK_TYPE in queue.countsByType(),
        "a series-only fan-out must not reach the books stage",
      )
      // Asserting no book task was queued is not enough on its own: with the scope check missing,
      // this chunk queues no books either - it fills its budget and hands a *books-stage* cursor to
      // a successor, which queues them a chunk later. The successor is the thing to look for.
      assertFalse(
        RefreshLibraryMetadataTaskHandler.TASK_TYPE in queue.countsByType(),
        "a series-only fan-out that finished its series has nothing left to continue",
      )
    }
  }

  @Test
  fun `a series-only request cannot be deduplicated into a whole-library one`() {
    // Same task type, same library: the queue keeps the row it already has. Sharing an id would
    // answer "covers only" with a request for everything, or silently drop it.
    assertNotEquals(
      RefreshMetadataTaskEmitter.libraryTaskId(LIBRARY_ID),
      RefreshMetadataTaskEmitter.libraryTaskId(LIBRARY_ID, seriesOnly = true),
    )
  }

  @Test
  fun `the fan-out handler carries the scope it was queued with`() {
    val scopes = mutableListOf<Boolean>()
    RefreshLibraryMetadataTaskHandler(
      refreshLibrary = { _, _, seriesOnly -> scopes += seriesOnly },
    ).handle(
      DurableTask(
        id = RefreshMetadataTaskEmitter.libraryTaskId(LIBRARY_ID, seriesOnly = true),
        type = RefreshLibraryMetadataTaskHandler.TASK_TYPE,
        payloadJson =
          """{"libraryId":"${LIBRARY_ID.value}","seriesOnly":true}""",
        priority = TaskPriority.HIGH,
        groupId = LIBRARY_ID.value,
        availableAtMillis = 1,
      ),
    )
    // A payload written before the field existed was queued for the whole library, so its absence
    // has to read as `false` rather than as anything else.
    RefreshLibraryMetadataTaskHandler(
      refreshLibrary = { _, _, seriesOnly -> scopes += seriesOnly },
    ).handle(
      DurableTask(
        id = RefreshMetadataTaskEmitter.libraryTaskId(LIBRARY_ID),
        type = RefreshLibraryMetadataTaskHandler.TASK_TYPE,
        payloadJson = """{"libraryId":"${LIBRARY_ID.value}"}""",
        priority = TaskPriority.HIGH,
        groupId = LIBRARY_ID.value,
        availableAtMillis = 1,
      ),
    )

    assertEquals(listOf(true, false), scopes)
  }

  @Test
  fun `a fan-out chunk hands the rest of the library to a successor`() {
    // An unbounded pass cannot be relied on to reach its own end: one busy enqueue throws, the whole
    // task is deferred, and the re-run starts over - re-queuing every book it had already finished,
    // because a completed task deletes its row. A library large enough to meet contention partway
    // every time never finishes at all.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("fan-out-chunked.sqlite"))).use {
        database ->
      insertCatalog(database)
      val queue = JooqDurableTaskQueue(database)
      val metadata =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = queue,
          currentTimeMillis = { 100 },
          chunkSize = 1,
        )

      // One item of budget, spent on the series. The book is left to a successor.
      assertEquals(1, metadata.refreshLibrary(LIBRARY_ID))
      assertEquals(
        mapOf(
          RefreshSeriesMetadataTaskHandler.TASK_TYPE to 1,
          RefreshLibraryMetadataTaskHandler.TASK_TYPE to 1,
        ),
        queue.countsByType(),
      )

      // The successor carries its cursor in its id as well as its payload, because an `enqueue`
      // collision on a still-RUNNING row keeps the old payload and would silently drop it.
      val successorId =
        RefreshMetadataTaskEmitter.libraryTaskId(LIBRARY_ID) +
          RefreshMetadataTaskEmitter.RESUME_SEPARATOR +
          LibraryFanOutCursor.BOOKS_START.encode()
      assertEquals(
        listOf(successorId, "REFRESH_SERIES_METADATA_series-1"),
        drainedIds(queue),
      )

      // The handler hands that cursor back to the emitter rather than starting over.
      val resumed = mutableListOf<Pair<LibraryId, LibraryFanOutCursor>>()
      RefreshLibraryMetadataTaskHandler(
        refreshLibrary = { id, cursor, _ -> resumed += id to cursor },
      ).handle(
        DurableTask(
          id = successorId,
          type = RefreshLibraryMetadataTaskHandler.TASK_TYPE,
          payloadJson =
            """{"libraryId":"${LIBRARY_ID.value}",""" +
              """"cursor":"${LibraryFanOutCursor.BOOKS_START.encode()}"}""",
          availableAtMillis = 100,
        ),
      )
      assertEquals(listOf(LIBRARY_ID to LibraryFanOutCursor.BOOKS_START), resumed)

      // Resuming queues the book and, crucially, not the series again - the series task it already
      // completed left no row behind to deduplicate against, so an unchunked restart would redo it.
      assertEquals(1, metadata.refreshLibrary(LIBRARY_ID, from = LibraryFanOutCursor.BOOKS_START))
      assertEquals(
        listOf("REFRESH_BOOK_METADATA_book-active"),
        drainedIds(queue),
      )
    }
  }

  @Test
  fun `an analysis fan-out chunk hands the rest of the library to a successor`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("analysis-chunked.sqlite"))).use {
        database ->
      insertCatalog(database)
      JooqBookRepository(database).insert(bookFixture("book-second", deletedAtMillis = null))
      val queue = JooqDurableTaskQueue(database)
      val analysis =
        AnalyzeBookTaskEmitter(
          books = JooqBookRepository(database),
          queue = queue,
          currentTimeMillis = { 100 },
          chunkSize = 1,
        )

      assertEquals(1, analysis.analyzeLibrary(LIBRARY_ID))
      assertEquals(
        mapOf(
          AnalyzeBookTaskHandler.TASK_TYPE to 1,
          AnalyzeLibraryTaskHandler.TASK_TYPE to 1,
        ),
        queue.countsByType(),
      )

      // Ordered by id, so the chunk took book-active and the cursor points past it. book-deleted is
      // filtered out before the ordering, not counted against the budget.
      val cursor = LibraryFanOutCursor(LibraryFanOutCursor.Stage.BOOKS, "book-active")
      val resumed = mutableListOf<LibraryFanOutCursor>()
      AnalyzeLibraryTaskHandler(analyzeLibrary = { _, from -> resumed += from }).handle(
        DurableTask(
          id = AnalyzeLibraryTaskHandler.taskId(LIBRARY_ID) +
            RefreshMetadataTaskEmitter.RESUME_SEPARATOR + cursor.encode(),
          type = AnalyzeLibraryTaskHandler.TASK_TYPE,
          payloadJson =
            """{"libraryId":"${LIBRARY_ID.value}","cursor":"${cursor.encode()}"}""",
          availableAtMillis = 100,
        ),
      )
      assertEquals(listOf(cursor), resumed)

      // Resuming queues book-second and nothing else, and chains no further successor because the
      // second chunk reached the end of the library.
      assertEquals(1, analysis.analyzeLibrary(LIBRARY_ID, from = cursor))
      assertEquals(
        mapOf(
          AnalyzeBookTaskHandler.TASK_TYPE to 2,
          AnalyzeLibraryTaskHandler.TASK_TYPE to 1,
        ),
        queue.countsByType(),
      )
      assertEquals(
        listOf(
          "ANALYZE_BOOK_book-active",
          "ANALYZE_BOOK_book-second",
          AnalyzeLibraryTaskHandler.taskId(LIBRARY_ID) +
            RefreshMetadataTaskEmitter.RESUME_SEPARATOR + cursor.encode(),
        ),
        drainedIds(queue),
      )
    }
  }

  @Test
  fun `a fan-out chunk that runs out mid-series resumes inside the series stage`() {
    // Distinct from the case above, which exhausts its budget exactly as the series stage ends and so
    // resumes at the start of the books stage. Here the budget runs out with series still to do, and
    // the cursor has to name the series to carry on after - not the stage boundary.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("fan-out-mid-series.sqlite"))).use {
        database ->
      insertCatalog(database)
      JooqSeriesRepository(database).insert(
        Series(
          id = SeriesId("series-2"),
          libraryId = LIBRARY_ID,
          name = "Second synthetic series",
          relativePath = "series-2",
          sourceItemId = "file:///synthetic/series-2",
          fileModifiedAtMillis = 1,
          bookCount = 0,
          createdAtMillis = 1,
        ),
      )
      val queue = JooqDurableTaskQueue(database)
      val metadata =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = queue,
          chunkSize = 1,
          currentTimeMillis = { 100 },
        )

      assertEquals(1, metadata.refreshLibrary(LIBRARY_ID))
      val successorId =
        RefreshMetadataTaskEmitter.libraryTaskId(LIBRARY_ID) +
          RefreshMetadataTaskEmitter.RESUME_SEPARATOR +
          LibraryFanOutCursor(LibraryFanOutCursor.Stage.SERIES, "series-1").encode()
      assertEquals(
        listOf(successorId, "REFRESH_SERIES_METADATA_series-1"),
        drainedIds(queue),
      )

      // Carrying on from there queues series-2 and then, budget spent again, the next successor.
      assertEquals(
        1,
        metadata.refreshLibrary(
          LIBRARY_ID,
          from = LibraryFanOutCursor(LibraryFanOutCursor.Stage.SERIES, "series-1"),
        ),
      )
      assertEquals(
        listOf(
          RefreshMetadataTaskEmitter.libraryTaskId(LIBRARY_ID) +
            RefreshMetadataTaskEmitter.RESUME_SEPARATOR +
            LibraryFanOutCursor.BOOKS_START.encode(),
          "REFRESH_SERIES_METADATA_series-2",
        ),
        drainedIds(queue),
      )
    }
  }

  @Test
  fun `a fan-out with no remainder chains no successor`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("fan-out-whole.sqlite"))).use {
        database ->
      insertCatalog(database)
      val queue = JooqDurableTaskQueue(database)
      val metadata =
        RefreshMetadataTaskEmitter(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = queue,
          currentTimeMillis = { 100 },
        )

      assertEquals(2, metadata.refreshLibrary(LIBRARY_ID))
      assertFalse(RefreshLibraryMetadataTaskHandler.TASK_TYPE in queue.countsByType())
    }
  }

  /**
   * Drains the queue, answering the ids it handed out in order.
   *
   * Claim-and-complete rather than a direct query, because this module deliberately does not depend
   * on persistence's jOOQ context - only on the queue interface.
   */
  private fun drainedIds(queue: JooqDurableTaskQueue): List<String> {
    val ids = mutableListOf<String>()
    while (true) {
      val claimed =
        queue.claimNext(
          workerId = "worker-drain",
          leaseToken = "lease-drain-${ids.size}",
          nowMillis = 100,
          leaseDurationMillis = 1_000,
        ) ?: return ids
      ids += claimed.task.id
      assertTrue(queue.complete(claimed.task.id, "lease-drain-${ids.size - 1}"))
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

  @Test
  fun `targeted book analysis jumps ahead of routine maintenance`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("targeted-analysis-priority.sqlite"))).use {
        database ->
      insertCatalog(database)
      val queue = JooqDurableTaskQueue(database)
      val requester =
        DurableCatalogMaintenanceRequester(
          analysis =
            AnalyzeBookTaskEmitter(
              JooqBookRepository(database),
              queue,
              currentTimeMillis = { 500 },
            ),
          metadata =
            RefreshMetadataTaskEmitter(
              JooqBookRepository(database),
              JooqSeriesRepository(database),
              queue,
              currentTimeMillis = { 500 },
            ),
          queue = queue,
        )

      assertTrue(requester.analyzeBook(BookId("book-active")))
      val claimed =
        requireNotNull(
          queue.claimNext(
            workerId = "reader-worker",
            leaseToken = "reader-lease",
            nowMillis = 500,
            leaseDurationMillis = 1_000,
          ),
        )

      assertEquals("ANALYZE_BOOK_book-active", claimed.task.id)
      assertEquals(TaskPriority.HIGHEST, claimed.task.priority)
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
