package io.xoboro.server

import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.ScanInterval
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.tasks.AnalyzeBookTaskHandler
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class ReaderReadyTaskPriorityTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `finishes pending analysis before post-analysis metadata`() {
    val databasePath = seedCatalog("metadata")

    XoboroRuntime.open(runtimeConfig(databasePath)).use { runtime ->
      await("first media item's metadata refresh") {
        runtime.book(BOOK_1_ID)?.metadata?.title == IMPORTED_TITLE
      }

      assertEquals(
        MediaStatus.READY,
        runtime.book(BOOK_2_ID)?.media?.status,
        "post-analysis metadata must not delay the next media item becoming readable",
      )
    }
  }

  @Test
  fun `finishes pending analysis before post-analysis cover generation`() {
    val databasePath = seedCatalog("cover")

    XoboroRuntime.open(runtimeConfig(databasePath)).use { runtime ->
      await("first media item's generated covers") {
        runtime.hasGeneratedArtwork(ArtworkOwnerKind.MEDIA_ITEM, BOOK_1_ID.value) &&
          runtime.hasGeneratedArtwork(ArtworkOwnerKind.SERIES, SERIES_ID.value)
      }

      assertEquals(
        MediaStatus.READY,
        runtime.book(BOOK_2_ID)?.media?.status,
        "post-analysis cover generation must not delay the next media item becoming readable",
      )
    }
  }

  private fun seedCatalog(testName: String): Path {
    val libraryRoot = Files.createDirectories(tempDirectory.resolve("$testName-library"))
    val seriesRoot = Files.createDirectories(libraryRoot.resolve("series"))
    val firstArchive = createArchive(seriesRoot.resolve("book-1.cbz"), pageCount = 1, comicInfo = true)
    val secondArchive = createArchive(seriesRoot.resolve("book-2.cbz"), pageCount = 200)
    val databasePath = tempDirectory.resolve("reader-ready-$testName.sqlite")

    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", libraryRoot.toUri().toString()),
          settings =
            LibrarySettings(
              importBarcodeIsbn = false,
              scanInterval = ScanInterval.DISABLED,
              hashFiles = false,
            ),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "series",
          sourceItemId = seriesRoot.toUri().toString(),
          fileModifiedAtMillis = 1,
          bookCount = 2,
          createdAtMillis = 1,
        ),
      )
      val books = JooqBookRepository(database)
      books.insert(book(BOOK_1_ID, firstArchive, createdAtMillis = 1))
      books.insert(book(BOOK_2_ID, secondArchive, createdAtMillis = 2))

      val queue = JooqDurableTaskQueue(database)
      enqueueAnalysis(queue, BOOK_1_ID, nowMillis = 10)
      enqueueAnalysis(queue, BOOK_2_ID, nowMillis = 11)
    }
    return databasePath
  }

  private fun createArchive(
    path: Path,
    pageCount: Int,
    comicInfo: Boolean = false,
  ): Path {
    val page =
      ByteArrayOutputStream().use { bytes ->
        ImageIO.write(BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB), "png", bytes)
        bytes.toByteArray()
      }
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      if (comicInfo) {
        output.putNextEntry(ZipEntry("ComicInfo.xml"))
        output.write("<ComicInfo><Title>$IMPORTED_TITLE</Title></ComicInfo>".encodeToByteArray())
        output.closeEntry()
      }
      repeat(pageCount) { index ->
        output.putNextEntry(ZipEntry("%03d.png".format(index + 1)))
        output.write(page)
        output.closeEntry()
      }
    }
    return path
  }

  private fun book(
    id: BookId,
    archive: Path,
    createdAtMillis: Long,
  ): Book =
    Book(
      id = id,
      libraryId = LIBRARY_ID,
      seriesId = SERIES_ID,
      name = id.value,
      relativePath = "series/${archive.fileName}",
      sourceItemId = archive.toUri().toString(),
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileSize = Files.size(archive),
      fileModifiedAtMillis = 1,
      createdAtMillis = createdAtMillis,
    )

  private fun enqueueAnalysis(
    queue: JooqDurableTaskQueue,
    bookId: BookId,
    nowMillis: Long,
  ) {
    queue.enqueue(
      DurableTask(
        id = "ANALYZE_BOOK_${bookId.value}",
        type = AnalyzeBookTaskHandler.TASK_TYPE,
        payloadJson = """{"bookId":"${bookId.value}"}""",
        priority = TaskPriority.DEFAULT,
        groupId = SERIES_ID.value,
        availableAtMillis = nowMillis,
      ),
      nowMillis = nowMillis,
    )
  }

  private fun runtimeConfig(databasePath: Path): ServerConfig =
    ServerConfig(
      port = 25_600,
      databasePath = databasePath,
      workerCount = 1,
      taskPollMillis = 10,
      taskFailurePollMillis = 10,
      taskLeaseMillis = 5_000,
      shutdownTimeoutMillis = 2_000,
    )

  private fun XoboroRuntime.book(id: BookId) =
    catalogReadRepository.findBookByIdOrNull(id, CatalogAccess())

  private fun XoboroRuntime.hasGeneratedArtwork(
    ownerKind: ArtworkOwnerKind,
    ownerId: String,
  ): Boolean =
    artworkLifecycle.findAll(ArtworkOwner(ownerKind, ownerId)).any { it.type == ArtworkType.GENERATED }

  private fun await(
    description: String,
    condition: () -> Boolean,
  ) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
      if (condition()) return
      Thread.sleep(5)
    }
    error("Timed out waiting for $description")
  }

  private companion object {
    const val IMPORTED_TITLE = "Imported synthetic title"
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_1_ID = BookId("book-1")
    val BOOK_2_ID = BookId("book-2")
  }
}
