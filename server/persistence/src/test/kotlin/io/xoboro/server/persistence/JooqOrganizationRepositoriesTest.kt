package io.xoboro.server.persistence

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqOrganizationRepositoriesTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `round trips ordered collections and read lists`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("organization.sqlite"))).use {
        database ->
      val fixture = seedCatalog(database)
      val collections = JooqSeriesCollectionRepository(database)
      val readLists = JooqReadListRepository(database)
      val collection =
        SeriesCollection(
          id = CollectionId("collection-1"),
          name = "Synthetic collection",
          ordered = true,
          seriesIds = fixture.seriesIds.reversed(),
          createdAtMillis = 10,
        )
      val readList =
        ReadList(
          id = ReadListId("read-list-1"),
          name = "Synthetic reading order",
          summary = "Synthetic summary",
          ordered = true,
          bookIds = fixture.bookIds.reversed(),
          createdAtMillis = 10,
        )

      collections.insert(collection)
      readLists.insert(readList)

      assertEquals(collection, collections.findByIdOrNull(collection.id))
      assertEquals(readList, readLists.findByIdOrNull(readList.id))
      assertEquals(listOf(collection), collections.findAllBySeriesId(fixture.seriesIds.first()))
      assertEquals(listOf(readList), readLists.findAllByBookId(fixture.bookIds.first()))
      assertEquals(collection, collections.findByNameIgnoreCaseOrNull("SYNTHETIC COLLECTION"))
      assertEquals(readList, readLists.findByNameIgnoreCaseOrNull("SYNTHETIC READING ORDER"))
    }
  }

  @Test
  fun `rolls back member replacement when a foreign key is invalid`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("rollback.sqlite"))).use {
        database ->
      val fixture = seedCatalog(database)
      val collections = JooqSeriesCollectionRepository(database)
      val original =
        SeriesCollection(
          id = CollectionId("collection-1"),
          name = "Synthetic collection",
          ordered = true,
          seriesIds = fixture.seriesIds,
          createdAtMillis = 10,
        )
      collections.insert(original)

      assertFails {
        collections.update(
          original.copy(
            seriesIds = listOf(SeriesId("missing-series")),
            updatedAtMillis = 20,
          ),
        )
      }

      assertEquals(original, collections.findByIdOrNull(original.id))
    }
  }

  @Test
  fun `cascades memberships while retaining their organizations`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("cascade.sqlite"))).use {
        database ->
      val fixture = seedCatalog(database)
      val collections = JooqSeriesCollectionRepository(database)
      val readLists = JooqReadListRepository(database)
      val collection =
        SeriesCollection(
          id = CollectionId("collection-1"),
          name = "Synthetic collection",
          ordered = false,
          seriesIds = listOf(fixture.seriesIds.first()),
          createdAtMillis = 10,
        )
      val readList =
        ReadList(
          id = ReadListId("read-list-1"),
          name = "Synthetic reading order",
          bookIds = listOf(fixture.bookIds.first()),
          createdAtMillis = 10,
        )
      collections.insert(collection)
      readLists.insert(readList)

      JooqBookRepository(database).delete(fixture.bookIds.first())
      JooqSeriesRepository(database).delete(fixture.seriesIds.first())

      assertEquals(emptyList(), collections.findByIdOrNull(collection.id)?.seriesIds)
      assertEquals(emptyList(), readLists.findByIdOrNull(readList.id)?.bookIds)
      collections.delete(collection.id)
      readLists.delete(readList.id)
      assertNull(collections.findByIdOrNull(collection.id))
      assertNull(readLists.findByIdOrNull(readList.id))
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
    val seriesIds = listOf(SeriesId("series-1"), SeriesId("series-2"))
    val bookIds = listOf(BookId("book-1"), BookId("book-2"))
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
