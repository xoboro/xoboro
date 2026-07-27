package io.xoboro.server.tasks

import io.xoboro.core.application.BookImportCommand
import io.xoboro.core.application.CatalogImportEvent
import io.xoboro.core.application.SourceCopyMode
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.HistoricalEventPageRequest
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqHistoricalEventRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.sources.local.LocalSourceMutationAccess
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CatalogFileLifecycleTaskTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `emits durable import and target deletion tasks`() {
    withCatalog("emission") { database, _, _ ->
      val queue = JooqDurableTaskQueue(database)
      var taskSequence = 0
      val requester =
        DurableCatalogFileLifecycleRequester(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          queue = queue,
          taskIdFactory = { "task-${++taskSequence}" },
          currentTimeMillis = { 100 },
        )

      assertEquals(
        1,
        requester.importBooks(
          books =
            listOf(
              BookImportCommand(
                sourceFile = "/synthetic/source.cbz",
                seriesId = SERIES_ID,
                destinationName = "destination.cbz",
              ),
            ),
          copyMode = SourceCopyMode.COPY,
        ),
      )
      assertTrue(requester.deleteBook(BOOK_ID))
      assertTrue(requester.deleteSeries(SERIES_ID))
      assertEquals(3, queue.counts().pending)

      val claimed =
        buildList {
          repeat(3) { index ->
            val lease = "lease-$index"
            val task = requireNotNull(queue.claimNext("worker", lease, 100, 1_000))
            add(task.task)
            assertTrue(queue.complete(task.task.id, lease))
          }
        }
      val import = claimed.single { it.type == ImportBookTaskHandler.TASK_TYPE }
      assertTrue(import.payloadJson.contains("\"copyMode\":\"COPY\""))
      assertEquals(SERIES_ID.value, import.groupId)
    }
  }

  @Test
  fun `imports and deletes local files then schedules reconciliation`() {
    withCatalog("execution") { database, root, seriesDirectory ->
      val queue = JooqDurableTaskQueue(database)
      val scanEmitter = ScanLibraryTaskEmitter(queue) { 200 }
      val history = JooqHistoricalEventRepository(database)
      var eventSequence = 0
      val importEvents = mutableListOf<CatalogImportEvent>()
      val lifecycle =
        CatalogSourceFileLifecycle(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          libraries = JooqLibraryRepository(database),
          mutations = listOf(LocalSourceMutationAccess()),
          scanEmitter = scanEmitter,
          history = history,
          historyIdFactory = { "event-${++eventSequence}" },
          currentTimeMillis = { 300 + eventSequence.toLong() },
          importEventPublisher = importEvents::add,
        )
      val importedSource = Files.writeString(tempDirectory.resolve("import.cbz"), "imported")

      lifecycle.importBook(
        BookImportCommand(
          sourceFile = importedSource.toString(),
          seriesId = SERIES_ID,
          destinationName = "imported.cbz",
        ),
        SourceCopyMode.COPY,
      )
      assertEquals("imported", Files.readString(seriesDirectory.resolve("imported.cbz")))
      assertEquals(1, queue.counts().pending)

      val existing = root.resolve("Synthetic series/existing.cbz")
      assertTrue(Files.exists(existing))
      lifecycle.deleteBook(BOOK_ID)
      assertTrue(!Files.exists(existing))
      assertEquals(1, queue.counts().pending)
      val events =
        history.findAll(
          HistoricalEventPageRequest(
            unpaged = true,
          ),
        ).content
      assertEquals(listOf("BookFileDeleted", "BookImported"), events.map { it.type })
      assertEquals(BOOK_ID, events.first().bookId)
      assertEquals(SERIES_ID, events.first().seriesId)
      assertEquals("No", events.last().properties["upgrade"])
      assertEquals(
        CatalogImportEvent(
          bookId = null,
          sourceFile = importedSource.toString(),
          success = true,
        ),
        importEvents.single(),
      )

      val missingSource = tempDirectory.resolve("missing.cbz")
      assertFailsWith<Exception> {
        lifecycle.importBook(
          BookImportCommand(
            sourceFile = missingSource.toString(),
            seriesId = SERIES_ID,
          ),
          SourceCopyMode.COPY,
        )
      }
      assertEquals(false, importEvents.last().success)
      assertEquals(missingSource.toString(), importEvents.last().sourceFile)
    }
  }

  private fun withCatalog(
    name: String,
    block: (XoboroDatabase, Path, Path) -> Unit,
  ) {
    val root = Files.createDirectories(tempDirectory.resolve("$name-library"))
    val seriesDirectory = Files.createDirectories(root.resolve("Synthetic series"))
    val existing = Files.writeString(seriesDirectory.resolve("existing.cbz"), "existing")
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$name.sqlite"))).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", root.toUri().toString()),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "Synthetic series",
          sourceItemId = seriesDirectory.toUri().toString(),
          fileModifiedAtMillis = 1,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      )
      JooqBookRepository(database).insert(
        Book(
          id = BOOK_ID,
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic existing book",
          relativePath = "Synthetic series/existing.cbz",
          sourceItemId = existing.toUri().toString(),
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          fileSize = Files.size(existing),
          createdAtMillis = 1,
        ),
      )
      block(database, root, seriesDirectory)
    }
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
  }
}
