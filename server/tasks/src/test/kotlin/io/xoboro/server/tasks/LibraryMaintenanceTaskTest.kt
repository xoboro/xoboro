package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.EmptyTrashResult
import io.xoboro.core.application.LibraryTrashStore
import io.xoboro.core.application.TaskPriority
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LibraryMaintenanceTaskTest {
  @TempDir
  lateinit var tempDirectory: Path

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
