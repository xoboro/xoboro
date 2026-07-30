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
 * [JooqBookMetadataAggregationRepository.refreshDirty] (a transaction that reads
 * `series_book_metadata_aggregation_dirty` and then conditionally writes to it) and a concurrent,
 * independent writer touching `book_metadata` for the same series on another pooled connection.
 *
 * Before the fix, this transaction began as SQLite's default deferred (reader) transaction; if
 * another connection committed a write between this transaction's read and its own write attempt,
 * SQLite refused to silently upgrade the now-stale read snapshot to a writer and raised
 * SQLITE_BUSY_SNAPSHOT. Forcing the transaction to open with `BEGIN IMMEDIATE` (see
 * [JooqBookMetadataAggregationRepository.forceImmediateWriteLock]) eliminates the reader-then-writer
 * upgrade entirely, so the first test hammers both sides concurrently and asserts zero failures.
 * The second test pins that leaving a pooled connection's transaction mode set to IMMEDIATE
 * afterward - rather than resetting it - does not affect unrelated write-first repositories.
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

  /**
   * [JooqBookMetadataAggregationRepository.forceImmediateWriteLock] deliberately leaves a pooled
   * connection's SQLite transaction mode set to IMMEDIATE rather than resetting it, on the premise
   * that every *other* `database.transaction { }` call site in this persistence module starts with
   * a write as its first statement and is therefore unaffected either way. This test pins that
   * premise: force a single-connection pool into IMMEDIATE mode via refreshDirty, then exercise a
   * handful of unrelated write-first repositories on that same (only) connection and confirm they
   * still persist correctly.
   *
   * A `finally`-scoped reset was tried instead and rejected: it reintroduced the exact
   * SQLITE_BUSY_SNAPSHOT/SQLITE_BUSY failures this fix removes, at a much higher rate under load
   * than leaving the mode set (144 failures per 4000 calls at 8 readers + 4 writers, vs. 0).
   */
  @Test
  fun `leaving a connection in IMMEDIATE mode does not affect other write-first repositories`() {
    val databasePath = tempDirectory.resolve("aggregation-immediate-mode-leak.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath, maximumPoolSize = 1)).use { database ->
      val libraryRepository = JooqLibraryRepository(database)
      val seriesRepository = JooqSeriesRepository(database)
      val bookRepository = JooqBookRepository(database)
      val bookMetadataRepository = JooqBookMetadataRepository(database)
      val aggregations = JooqBookMetadataAggregationRepository(database)

      val libraryId = LibraryId("leak-library")
      val seriesId = SeriesId("leak-series")
      val bookId = BookId("leak-book-1")
      libraryRepository.insert(
        Library(
          id = libraryId,
          name = "Leak library",
          root = SourceLocation("local", "file:///leak"),
          createdAtMillis = 1,
        ),
      )
      seriesRepository.insert(
        Series(
          id = seriesId,
          libraryId = libraryId,
          name = "Leak series",
          relativePath = "Leak series",
          sourceItemId = "file:///leak/series",
          fileModifiedAtMillis = 2,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      )
      bookRepository.insert(
        Book(
          id = bookId,
          libraryId = libraryId,
          seriesId = seriesId,
          name = "Leak issue.cbz",
          relativePath = "Leak series/Leak issue.cbz",
          sourceItemId = "file:///leak/series/issue.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 2,
          fileSize = 100,
          number = 1,
          createdAtMillis = 1,
        ),
      )
      bookMetadataRepository.upsert(
        BookMetadata(
          bookId = bookId,
          title = "Leak issue",
          number = "1",
          numberSort = 1F,
          summary = "Synthetic summary",
          createdAtMillis = 1,
        ),
      )

      // With a single pooled connection, this leaves that one connection's transaction mode set
      // to IMMEDIATE and never resets it - exactly the state under test.
      aggregations.refreshDirty(listOf(seriesId))

      // A handful of unrelated write-first operations, all forced onto the same connection.
      val otherLibraryId = LibraryId("leak-library-2")
      val otherSeriesId = SeriesId("leak-series-2")
      val otherBookId = BookId("leak-book-2")
      libraryRepository.insert(
        Library(
          id = otherLibraryId,
          name = "Second leak library",
          root = SourceLocation("local", "file:///leak2"),
          createdAtMillis = 1,
        ),
      )
      seriesRepository.insert(
        Series(
          id = otherSeriesId,
          libraryId = otherLibraryId,
          name = "Second leak series",
          relativePath = "Second leak series",
          sourceItemId = "file:///leak2/series",
          fileModifiedAtMillis = 2,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      )
      bookRepository.insert(
        Book(
          id = otherBookId,
          libraryId = otherLibraryId,
          seriesId = otherSeriesId,
          name = "Second leak issue.cbz",
          relativePath = "Second leak series/Second leak issue.cbz",
          sourceItemId = "file:///leak2/series/issue.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 2,
          fileSize = 100,
          number = 1,
          createdAtMillis = 1,
        ),
      )
      bookMetadataRepository.upsert(
        BookMetadata(
          bookId = otherBookId,
          title = "Second leak issue",
          number = "1",
          numberSort = 1F,
          summary = "Second synthetic summary",
          createdAtMillis = 1,
        ),
      )
      seriesRepository.update(
        requireNotNull(seriesRepository.findByIdOrNull(otherSeriesId)).copy(bookCount = 2),
      )

      val persisted = assertNotNull(seriesRepository.findByIdOrNull(otherSeriesId))
      assertEquals(2, persisted.bookCount)
      assertNotNull(bookRepository.findByIdOrNull(otherBookId))
      assertEquals(
        "Second leak issue",
        bookMetadataRepository.findByBookIdOrNull(otherBookId)?.title,
      )
    }
  }

  private companion object {
    const val WRITER_MAX_ATTEMPTS = 20
  }
}
