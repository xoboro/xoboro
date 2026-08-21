package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
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
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class EnrichBookTaskEmitterTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `queues deterministic enrichment behind reader-ready work`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("enrichment.sqlite"))).use { database ->
      val books = insertBook(database)
      val queue = JooqDurableTaskQueue(database)
      val emitter = EnrichBookTaskEmitter(books, queue, currentTimeMillis = { 10 })

      assertTrue(emitter.enrich(BOOK_ID))
      queue.enqueue(
        DurableTask(
          id = "ANALYZE_BOOK_second",
          type = AnalyzeBookTaskHandler.TASK_TYPE,
          payloadJson = """{"bookId":"second"}""",
          priority = TaskPriority.HIGH,
          availableAtMillis = 10,
        ),
        nowMillis = 10,
      )

      val first = requireNotNull(queue.claimNext("worker", "lease-1", 10, 1_000))
      assertEquals("ANALYZE_BOOK_second", first.task.id)
      assertTrue(queue.complete(first.task.id, first.leaseToken))

      val second = requireNotNull(queue.claimNext("worker", "lease-2", 10, 1_000))
      assertEquals("ENRICH_BOOK_${BOOK_ID.value}", second.task.id)
      assertEquals(EnrichBookTaskHandler.TASK_TYPE, second.task.type)
      assertEquals(TaskPriority.LOW, second.task.priority)
      assertEquals(SERIES_ID.value, second.task.groupId)
    }
  }

  private fun insertBook(database: XoboroDatabase): JooqBookRepository {
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
        createdAtMillis = 1,
      ),
    )
    return JooqBookRepository(database).also { books ->
      books.insert(
        Book(
          id = BOOK_ID,
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic book",
          relativePath = "series/book.cbz",
          sourceItemId = "file:///synthetic/series/book.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
    }
  }

  private companion object {
    val BOOK_ID = BookId("book-1")
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
  }
}
