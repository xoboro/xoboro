package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.AnalyzeBook
import io.xoboro.server.media.ZipMediaAnalyzer
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.sources.local.LocalSourceMediaAccess
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class AnalyzeBookWorkerIntegrationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `executes a reconciler analysis task through the durable worker`() {
    val archive = createArchive()
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("worker.sqlite"))).use { database ->
      val libraries = JooqLibraryRepository(database)
      val books = JooqBookRepository(database)
      val media = JooqBookMediaRepository(database)
      val queue = JooqDurableTaskQueue(database)
      libraries.insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", tempDirectory.toUri().toString()),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "series",
          sourceItemId = archive.parent.toUri().toString(),
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      books.insert(
        Book(
          id = BOOK_ID,
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic book",
          relativePath = "series/book.cbz",
          sourceItemId = archive.toUri().toString(),
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      queue.enqueue(
        DurableTask(
          id = "ANALYZE_BOOK_${BOOK_ID.value}",
          type = AnalyzeBookTaskHandler.TASK_TYPE,
          payloadJson = """{"bookId":"${BOOK_ID.value}"}""",
          groupId = SERIES_ID.value,
          availableAtMillis = 10,
        ),
        nowMillis = 10,
      )
      val analyzeBook =
        AnalyzeBook(
          books = books,
          libraries = libraries,
          accesses = listOf(LocalSourceMediaAccess()),
          media = media,
          zipAnalyzer = ZipMediaAnalyzer(),
          currentTimeMillis = { 100 },
        )
      val worker =
        DurableTaskWorker(
          queue = queue,
          handlers = listOf(AnalyzeBookTaskHandler(analyzeBook::execute)),
          heartbeat = LeaseHeartbeat { _, _ -> AutoCloseable {} },
          currentTimeMillis = { 100 },
          leaseTokenFactory = { "lease-1" },
        )

      assertEquals(
        TaskRunResult.Completed("ANALYZE_BOOK_${BOOK_ID.value}"),
        worker.runOnce("worker-1"),
      )
      assertEquals(MediaStatus.READY, media.findByBookIdOrNull(BOOK_ID)?.status)
      assertEquals(1, media.findByBookIdOrNull(BOOK_ID)?.pageCount)
      assertEquals(TaskCounts(0, 0, 0), queue.counts())
    }
  }

  private fun createArchive(): Path {
    val series = Files.createDirectories(tempDirectory.resolve("series"))
    val path = series.resolve("book.cbz")
    val page =
      java.io.ByteArrayOutputStream().use { bytes ->
        ImageIO.write(BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB), "png", bytes)
        bytes.toByteArray()
      }
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      output.putNextEntry(ZipEntry("001.png"))
      output.write(page)
      output.closeEntry()
    }
    return path
  }

  companion object {
    private val LIBRARY_ID = LibraryId("library-1")
    private val SERIES_ID = SeriesId("series-1")
    private val BOOK_ID = BookId("book-1")
  }
}
