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
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

/**
 * What the aggregation sweep does when the write lock is *held*, rather than merely contended.
 *
 * [JooqBookMetadataAggregationRepositoryConcurrencyTest] covers the busy-snapshot race: many short
 * writers committing between a reader's snapshot and its upgrade. Its writers release the lock
 * immediately, so `busy_timeout` always wins eventually and the sweep completes.
 *
 * A library scan is the opposite shape. It holds the write lock for minutes at a time, far past any
 * `busy_timeout` worth configuring, and `SQLITE_BUSY` is then raised for real. Sweeping used to be
 * something every one of [JooqCatalogReadRepository]'s series read paths did, so an escaping
 * exception turned the whole library listing into a `500` for as long as the scan ran - observed
 * against a library of 145,105 archives, where `GET /api/v1/series` failed with
 * `DELETE FROM series_book_metadata_aggregation_dirty ... RETURNING series_id; [SQLITE_BUSY]`.
 *
 * Only a query ordering on the aggregation still sweeps, but absorbing contention matters no less
 * for it, and the background sweep that took over the rest would stop being scheduled if a tick
 * threw. The denormalized view is allowed to be a moment stale; nothing is allowed to fail over it.
 * And nothing is lost by deferring: the claim is the transaction's first statement, so a failure
 * rolls back with the dirty rows still in place for the next sweep.
 */
class JooqBookMetadataAggregationRepositoryContentionTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `serves a stale aggregation rather than failing the read while the write lock is held`() {
    val databasePath = tempDirectory.resolve("aggregation-locked.sqlite")
    val seriesId = SeriesId("locked-series")
    val bookId = BookId("locked-book")

    XoboroDatabase.open(DatabaseConfig(databasePath, busyTimeoutMillis = 50)).use { database ->
      val aggregations = JooqBookMetadataAggregationRepository(database)
      seed(database, seriesId, bookId)

      // The lock is taken for real from a second connection rather than simulated, because what is
      // under test is the driver's result code surviving jOOQ's wrapping - the same reason
      // JooqUserSessionRepositoryTest takes it this way.
      DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").use { holder ->
        holder.autoCommit = false
        // `library` carries no `mark_series_aggregation_dirty_*` trigger, so holding the lock here
        // does not itself dirty the series the assertions below depend on.
        holder.createStatement().use { it.executeUpdate("UPDATE library SET name = name") }
        try {
          // Both sweeps and the read, under the held lock. None may throw.
          aggregations.sweepSomeDirty()
          aggregations.refreshAllDirty()
          aggregations.findAllBySeriesIds(listOf(seriesId))
        } finally {
          holder.rollback()
        }
      }

      // Deferred, not lost: the same series rebuilds once the lock is gone, so the stale answer
      // above was "not yet" rather than "there is nothing to aggregate".
      aggregations.refreshAllDirty()
      val rebuilt = aggregations.findAllBySeriesIds(listOf(seriesId))
      assertEquals(setOf(seriesId), rebuilt.keys)
      assertEquals("Synthetic summary", assertNotNull(rebuilt[seriesId]).summary)
    }
  }

  private fun seed(
    database: XoboroDatabase,
    seriesId: SeriesId,
    bookId: BookId,
  ) {
    val libraryId = LibraryId("locked-library")
    JooqLibraryRepository(database).insert(
      Library(
        id = libraryId,
        name = "Locked library",
        root = SourceLocation("local", "file:///locked"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = seriesId,
        libraryId = libraryId,
        name = "Locked series",
        relativePath = "Locked series",
        sourceItemId = "file:///locked/series",
        fileModifiedAtMillis = 2,
        bookCount = 1,
        createdAtMillis = 1,
      ),
    )
    JooqBookRepository(database).insert(
      Book(
        id = bookId,
        libraryId = libraryId,
        seriesId = seriesId,
        name = "Locked issue.cbz",
        relativePath = "Locked series/Locked issue.cbz",
        sourceItemId = "file:///locked/series/issue.cbz",
        mediaKind = MediaKind.COMIC_ARCHIVE,
        fileModifiedAtMillis = 2,
        fileSize = 100,
        number = 1,
        createdAtMillis = 1,
      ),
    )
    JooqBookMetadataRepository(database).upsert(
      BookMetadata(
        bookId = bookId,
        title = "Locked issue",
        number = "1",
        numberSort = 1f,
        summary = "Synthetic summary",
        authors = listOf(Author(name = "Author", role = "writer")),
        createdAtMillis = 1,
      ),
    )
  }
}
