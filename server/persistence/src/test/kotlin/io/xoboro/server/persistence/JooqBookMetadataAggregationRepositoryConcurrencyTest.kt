package io.xoboro.server.persistence

import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

/**
 * Regression coverage for the SQLITE_BUSY_SNAPSHOT race between
 * [JooqBookMetadataAggregationRepository.refreshDirty] and a concurrent, independent writer touching
 * `book_metadata` for the same series on another pooled connection.
 *
 * Before the fix, refreshDirty read `series_book_metadata_aggregation_dirty` and only then wrote to
 * it, so its transaction began as SQLite's default deferred (reader) transaction. If another
 * connection committed a write in between, SQLite refused to silently upgrade the now-stale read
 * snapshot to a writer and raised SQLITE_BUSY_SNAPSHOT - and `busy_timeout` does not wait out a
 * mid-transaction lock upgrade the way it waits for a fresh transaction's first write.
 *
 * refreshDirty now claims its work by *deleting* the dirty rows and reading their ids from
 * `RETURNING`, so the transaction's first statement is a write and there is no upgrade to fail.
 * This test hammers both sides concurrently and asserts zero failures.
 */
class JooqBookMetadataAggregationRepositoryConcurrencyTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `refreshDirty survives a concurrent writer without a busy-snapshot failure`() {
    val databasePath = tempDirectory.resolve("aggregation-race.sqlite")
    val seriesId = SeriesId("race-series")
    val bookIds = (1..40).map { BookId("race-book-$it") }

    XoboroDatabase.open(DatabaseConfig(databasePath, maximumPoolSize = 8)).use { database ->
      val libraryRepository = JooqLibraryRepository(database)
      val seriesRepository = JooqSeriesRepository(database)
      val bookRepository = JooqBookRepository(database)
      val bookMetadataRepository = JooqBookMetadataRepository(database)
      val aggregations = JooqBookMetadataAggregationRepository(database)

      val libraryId = LibraryId("race-library")
      libraryRepository.insert(
        Library(
          id = libraryId,
          name = "Race library",
          root = SourceLocation("local", "file:///race"),
          createdAtMillis = 1,
        ),
      )
      seriesRepository.insert(
        Series(
          id = seriesId,
          libraryId = libraryId,
          name = "Race series",
          relativePath = "Race series",
          sourceItemId = "file:///race/series",
          fileModifiedAtMillis = 2,
          bookCount = bookIds.size,
          createdAtMillis = 1,
        ),
      )
      bookIds.forEachIndexed { index, bookId ->
        bookRepository.insert(
          Book(
            id = bookId,
            libraryId = libraryId,
            seriesId = seriesId,
            name = "Race issue $index.cbz",
            relativePath = "Race series/Race issue $index.cbz",
            sourceItemId = "file:///race/series/issue-$index.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 2,
            fileSize = 100,
            number = index,
            createdAtMillis = 1,
          ),
        )
        bookMetadataRepository.upsert(
          BookMetadata(
            bookId = bookId,
            title = "Race issue $index",
            number = index.toString(),
            numberSort = index.toFloat(),
            summary = "Synthetic summary $index",
            authors = listOf(Author(name = "Author $index", role = "writer")),
            createdAtMillis = 1,
          ),
        )
      }

      val readerThreads = 8
      val writerThreads = 4
      val iterationsPerReader = 500
      val failures = AtomicInteger(0)
      val stopWriting = CountDownLatch(1)
      val executor = Executors.newFixedThreadPool(readerThreads + writerThreads)
      val failureMessages = CopyOnWriteArrayList<String>()

      // A durable-task-worker stand-in: keeps marking the series dirty by writing book_metadata
      // on an independent connection, concurrently with the readers below. Retries its own
      // ordinary lock-contention busy errors (not the failure mode under test here) the same way
      // the real durable task worker retries a failed attempt.
      val writers =
        (1..writerThreads).map { writerIndex ->
          executor.submit {
            var counter = 0
            while (stopWriting.count > 0) {
              val bookId = bookIds[counter % bookIds.size]
              counter++
              val metadata =
                BookMetadata(
                  bookId = bookId,
                  title = "Race issue",
                  number = counter.toString(),
                  numberSort = counter.toFloat(),
                  summary = "Synthetic summary $writerIndex-$counter",
                  createdAtMillis = 1,
                )
              var attempt = 0
              while (true) {
                val outcome = runCatching { bookMetadataRepository.upsert(metadata) }
                if (outcome.isSuccess || ++attempt >= WRITER_MAX_ATTEMPTS) break
              }
            }
          }
        }

      // A GET /series{,/{id}}-style read path stand-in: refreshes the same series concurrently.
      val testStart = System.currentTimeMillis()
      val readers =
        (1..readerThreads).map {
          executor.submit {
            repeat(iterationsPerReader) { iteration ->
              val callStart = System.currentTimeMillis()
              runCatching { aggregations.refreshDirty(listOf(seriesId)) }
                .onFailure { failure ->
                  failures.incrementAndGet()
                  val now = System.currentTimeMillis()
                  failureMessages.add(
                    "iteration=$iteration callElapsedMs=${now - callStart} " +
                      "totalElapsedMs=${now - testStart}: " +
                      generateSequence(failure) { it.cause }.joinToString(" <- ") { it.toString() },
                  )
                }
            }
          }
        }

      readers.forEach { it.get(120, TimeUnit.SECONDS) }
      stopWriting.countDown()
      writers.forEach { it.get(30, TimeUnit.SECONDS) }
      executor.shutdown()

      assertEquals(0, failures.get(), "Unexpected refreshDirty failures: $failureMessages")
    }
  }

  private companion object {
    const val WRITER_MAX_ATTEMPTS = 20
  }
}
