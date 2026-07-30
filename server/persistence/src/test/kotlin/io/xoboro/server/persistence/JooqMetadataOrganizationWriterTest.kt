package io.xoboro.server.persistence

import io.xoboro.core.application.OrganizationEvent
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqMetadataOrganizationWriterTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `creates and extends ComicInfo organizations with sparse positions`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("metadata-organization.sqlite")))
      .use { database ->
        val fixture = seedCatalog(database)
        val events = mutableListOf<OrganizationEvent>()
        var id = 0
        var now = 10L
        val writer =
          JooqMetadataOrganizationWriter(
            database = database,
            collectionIdFactory = { "collection-${++id}" },
            readListIdFactory = { "read-list-${++id}" },
            currentTimeMillis = { now++ },
            eventPublisher = events::add,
          )

        writer.addBookToReadList("Synthetic order", fixture.bookIds[0], 10)
        writer.addBookToReadList("SYNTHETIC ORDER", fixture.bookIds[1], 5)
        writer.addBookToReadList("Synthetic order", fixture.bookIds[2], 5)
        writer.addBookToReadList("Synthetic order", fixture.bookIds[2], 99)

        val readList =
          requireNotNull(
            JooqReadListRepository(database)
              .findByNameIgnoreCaseOrNull("synthetic order"),
          )
        assertEquals(
          listOf(fixture.bookIds[1], fixture.bookIds[0], fixture.bookIds[2]),
          readList.bookIds,
        )
        assertEquals(
          listOf(
            OrganizationEvent.ReadListAdded::class,
            OrganizationEvent.ReadListUpdated::class,
            OrganizationEvent.ReadListUpdated::class,
          ),
          events.map { it::class },
        )

        events.clear()
        writer.addSeriesToCollection("Synthetic shelf", fixture.seriesIds[0])
        writer.addSeriesToCollection("SYNTHETIC SHELF", fixture.seriesIds[1])
        writer.addSeriesToCollection("Synthetic shelf", fixture.seriesIds[1])

        val collection =
          requireNotNull(
            JooqSeriesCollectionRepository(database)
              .findByNameIgnoreCaseOrNull("synthetic shelf"),
          )
        assertEquals(fixture.seriesIds.take(2), collection.seriesIds)
        assertEquals(
          listOf(
            OrganizationEvent.CollectionAdded::class,
            OrganizationEvent.CollectionUpdated::class,
          ),
          events.map { it::class },
        )
      }
  }

  /**
   * Both entry points used to look the name up and then write inside one transaction, so a
   * concurrent commit against the read snapshot they had already taken made the later insert fail
   * to acquire the write lock - `SQLITE_BUSY_SNAPSHOT`, which `busy_timeout` does not wait out
   * because the failure is a mid-transaction lock upgrade rather than a fresh transaction's first
   * write. Book analysis drives this concurrently for real.
   *
   * Each thread owns its own collection and read list, so the threads do not contend with each
   * other: the competitor is the background writer committing unrelated rows.
   */
  @Test
  fun `organizes metadata while another connection commits`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("organization-concurrency.sqlite")))
      .use { database ->
        val fixture = seedCatalog(database, itemCount = THREADS * ITEMS_PER_THREAD)
        val identifiers = AtomicInteger()
        val writer =
          JooqMetadataOrganizationWriter(
            database = database,
            collectionIdFactory = { "collection-${identifiers.incrementAndGet()}" },
            readListIdFactory = { "read-list-${identifiers.incrementAndGet()}" },
            currentTimeMillis = { CONCURRENT_TIMESTAMP },
          )
        val settings = JooqServerSettingRepository(database)
        val keepWriting = AtomicBoolean(true)
        val failures = mutableListOf<Throwable>()
        val executor = Executors.newFixedThreadPool(THREADS + 1)
        try {
          val competingWriter =
            executor.submit {
              var counter = 0L
              while (keepWriting.get()) {
                settings.put("SYNTHETIC_COMPETING_WRITER", "value-${counter++}")
              }
            }
          (0 until THREADS)
            .map { thread ->
              executor.submit {
                repeat(ITEMS_PER_THREAD) { index ->
                  val item = thread * ITEMS_PER_THREAD + index
                  writer.addBookToReadList("Synthetic list $thread", fixture.bookIds[item], null)
                  writer.addSeriesToCollection("Synthetic shelf $thread", fixture.seriesIds[item])
                }
              }
            }.forEach { worker ->
              runCatching { worker.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .onFailure { failure -> failures += failure }
            }
          keepWriting.set(false)
          competingWriter.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
          keepWriting.set(false)
          executor.shutdownNow()
        }
        assertTrue(failures.isEmpty(), "Concurrent organization failed: ${failures.map { it.message }}")
        val readLists = JooqReadListRepository(database)
        val seriesCollections = JooqSeriesCollectionRepository(database)
        (0 until THREADS).forEach { thread ->
          assertEquals(
            ITEMS_PER_THREAD,
            requireNotNull(readLists.findByNameIgnoreCaseOrNull("synthetic list $thread")).bookIds.size,
          )
          assertEquals(
            ITEMS_PER_THREAD,
            requireNotNull(
              seriesCollections.findByNameIgnoreCaseOrNull("synthetic shelf $thread"),
            ).seriesIds.size,
          )
        }
      }
  }

  private fun seedCatalog(
    database: XoboroDatabase,
    itemCount: Int = 3,
  ): Fixture {
    val libraryId = LibraryId("library-1")
    JooqLibraryRepository(database).insert(
      Library(
        id = libraryId,
        name = "Synthetic library",
        root = SourceLocation("local", "file:///synthetic"),
        createdAtMillis = 1,
      ),
    )
    val seriesRepository = JooqSeriesRepository(database)
    val bookRepository = JooqBookRepository(database)
    val seriesIds = (1..itemCount).map { SeriesId("series-$it") }
    val bookIds = (1..itemCount).map { BookId("book-$it") }
    seriesIds.forEachIndexed { index, seriesId ->
      seriesRepository.insert(
        Series(
          id = seriesId,
          libraryId = libraryId,
          name = "Synthetic series ${index + 1}",
          relativePath = "series-${index + 1}",
          sourceItemId = "file:///synthetic/series-${index + 1}",
          fileModifiedAtMillis = 1,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      )
      bookRepository.insert(
        Book(
          id = bookIds[index],
          libraryId = libraryId,
          seriesId = seriesId,
          name = "Synthetic book ${index + 1}",
          relativePath = "series-${index + 1}/book.cbz",
          sourceItemId = "file:///synthetic/series-${index + 1}/book.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
    }
    return Fixture(seriesIds, bookIds)
  }

  private data class Fixture(
    val seriesIds: List<SeriesId>,
    val bookIds: List<BookId>,
  )

  private companion object {
    const val THREADS = 4
    const val ITEMS_PER_THREAD = 25
    const val CONCURRENCY_TIMEOUT_SECONDS = 120L

    /** Fixed so the timestamp source stays thread-safe; timestamps are not what this test checks. */
    const val CONCURRENT_TIMESTAMP = 1_000L
  }
}
