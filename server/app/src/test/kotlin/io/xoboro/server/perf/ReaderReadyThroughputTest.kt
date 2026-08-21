package io.xoboro.server.perf

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.ReaderReadyBookIndexer
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.sources.local.LocalSourceRandomAccess
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

@Tag("readerReadyThroughput")
class ReaderReadyThroughputTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `indexes production shaped manifests above ten items per second`() {
    val itemCount = System.getProperty("xoboro.readerReady.itemCount", "10000").toInt()
    require(itemCount >= 10_000) { "Reader-ready throughput must measure at least 10,000 items" }
    val libraryRoot = Files.createDirectories(tempDirectory.resolve("library"))
    val seriesRoot = Files.createDirectories(libraryRoot.resolve("Synthetic series"))
    val archive = archiveBytes()
    val books =
      (1..itemCount).map { index ->
        val path = seriesRoot.resolve(String.format(Locale.ROOT, "Book %06d.cbz", index))
        Files.write(path, archive)
        Book(
          id = BookId("book-$index"),
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic book $index",
          relativePath = "Synthetic series/${path.fileName}",
          sourceItemId = path.toUri().toString(),
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileSize = archive.size.toLong(),
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        )
      }

    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("reader-ready.sqlite"))).use { database ->
      val libraries = JooqLibraryRepository(database)
      libraries.insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", libraryRoot.toUri().toString()),
          settings = LibrarySettings(hashFiles = true, analyzeDimensions = true),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "Synthetic series",
          sourceItemId = seriesRoot.toUri().toString(),
          fileModifiedAtMillis = 1,
          bookCount = itemCount,
          createdAtMillis = 1,
        ),
      )
      val bookRepository = JooqBookRepository(database)
      bookRepository.insertAll(books)
      val media = JooqBookMediaRepository(database)
      val indexer =
        ReaderReadyBookIndexer(
          books = bookRepository,
          libraries = libraries,
          media = media,
          randomAccesses = listOf(LocalSourceRandomAccess()),
          fallback = { error("synthetic CBZ must stay on the reader-ready path") },
          currentTimeMillis = { 100 },
        )

      val elapsedNanos = measureNanoTime { books.forEach { indexer.execute(it.id) } }
      val indexedMedia = media.findAllByBookIds(books.map(Book::id))
      val readyCount = indexedMedia.count { it.status == MediaStatus.READY }.toLong()
      val indexedPages = indexedMedia.sumOf { it.pages.size.toLong() }
      val elapsedMillis = elapsedNanos / 1_000_000.0
      val itemsPerSecond = itemCount / (elapsedNanos / 1_000_000_000.0)

      assertEquals(itemCount.toLong(), readyCount)
      assertEquals(itemCount.toLong() * PAGES_PER_BOOK, indexedPages)
      println("xoboro.reader_ready.items=$itemCount")
      println("xoboro.reader_ready.pages=$indexedPages")
      println("xoboro.reader_ready.elapsed_ms=${String.format(Locale.ROOT, "%.1f", elapsedMillis)}")
      println(
        "xoboro.reader_ready.items_per_second=" +
          String.format(Locale.ROOT, "%.2f", itemsPerSecond),
      )
      assertTrue(
        itemsPerSecond >= TARGET_ITEMS_PER_SECOND,
        "Reader-ready throughput was %.2f items/s, expected at least %.2f"
          .format(Locale.ROOT, itemsPerSecond, TARGET_ITEMS_PER_SECOND),
      )
    }
  }

  private fun archiveBytes(): ByteArray =
    ByteArrayOutputStream().use { bytes ->
      ZipOutputStream(bytes).use { zip ->
        repeat(PAGES_PER_BOOK) { pageIndex ->
          zip.putNextEntry(ZipEntry(String.format(Locale.ROOT, "%03d.jpg", pageIndex + 1)))
          zip.write(byteArrayOf(pageIndex.toByte()))
          zip.closeEntry()
        }
      }
      bytes.toByteArray()
    }

  private companion object {
    const val PAGES_PER_BOOK = 33
    const val TARGET_ITEMS_PER_SECOND = 10.0
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
  }
}
