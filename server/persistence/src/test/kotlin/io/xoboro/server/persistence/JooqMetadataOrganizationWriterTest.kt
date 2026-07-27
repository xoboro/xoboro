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
import kotlin.test.Test
import kotlin.test.assertEquals
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

  private fun seedCatalog(database: XoboroDatabase): Fixture {
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
    val seriesIds = (1..3).map { SeriesId("series-$it") }
    val bookIds = (1..3).map { BookId("book-$it") }
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
}
