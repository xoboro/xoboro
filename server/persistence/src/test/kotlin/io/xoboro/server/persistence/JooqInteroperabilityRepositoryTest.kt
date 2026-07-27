package io.xoboro.server.persistence

import io.xoboro.core.application.ReadListImportBookRequest
import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.HistoricalEvent
import io.xoboro.core.domain.HistoricalEventPageRequest
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SortDirection
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.SyncPoint
import io.xoboro.core.domain.SyncPointId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqInteroperabilityRepositoryTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `history persists properties and stable paging across restart`() {
    val path = temporaryDirectory.resolve("history.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val history = JooqHistoricalEventRepository(database)
      history.insert(
        HistoricalEvent(
          id = "event-1",
          type = "BookImported",
          timestampMillis = 10,
          bookId = BOOK_ID,
          seriesId = SERIES_ID,
          properties = mapOf("name" to "Synthetic chapter", "source" to "local"),
        ),
      )
      history.insert(
        HistoricalEvent(
          id = "event-2",
          type = "BookFileDeleted",
          timestampMillis = 20,
          bookId = BOOK_ID,
        ),
      )
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val history = JooqHistoricalEventRepository(database)
      val first =
        history.findAll(
          HistoricalEventPageRequest(
            page = 0,
            size = 1,
            direction = SortDirection.DESCENDING,
          ),
        )
      val second =
        history.findAll(
          HistoricalEventPageRequest(
            page = 1,
            size = 1,
            direction = SortDirection.DESCENDING,
          ),
        )

      assertEquals(2, first.totalElements)
      assertEquals(listOf("event-2"), first.content.map(HistoricalEvent::id))
      assertEquals(listOf("event-1"), second.content.map(HistoricalEvent::id))
      assertEquals(
        mapOf("name" to "Synthetic chapter", "source" to "local"),
        second.content.single().properties,
      )
    }
  }

  @Test
  fun `sync points support API key scoped and complete user deletion`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("sync.sqlite"))).use { database ->
      JooqUserRepository(database).insert(user())
      val apiKeys = JooqApiKeyRepository(database)
      apiKeys.insert(apiKey(API_KEY_ONE, "first-hash", "First synthetic client"))
      apiKeys.insert(apiKey(API_KEY_TWO, "second-hash", "Second synthetic client"))
      val syncPoints = JooqSyncPointRepository(database)
      val userPoint = syncPoint("sync-user", null)
      val firstKeyPoint = syncPoint("sync-first", API_KEY_ONE)
      val secondKeyPoint = syncPoint("sync-second", API_KEY_TWO)
      listOf(userPoint, firstKeyPoint, secondKeyPoint).forEach(syncPoints::insert)

      assertEquals(1, syncPoints.deleteByUserIdAndApiKeyIds(USER_ID, listOf(API_KEY_ONE)))
      assertNull(syncPoints.findByIdOrNull(firstKeyPoint.id))
      assertNotNull(syncPoints.findByIdOrNull(userPoint.id))
      assertNotNull(syncPoints.findByIdOrNull(secondKeyPoint.id))

      assertEquals(2, syncPoints.deleteByUserId(USER_ID))
      assertNull(syncPoints.findByIdOrNull(userPoint.id))
      assertNull(syncPoints.findByIdOrNull(secondKeyPoint.id))
    }
  }

  @Test
  fun `ComicRack matching batches aliases and ignores leading zeroes`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("matching.sqlite"))).use {
        database ->
      seedCatalog(database)

      val matches =
        JooqReadListImportMatcher(database).match(
          listOf(
            ReadListImportBookRequest(
              series = setOf("Other alias", "SYNTHETIC CATALOG"),
              number = "007",
            ),
            ReadListImportBookRequest(
              series = setOf("Synthetic catalog"),
              number = "8",
            ),
          ),
        )

      assertEquals(2, matches.size)
      assertEquals(listOf("book-7"), matches[0].matches.single().books.map { it.bookId })
      assertEquals(emptyList(), matches[1].matches)
    }
  }

  private fun seedCatalog(database: XoboroDatabase) {
    JooqLibraryRepository(database).insert(
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation("local", "file:///synthetic"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = SERIES_ID,
        libraryId = LIBRARY_ID,
        name = "Synthetic catalog",
        relativePath = "catalog",
        sourceItemId = "file:///synthetic/catalog",
        fileModifiedAtMillis = 1,
        bookCount = 1,
        createdAtMillis = 1,
      ),
    )
    JooqBookRepository(database).insert(
      Book(
        id = BOOK_ID,
        libraryId = LIBRARY_ID,
        seriesId = SERIES_ID,
        name = "Synthetic chapter",
        relativePath = "catalog/chapter.cbz",
        sourceItemId = "file:///synthetic/catalog/chapter.cbz",
        mediaKind = MediaKind.COMIC_ARCHIVE,
        fileModifiedAtMillis = 1,
        number = 7,
        createdAtMillis = 1,
      ),
    )
  }

  private fun user(): User =
    User(
      id = USER_ID,
      email = "reader@example.invalid",
      passwordHash = "synthetic-password-hash",
      createdAtMillis = 1,
    )

  private fun apiKey(
    id: ApiKeyId,
    hash: String,
    comment: String,
  ): ApiKey =
    ApiKey(
      id = id,
      userId = USER_ID,
      keyHash = hash,
      comment = comment,
      createdAtMillis = 2,
    )

  private fun syncPoint(
    id: String,
    apiKeyId: ApiKeyId?,
  ): SyncPoint =
    SyncPoint(
      id = SyncPointId(id),
      userId = USER_ID,
      apiKeyId = apiKeyId,
      createdAtMillis = 3,
    )

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-7")
    val USER_ID = UserId("user-1")
    val API_KEY_ONE = ApiKeyId("key-1")
    val API_KEY_TWO = ApiKeyId("key-2")
  }
}
