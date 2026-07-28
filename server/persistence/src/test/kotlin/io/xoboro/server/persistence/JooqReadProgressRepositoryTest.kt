package io.xoboro.server.persistence

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqReadProgressRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `upsertIfNewer inserts missing progress`() {
    withRepository("insert") { repository, firstBookId, _, _, userId, _ ->
      val progress =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 3,
          readAtMillis = 100,
          deviceId = "device-insert",
          deviceName = "Synthetic insert device",
          locatorJson = """{"position":"insert"}""",
        )

      val changed = repository.upsertIfNewer(progress)

      assertTrue(changed)
      assertEquals(
        progress,
        repository.findByBookIdAndUserIdOrNull(firstBookId, userId),
      )
    }
  }

  @Test
  fun `upsertIfNewer updates all mutable progress fields for a newer timestamp`() {
    withRepository("newer") { repository, firstBookId, _, _, userId, _ ->
      val original =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 2,
          readAtMillis = 100,
          deviceId = "device-old",
          deviceName = "Synthetic old device",
          locatorJson = """{"position":"old"}""",
          createdAtMillis = 10,
          updatedAtMillis = 100,
        )
      repository.upsert(original)
      val newer =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 8,
          completed = true,
          readAtMillis = 200,
          deviceId = "device-new",
          deviceName = "Synthetic new device",
          locatorJson = """{"position":"new"}""",
          createdAtMillis = 150,
          updatedAtMillis = 250,
        )

      val changed = repository.upsertIfNewer(newer)

      assertTrue(changed)
      assertEquals(
        newer.copy(createdAtMillis = original.createdAtMillis),
        repository.findByBookIdAndUserIdOrNull(firstBookId, userId),
      )
    }
  }

  @Test
  fun `upsertIfNewer rejects an equal timestamp without changing stored progress`() {
    withRepository("equal") { repository, firstBookId, _, _, userId, _ ->
      val original =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 4,
          readAtMillis = 100,
          deviceId = "device-original",
          deviceName = "Synthetic original device",
          locatorJson = """{"position":"original"}""",
          createdAtMillis = 10,
          updatedAtMillis = 100,
        )
      repository.upsert(original)
      val equal =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 9,
          completed = true,
          readAtMillis = 100,
          deviceId = "device-equal",
          deviceName = "Synthetic equal device",
          locatorJson = """{"position":"equal"}""",
          createdAtMillis = 20,
          updatedAtMillis = 300,
        )

      val changed = repository.upsertIfNewer(equal)

      assertFalse(changed)
      assertEquals(
        original,
        repository.findByBookIdAndUserIdOrNull(firstBookId, userId),
      )
    }
  }

  @Test
  fun `upsertIfNewer rejects an older timestamp without changing stored progress`() {
    withRepository("older") { repository, firstBookId, _, _, userId, _ ->
      val original =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 5,
          completed = true,
          readAtMillis = 100,
          deviceId = "device-original",
          deviceName = "Synthetic original device",
          locatorJson = """{"position":"original"}""",
          createdAtMillis = 10,
          updatedAtMillis = 100,
        )
      repository.upsert(original)
      val older =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 1,
          readAtMillis = 50,
          deviceId = "device-older",
          deviceName = "Synthetic older device",
          locatorJson = """{"position":"older"}""",
          createdAtMillis = 20,
          updatedAtMillis = 300,
        )

      val changed = repository.upsertIfNewer(older)

      assertFalse(changed)
      assertEquals(
        original,
        repository.findByBookIdAndUserIdOrNull(firstBookId, userId),
      )
    }
  }

  @Test
  fun `upsertIfNewer preserves creation time and advances update time`() {
    withRepository("timestamps") { repository, firstBookId, _, _, userId, _ ->
      val original =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 1,
          readAtMillis = 100,
          createdAtMillis = 10,
          updatedAtMillis = 100,
        )
      repository.upsert(original)
      val newer =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 6,
          readAtMillis = 200,
          createdAtMillis = 150,
          updatedAtMillis = 250,
        )

      val changed = repository.upsertIfNewer(newer)
      val stored =
        assertNotNull(repository.findByBookIdAndUserIdOrNull(firstBookId, userId))

      assertTrue(changed)
      assertEquals(10, stored.createdAtMillis)
      assertEquals(250, stored.updatedAtMillis)
      assertEquals(newer.copy(createdAtMillis = 10), stored)
    }
  }

  @Test
  fun `upsertIfNewer recomputes series progress only for accepted writes`() {
    withRepository("series") { repository, firstBookId, secondBookId, seriesId, userId, _ ->
      val first =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 4,
          readAtMillis = 100,
          createdAtMillis = 10,
          updatedAtMillis = 100,
        )
      assertTrue(repository.upsertIfNewer(first))
      val initialSeries =
        assertNotNull(repository.findSeriesByIdAndUserIdOrNull(seriesId, userId))
      assertEquals(0, initialSeries.booksReadCount)
      assertEquals(1, initialSeries.booksInProgressCount)
      assertEquals(100, initialSeries.lastReadAtMillis)

      val second =
        progress(
          bookId = secondBookId,
          userId = userId,
          page = 10,
          completed = true,
          readAtMillis = 200,
          deviceId = "device-accepted",
          deviceName = "Synthetic accepted device",
          locatorJson = """{"position":"accepted"}""",
          createdAtMillis = 20,
          updatedAtMillis = 200,
        )
      val accepted = repository.upsertIfNewer(second)
      val acceptedSeries =
        assertNotNull(repository.findSeriesByIdAndUserIdOrNull(seriesId, userId))

      assertTrue(accepted)
      assertEquals(second, repository.findByBookIdAndUserIdOrNull(secondBookId, userId))
      assertEquals(1, acceptedSeries.booksReadCount)
      assertEquals(1, acceptedSeries.booksInProgressCount)
      assertEquals(200, acceptedSeries.lastReadAtMillis)
      assertEquals(10, acceptedSeries.createdAtMillis)
      assertEquals(200, acceptedSeries.updatedAtMillis)

      val rejected =
        progress(
          bookId = secondBookId,
          userId = userId,
          page = 2,
          readAtMillis = 150,
          deviceId = "device-rejected",
          deviceName = "Synthetic rejected device",
          locatorJson = """{"position":"rejected"}""",
          createdAtMillis = 30,
          updatedAtMillis = 300,
        )
      val changed = repository.upsertIfNewer(rejected)
      val rejectedSeries =
        assertNotNull(repository.findSeriesByIdAndUserIdOrNull(seriesId, userId))

      assertFalse(changed)
      assertEquals(second, repository.findByBookIdAndUserIdOrNull(secondBookId, userId))
      assertEquals(1, rejectedSeries.booksReadCount)
      assertEquals(1, rejectedSeries.booksInProgressCount)
      assertEquals(200, rejectedSeries.lastReadAtMillis)
      assertEquals(acceptedSeries, rejectedSeries)
    }
  }

  @Test
  fun `upsertIfNewer scopes the newer-only comparison to a single user`() {
    withRepository("two-users") { repository, firstBookId, _, seriesId, userId, otherUserId ->
      val owner =
        progress(
          bookId = firstBookId,
          userId = userId,
          page = 7,
          readAtMillis = 200,
          deviceId = "device-owner",
          deviceName = "Synthetic owner device",
          locatorJson = """{"position":"owner"}""",
          createdAtMillis = 10,
          updatedAtMillis = 200,
        )
      assertTrue(repository.upsertIfNewer(owner))
      val other =
        progress(
          bookId = firstBookId,
          userId = otherUserId,
          page = 2,
          readAtMillis = 100,
          deviceId = "device-other",
          deviceName = "Synthetic other device",
          locatorJson = """{"position":"other"}""",
          createdAtMillis = 20,
          updatedAtMillis = 100,
        )

      // An older timestamp belonging to a different user must not lose to, or overwrite, the
      // first user's row. A comparison missing the user scope would reject this write.
      assertTrue(repository.upsertIfNewer(other))

      assertEquals(owner, repository.findByBookIdAndUserIdOrNull(firstBookId, userId))
      assertEquals(other, repository.findByBookIdAndUserIdOrNull(firstBookId, otherUserId))
      val ownerSeries = assertNotNull(repository.findSeriesByIdAndUserIdOrNull(seriesId, userId))
      val otherSeries =
        assertNotNull(repository.findSeriesByIdAndUserIdOrNull(seriesId, otherUserId))
      assertEquals(200, ownerSeries.lastReadAtMillis)
      assertEquals(100, otherSeries.lastReadAtMillis)
      assertEquals(1, ownerSeries.booksInProgressCount)
      assertEquals(1, otherSeries.booksInProgressCount)
    }
  }

  @Test
  fun `concurrent upsertIfNewer calls converge on the newest submitted progress`() {
    // maximumPoolSize must be at least the writer count: with a smaller pool the writers queue
    // on Hikari and serialize, which would let a non-atomic read-then-write implementation pass
    // this test. Keep it equal to `timestamps.size` below.
    withRepository(
      name = "concurrent",
      maximumPoolSize = 7,
    ) { repository, firstBookId, _, _, userId, _ ->
      val timestamps = listOf(400L, 900L, 200L, 700L, 100L, 600L, 300L)
      val progresses =
        timestamps.map { timestamp ->
          progress(
            bookId = firstBookId,
            userId = userId,
            page = (timestamp / 100).toInt(),
            completed = timestamp == timestamps.max(),
            readAtMillis = timestamp,
            deviceId = "device-$timestamp",
            deviceName = "Synthetic device $timestamp",
            locatorJson = """{"position":$timestamp}""",
            createdAtMillis = 1,
            updatedAtMillis = timestamp + 1_000,
          )
        }
      val barrier = CyclicBarrier(progresses.size)
      val executor = Executors.newFixedThreadPool(progresses.size)

      try {
        val results =
          progresses.map { candidate ->
            executor.submit<Boolean> {
              barrier.await(10, TimeUnit.SECONDS)
              repository.upsertIfNewer(candidate)
            }
          }.map { it.get(30, TimeUnit.SECONDS) }
        val newest = progresses.maxBy(ReadProgress::readAtMillis)

        assertTrue(results[progresses.indexOf(newest)])
        assertEquals(
          newest,
          repository.findByBookIdAndUserIdOrNull(firstBookId, userId),
        )
      } finally {
        executor.shutdownNow()
        executor.awaitTermination(10, TimeUnit.SECONDS)
      }
    }
  }

  private fun withRepository(
    name: String,
    maximumPoolSize: Int = 4,
    block: (
      JooqReadProgressRepository,
      BookId,
      BookId,
      SeriesId,
      UserId,
      UserId,
    ) -> Unit,
  ) {
    XoboroDatabase
      .open(
        DatabaseConfig(
          path = tempDirectory.resolve("$name.sqlite"),
          maximumPoolSize = maximumPoolSize,
        ),
      ).use { database ->
        val libraryId = LibraryId("library-$name")
        val seriesId = SeriesId("series-$name")
        val firstBookId = BookId("book-$name-1")
        val secondBookId = BookId("book-$name-2")
        val userId = UserId("user-$name")
        val secondUserId = UserId("user-$name-other")
        JooqLibraryRepository(database).insert(
          Library(
            id = libraryId,
            name = "Synthetic library $name",
            root = SourceLocation("local", "file:///synthetic/$name"),
            createdAtMillis = 1,
          ),
        )
        JooqSeriesRepository(database).insert(
          Series(
            id = seriesId,
            libraryId = libraryId,
            name = "Synthetic series $name",
            relativePath = "series-$name",
            sourceItemId = "file:///synthetic/$name/series",
            fileModifiedAtMillis = 1,
            bookCount = 2,
            createdAtMillis = 1,
          ),
        )
        val books = JooqBookRepository(database)
        listOf(firstBookId, secondBookId).forEachIndexed { index, bookId ->
          val number = index + 1
          books.insert(
            Book(
              id = bookId,
              libraryId = libraryId,
              seriesId = seriesId,
              name = "Synthetic book $number",
              relativePath = "series-$name/book-$number.cbz",
              sourceItemId = "file:///synthetic/$name/series/book-$number.cbz",
              mediaKind = MediaKind.COMIC_ARCHIVE,
              fileModifiedAtMillis = number.toLong(),
              number = number,
              createdAtMillis = number.toLong(),
            ),
          )
        }
        val users = JooqUserRepository(database)
        users.insert(
          User(
            id = userId,
            email = "$name@example.invalid",
            passwordHash = "synthetic-password-hash",
            createdAtMillis = 1,
          ),
        )
        users.insert(
          User(
            id = secondUserId,
            email = "$name-other@example.invalid",
            passwordHash = "synthetic-password-hash",
            createdAtMillis = 1,
          ),
        )

        block(
          JooqReadProgressRepository(database),
          firstBookId,
          secondBookId,
          seriesId,
          userId,
          secondUserId,
        )
      }
  }

  private fun progress(
    bookId: BookId,
    userId: UserId,
    page: Int,
    completed: Boolean = false,
    readAtMillis: Long,
    deviceId: String = "",
    deviceName: String = "",
    locatorJson: String? = null,
    createdAtMillis: Long = readAtMillis,
    updatedAtMillis: Long = createdAtMillis,
  ): ReadProgress =
    ReadProgress(
      bookId = bookId,
      userId = userId,
      page = page,
      completed = completed,
      readAtMillis = readAtMillis,
      deviceId = deviceId,
      deviceName = deviceName,
      locatorJson = locatorJson,
      createdAtMillis = createdAtMillis,
      updatedAtMillis = updatedAtMillis,
    )
}
