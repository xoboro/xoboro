package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogMutationKind
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
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * What a sweep *says* it did, which is the half nobody could read before.
 *
 * Authors and tags on a series come out of this aggregation, and it is rebuilt in the background.
 * Until it runs, the series reads as having none - so a screen loaded in that window shows a work
 * with no author while the route it asked would now answer with two. That was measured against a
 * running server, and the reason nothing could be done about it was that the sweep answered a bare
 * boolean: whoever wanted to announce the change had no idea what had changed.
 *
 * The library id is asserted alongside the series id because it is not decoration. A native event
 * is delivered by comparing `libraryId` against what a subscriber may see, so an event without one
 * reaches nobody.
 */
class JooqBookMetadataAggregationSweepReportTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `reports the series it rebuilt, with the library each belongs to`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("sweep-report.sqlite"))).use { database ->
      val aggregations = JooqBookMetadataAggregationRepository(database)
      seed(database, SeriesId("series-a"), BookId("book-a"))
      seed(database, SeriesId("series-b"), BookId("book-b"))

      val swept = aggregations.sweepSomeDirty()

      assertEquals(
        setOf(SeriesId("series-a"), SeriesId("series-b")),
        swept.rebuilt.map { it.seriesId }.toSet(),
      )
      assertEquals(
        setOf(LibraryId("library-series-a"), LibraryId("library-series-b")),
        swept.rebuilt.map { it.libraryId }.toSet(),
      )
      // A rebuild is an update. A sweep neither creates nor deletes a series, and a subscriber
      // acting on "added" would insert a second card for a series already on screen.
      assertTrue(swept.rebuilt.all { it.kind == CatalogMutationKind.UPDATED })
      // Two series is not a full batch, so there is nothing left for the next tick to take.
      assertEquals(false, swept.moreRemaining)
    }
  }

  /** Nothing dirty is not the same as something rebuilt: an empty sweep must announce nothing. */
  @Test
  fun `reports nothing when there was nothing to rebuild`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("sweep-empty.sqlite"))).use { database ->
      val aggregations = JooqBookMetadataAggregationRepository(database)
      seed(database, SeriesId("series-a"), BookId("book-a"))
      aggregations.refreshAllDirty()

      val swept = aggregations.sweepSomeDirty()

      assertEquals(emptyList(), swept.rebuilt)
      assertEquals(false, swept.moreRemaining)
    }
  }

  /**
   * Locked out is "not yet", not "nothing".
   *
   * A scan holds the write lock for minutes, far past any `busy_timeout` worth configuring. The
   * sweep has always been allowed to answer that it could not run; what must not happen is that it
   * answers it with an empty rebuild *and* claims the backlog is drained, because then the tick
   * that could have caught up never happens.
   */
  @Test
  fun `reports more remaining and rebuilds nothing while the write lock is held`() {
    val databasePath = tempDirectory.resolve("sweep-locked.sqlite")
    XoboroDatabase.open(DatabaseConfig(databasePath, busyTimeoutMillis = 50)).use { database ->
      val aggregations = JooqBookMetadataAggregationRepository(database)
      seed(database, SeriesId("series-a"), BookId("book-a"))

      DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").use { holder ->
        holder.autoCommit = false
        holder.createStatement().use { it.executeUpdate("UPDATE library SET name = name") }
        try {
          val swept = aggregations.sweepSomeDirty()

          assertEquals(emptyList(), swept.rebuilt)
          assertEquals(true, swept.moreRemaining)
        } finally {
          holder.rollback()
        }
      }
    }
  }

  private fun seed(
    database: XoboroDatabase,
    seriesId: SeriesId,
    bookId: BookId,
  ) {
    val libraryId = LibraryId("library-${seriesId.value}")
    JooqLibraryRepository(database).insert(
      Library(
        id = libraryId,
        name = "Library ${seriesId.value}",
        root = SourceLocation("local", "file:///${seriesId.value}"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = seriesId,
        libraryId = libraryId,
        name = "Series ${seriesId.value}",
        relativePath = seriesId.value,
        sourceItemId = "file:///${seriesId.value}/series",
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
        name = "${bookId.value}.cbz",
        relativePath = "${seriesId.value}/${bookId.value}.cbz",
        sourceItemId = "file:///${seriesId.value}/${bookId.value}.cbz",
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
        title = "Issue of ${seriesId.value}",
        number = "1",
        numberSort = 1f,
        summary = "Synthetic summary",
        authors = listOf(Author(name = "Author", role = "writer")),
        createdAtMillis = 1,
      ),
    )
  }
}
