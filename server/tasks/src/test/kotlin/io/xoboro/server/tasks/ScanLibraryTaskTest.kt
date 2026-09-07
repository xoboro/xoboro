package io.xoboro.server.tasks

import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.LibraryAvailabilityLifecycle
import io.xoboro.core.application.LibraryEvent
import io.xoboro.core.application.LibraryEventPublisher
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReconciliationStore
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.sources.local.LocalInventoryUnavailableException
import io.xoboro.server.sources.local.LocalSourceInventory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ScanLibraryTaskTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `emits the Komga-compatible durable task contract`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("emitter.sqlite"))).use { database ->
      val queue = JooqDurableTaskQueue(database)
      val emitter = ScanLibraryTaskEmitter(queue, currentTimeMillis = { 123 })

      assertTrue(
        emitter.scanLibrary(
          libraryId = LIBRARY_ID,
          deep = true,
          priority = TaskPriority.HIGHEST,
        ),
      )
      assertTrue(
        emitter.scanLibrary(
          libraryId = LIBRARY_ID,
          deep = true,
          priority = TaskPriority.HIGHEST,
        ),
      )
      assertEquals(TaskCounts(pending = 1, running = 0, dead = 0), queue.counts())

      val claim =
        requireNotNull(
          queue.claimNext(
            workerId = "worker-1",
            leaseToken = "lease-1",
            nowMillis = 123,
            leaseDurationMillis = 1_000,
          ),
        )
      assertEquals("SCAN_LIBRARY_library-1_DEEP_true", claim.task.id)
      assertEquals(ScanLibraryTaskHandler.TASK_TYPE, claim.task.type)
      assertEquals("""{"libraryId":"library-1","deep":true}""", claim.task.payloadJson)
      assertEquals(TaskPriority.HIGHEST, claim.task.priority)
      // A library's scans and its metadata fan-out share this group, so only one of them runs at a
      // time. Ungrouped, two scans of one library could run at once - and `begin` retires every
      // other STAGING session for the library, so each would discard the other's staged candidates.
      assertEquals(LIBRARY_ID.value, claim.task.groupId)
      assertEquals(ScanLibraryTaskEmitter.SCAN_EXCLUSION_KEY, claim.task.exclusionKey)
    }
  }

  @Test
  fun `a scan requested while one is running queues another pass`() {
    // A scan reads the source once, near its start, so a request arriving after that read cannot be
    // satisfied by the run in flight - the change it is asking about has already been missed.
    // Collapsing onto the running id answered 202 and did nothing: delete a file, press Scan, and
    // the catalog kept serving the file. It also made XoboroLocalLibraryAcceptanceTest's
    // delete-then-rescan case fail intermittently, which is how this surfaced.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("rescan.sqlite"))).use { database ->
      val queue = JooqDurableTaskQueue(database)
      val emitter = ScanLibraryTaskEmitter(queue, currentTimeMillis = { 123 })

      assertTrue(emitter.scanLibrary(LIBRARY_ID))
      val running =
        requireNotNull(
          queue.claimNext(
            workerId = "worker-1",
            leaseToken = "lease-1",
            nowMillis = 123,
            leaseDurationMillis = 1_000,
          ),
        )
      assertEquals(ScanLibraryTaskEmitter.taskId(LIBRARY_ID, deep = false), running.task.id)

      // The request that lands mid-scan earns a second pass rather than being swallowed.
      assertTrue(emitter.scanLibrary(LIBRARY_ID))
      assertEquals(TaskCounts(pending = 1, running = 1, dead = 0), queue.counts())

      // Further requests during the same scan collapse onto that one waiting pass: several people
      // pressing Scan mean one more scan, not one each. They report `true` because they do reach the
      // store and the pass they asked for is queued - it is the same row, which is the point.
      assertTrue(emitter.scanLibrary(LIBRARY_ID))
      assertTrue(emitter.scanLibrary(LIBRARY_ID))
      assertEquals(TaskCounts(pending = 1, running = 1, dead = 0), queue.counts())

      // The waiting pass cannot start until the running one is finished, because they share the
      // library's exclusion group.
      assertEquals(
        null,
        queue.claimNext(
          workerId = "worker-2",
          leaseToken = "lease-2",
          nowMillis = 123,
          leaseDurationMillis = 1_000,
        ),
      )
      assertTrue(queue.complete(running.task.id, "lease-1"))
      val followUp =
        requireNotNull(
          queue.claimNext(
            workerId = "worker-2",
            leaseToken = "lease-2",
            nowMillis = 123,
            leaseDurationMillis = 1_000,
          ),
        )
      assertEquals(
        ScanLibraryTaskEmitter.taskId(LIBRARY_ID, deep = false) +
          ScanLibraryTaskEmitter.FOLLOW_UP_SUFFIX,
        followUp.task.id,
      )
      // Same payload, so the pass it performs is a full scan and not a variant of one.
      assertEquals("""{"libraryId":"library-1","deep":false}""", followUp.task.payloadJson)

      // And a request during *that* pass goes back to the plain id, so the two alternate instead of
      // one of them becoming permanently unqueueable.
      assertTrue(emitter.scanLibrary(LIBRARY_ID))
      assertEquals(TaskCounts(pending = 1, running = 1, dead = 0), queue.counts())
      assertTrue(queue.complete(followUp.task.id, "lease-2"))
      assertEquals(
        ScanLibraryTaskEmitter.taskId(LIBRARY_ID, deep = false),
        requireNotNull(
          queue.claimNext(
            workerId = "worker-3",
            leaseToken = "lease-3",
            nowMillis = 123,
            leaseDurationMillis = 1_000,
          ),
        ).task.id,
      )
    }
  }

  @Test
  fun `executes a local scan through the durable worker and schedules analysis`() {
    val seriesDirectory = Files.createDirectories(tempDirectory.resolve("Synthetic series"))
    Files.write(seriesDirectory.resolve("Book 001.cbz"), byteArrayOf(1, 2, 3))
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("scan.sqlite"))).use { database ->
      val libraries = JooqLibraryRepository(database)
      val books = JooqBookRepository(database)
      val series = JooqSeriesRepository(database)
      val queue = JooqDurableTaskQueue(database)
      libraries.insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", tempDirectory.toUri().toString()),
          settings =
            LibrarySettings(
              scanPdf = false,
              scanEpub = false,
            ),
          createdAtMillis = 1,
        ),
      )
      val scanner =
        CatalogScanner(
          inventories = listOf(LocalSourceInventory()),
          reconciliationStore = JooqCatalogReconciliationStore(database),
          currentTimeMillis = { 200 },
          batchSize = 1,
        )
      val emitter = ScanLibraryTaskEmitter(queue, currentTimeMillis = { 100 })
      emitter.scanLibrary(LIBRARY_ID)
      val completedScans = mutableListOf<Pair<LibraryId, Boolean>>()
      val worker =
        DurableTaskWorker(
          queue = queue,
          handlers =
            listOf(
              ScanLibraryTaskHandler(
                libraries = libraries,
                scanner = scanner,
                afterScan = { library, deep ->
                  completedScans += library.id to deep
                },
              ),
            ),
          heartbeat = LeaseHeartbeat { _, _ -> AutoCloseable {} },
          currentTimeMillis = { 200 },
          leaseTokenFactory = { "lease-1" },
        )

      assertEquals(
        TaskRunResult.Completed("SCAN_LIBRARY_library-1_DEEP_false"),
        worker.runOnce("worker-1"),
      )

      assertEquals(1, series.count())
      assertEquals(1, books.count())
      assertEquals(listOf(LIBRARY_ID to false), completedScans)
      assertEquals("Synthetic series/Book 001.cbz", books.findAllByLibraryId(LIBRARY_ID).single().relativePath)
      assertEquals(TaskCounts(pending = 1, running = 0, dead = 0), queue.counts())
    }
  }

  @Test
  fun `treats a deleted library as a completed no-op`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("missing.sqlite"))).use { database ->
      val queue = JooqDurableTaskQueue(database)
      ScanLibraryTaskEmitter(queue, currentTimeMillis = { 10 }).scanLibrary(LIBRARY_ID)
      val worker =
        DurableTaskWorker(
          queue = queue,
          handlers =
            listOf(
              ScanLibraryTaskHandler(
                libraries = JooqLibraryRepository(database),
                scanner =
                  CatalogScanner(
                    inventories = listOf(LocalSourceInventory()),
                    reconciliationStore = JooqCatalogReconciliationStore(database),
                    currentTimeMillis = { 20 },
                  ),
              ),
            ),
          heartbeat = LeaseHeartbeat { _, _ -> AutoCloseable {} },
          currentTimeMillis = { 20 },
          leaseTokenFactory = { "lease-1" },
        )

      assertEquals(
        TaskRunResult.Completed("SCAN_LIBRARY_library-1_DEEP_false"),
        worker.runOnce("worker-1"),
      )
      assertEquals(TaskCounts(0, 0, 0), queue.counts())
    }
  }

  @Test
  fun `preserves the catalog while storage is unavailable and clears the flag on recovery`() {
    val root = tempDirectory.resolve("detached")
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("availability.sqlite"))).use {
        database ->
      val libraries = JooqLibraryRepository(database)
      libraries.insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", root.toUri().toString()),
          createdAtMillis = 1,
        ),
      )
      val series = JooqSeriesRepository(database)
      val books = JooqBookRepository(database)
      val seriesId = SeriesId("series-1")
      series.insert(
        Series(
          id = seriesId,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "Synthetic series",
          sourceItemId = root.resolve("Synthetic series").toUri().toString(),
          fileModifiedAtMillis = 1,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      )
      books.insertAll(
        listOf(
          Book(
            id = BookId("book-1"),
            libraryId = LIBRARY_ID,
            seriesId = seriesId,
            name = "Synthetic book",
            relativePath = "Synthetic series/Synthetic book.cbz",
            sourceItemId =
              root.resolve("Synthetic series/Synthetic book.cbz").toUri().toString(),
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 1,
            fileSize = 10,
            createdAtMillis = 1,
          ),
        ),
      )
      val events = mutableListOf<LibraryEvent>()
      var now = 100L
      val handler =
        ScanLibraryTaskHandler(
          libraries = libraries,
          scanner =
            CatalogScanner(
              inventories = listOf(LocalSourceInventory()),
              reconciliationStore = JooqCatalogReconciliationStore(database),
              currentTimeMillis = { now },
            ),
          availability =
            LibraryAvailabilityLifecycle(
              libraries = libraries,
              currentTimeMillis = { now },
              eventPublisher = LibraryEventPublisher(events::add),
            ),
        )

      assertFailsWith<LocalInventoryUnavailableException> {
        handler.handle(scanTask("""{"libraryId":"library-1","deep":false}"""))
      }
      assertEquals(100, libraries.findById(LIBRARY_ID).unavailableAtMillis)
      assertEquals(1, series.count())
      assertEquals(1, books.count())
      assertEquals(null, series.findByIdOrNull(seriesId)?.deletedAtMillis)
      assertEquals(null, books.findByIdOrNull(BookId("book-1"))?.deletedAtMillis)
      assertEquals(1, events.size)

      now = 200
      Files.createDirectories(root)
      handler.handle(scanTask("""{"libraryId":"library-1","deep":false}"""))

      assertEquals(null, libraries.findById(LIBRARY_ID).unavailableAtMillis)
      assertEquals(2, events.size)
    }
  }

  @Test
  fun `rejects malformed scan payloads`() {
    val scanner =
      CatalogScanner(
        inventories = emptyList(),
        reconciliationStore =
          object : io.xoboro.core.application.CatalogReconciliationStore {
            override fun begin(
              libraryId: LibraryId,
              deep: Boolean,
              startedAtMillis: Long,
            ) = error("not used")

            override fun stage(
              sessionId: io.xoboro.core.application.ScanSessionId,
              candidates: List<io.xoboro.core.application.CatalogCandidate>,
            ) = error("not used")

            override fun stagedVolumeCandidatePaths(
              sessionId: io.xoboro.core.application.ScanSessionId,
            ) = error("not used")

            override fun unstage(
              sessionId: io.xoboro.core.application.ScanSessionId,
              relativePaths: Collection<String>,
            ) = error("not used")

            override fun complete(
              sessionId: io.xoboro.core.application.ScanSessionId,
              failedEntries: Long,
              ignoredFiles: Long,
              completedAtMillis: Long,
            ) = error("not used")

            override fun abort(
              sessionId: io.xoboro.core.application.ScanSessionId,
              abortedAtMillis: Long,
            ) = error("not used")
          },
        currentTimeMillis = { 0 },
      )
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("malformed.sqlite"))).use { database ->
      val handler =
        ScanLibraryTaskHandler(
          libraries = JooqLibraryRepository(database),
          scanner = scanner,
        )

      assertFailsWith<IllegalArgumentException> {
        handler.handle(scanTask("""{"libraryId":"","deep":false}"""))
      }
      assertFailsWith<IllegalArgumentException> {
        handler.handle(scanTask("""{"libraryId":"library-1","deep":"false"}"""))
      }
    }
  }

  private fun scanTask(payload: String): DurableTask =
    DurableTask(
      id = "scan",
      type = ScanLibraryTaskHandler.TASK_TYPE,
      payloadJson = payload,
      availableAtMillis = 0,
    )

  companion object {
    private val LIBRARY_ID = LibraryId("library-1")
  }
}
