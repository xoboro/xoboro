package io.xoboro.server.tasks

import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReconciliationStore
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
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
      assertEquals(null, claim.task.groupId)
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
      val worker =
        DurableTaskWorker(
          queue = queue,
          handlers = listOf(ScanLibraryTaskHandler(libraries, scanner)),
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
