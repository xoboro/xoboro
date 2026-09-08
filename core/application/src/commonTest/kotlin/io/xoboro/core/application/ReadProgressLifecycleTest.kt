package io.xoboro.core.application

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.ReadProgressRepository
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesReadProgress
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ReadProgressLifecycleTest {
  @Test
  fun `applies newer progression and publishes one changed event`() {
    val fixture = Fixture(progress(readAtMillis = 100))

    val result =
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 2,
        modifiedAtMillis = 200,
        deviceId = "device-new",
        deviceName = "Synthetic reader",
        locatorJson = """{"href":"chapter-2"}""",
      )

    val applied = assertIs<ReadProgressUpdate.Applied>(result).progress
    assertEquals(2, applied.page)
    assertEquals(200, applied.readAtMillis)
    assertEquals("device-new", applied.deviceId)
    assertEquals("Synthetic reader", applied.deviceName)
    assertEquals("""{"href":"chapter-2"}""", applied.locatorJson)
    assertEquals(applied, fixture.progresses.findByBookIdAndUserIdOrNull(BOOK_ID, USER_ID))
    assertEquals(listOf<ReadProgressEvent>(ReadProgressEvent.Changed(applied)), fixture.events)
  }

  @Test
  fun `returns stored progress for equal timestamp without publishing an event`() {
    val existing = progress(readAtMillis = 200)
    val fixture = Fixture(existing)

    val result =
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 2,
        modifiedAtMillis = 200,
        deviceId = "device-new",
        deviceName = "Synthetic reader",
        locatorJson = """{"href":"chapter-2"}""",
      )

    assertEquals(existing, assertIs<ReadProgressUpdate.Stale>(result).stored)
    assertEquals(existing, fixture.progresses.findByBookIdAndUserIdOrNull(BOOK_ID, USER_ID))
    assertEquals(emptyList<ReadProgressEvent>(), fixture.events)
  }

  @Test
  fun `returns stale for an older timestamp`() {
    val existing = progress(readAtMillis = 200)
    val fixture = Fixture(existing)

    val result =
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 2,
        modifiedAtMillis = 199,
        deviceId = "device-new",
        deviceName = "Synthetic reader",
        locatorJson = """{"href":"chapter-2"}""",
      )

    assertEquals(existing, assertIs<ReadProgressUpdate.Stale>(result).stored)
    assertEquals(existing, fixture.progresses.findByBookIdAndUserIdOrNull(BOOK_ID, USER_ID))
    assertEquals(emptyList<ReadProgressEvent>(), fixture.events)
  }

  @Test
  fun `returns media item not found for an unknown book`() {
    val fixture = Fixture()

    val result =
      fixture.lifecycle.updateBookProgression(
        bookId = BookId("missing-book"),
        userId = USER_ID,
        page = 1,
        modifiedAtMillis = 200,
        deviceId = "device-1",
        deviceName = "Synthetic reader",
        locatorJson = """{"href":"chapter-1"}""",
      )

    assertEquals(ReadProgressUpdate.MediaItemNotFound, result)
  }

  @Test
  fun `explicit incomplete keeps the final page resumable`() {
    val fixture = Fixture()

    val result =
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 3,
        modifiedAtMillis = 200,
        deviceId = "device-1",
        deviceName = "Synthetic reader",
        locatorJson = null,
        completed = false,
      )

    assertEquals(false, assertIs<ReadProgressUpdate.Applied>(result).progress.completed)
  }

  @Test
  fun `explicit complete marks the final page complete`() {
    val fixture = Fixture()

    val result =
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 3,
        modifiedAtMillis = 200,
        deviceId = "device-1",
        deviceName = "Synthetic reader",
        locatorJson = null,
        completed = true,
      )

    assertEquals(true, assertIs<ReadProgressUpdate.Applied>(result).progress.completed)
  }

  @Test
  fun `omitted completion retains final page completion`() {
    val fixture = Fixture()

    val result =
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 3,
        modifiedAtMillis = 200,
        deviceId = "device-1",
        deviceName = "Synthetic reader",
        locatorJson = null,
      )

    assertEquals(true, assertIs<ReadProgressUpdate.Applied>(result).progress.completed)
  }

  @Test
  fun `rejects explicit completion before the final page`() {
    val fixture = Fixture()

    assertFailsWith<IllegalArgumentException> {
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 2,
        modifiedAtMillis = 200,
        deviceId = "device-1",
        deviceName = "Synthetic reader",
        locatorJson = null,
        completed = true,
      )
    }
  }

  @Test
  fun `rejects progression updates for a deleted book`() {
    val fixture = Fixture(book = book().copy(deletedAtMillis = 20))

    assertFailsWith<IllegalArgumentException> {
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 1,
        modifiedAtMillis = 200,
        deviceId = "device-1",
        deviceName = "Synthetic reader",
        locatorJson = """{"href":"chapter-1"}""",
      )
    }
  }

  @Test
  fun `rejects invalid progression arguments`() {
    val fixture = Fixture()

    listOf(0, 4).forEach { page ->
      assertFailsWith<IllegalArgumentException> {
        fixture.lifecycle.updateBookProgression(
          bookId = BOOK_ID,
          userId = USER_ID,
          page = page,
          modifiedAtMillis = 200,
          deviceId = "device-1",
          deviceName = "Synthetic reader",
          locatorJson = """{"href":"chapter-1"}""",
        )
      }
    }
    assertFailsWith<IllegalArgumentException> {
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 1,
        modifiedAtMillis = 200,
        deviceId = "device-1",
        deviceName = "Synthetic reader",
        locatorJson = " ",
      )
    }
    assertFailsWith<IllegalArgumentException> {
      fixture.lifecycle.updateBookProgression(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 1,
        modifiedAtMillis = -1,
        deviceId = "device-1",
        deviceName = "Synthetic reader",
        locatorJson = """{"href":"chapter-1"}""",
      )
    }
  }

  private class Fixture(
    initialProgress: ReadProgress? = null,
    book: Book = book(),
  ) {
    val events = mutableListOf<ReadProgressEvent>()
    val progresses = InMemoryReadProgressRepository(initialProgress)
    val lifecycle =
      ReadProgressLifecycle(
        books = InMemoryBookRepository(book),
        series = InMemorySeriesRepository(),
        media = InMemoryBookMediaRepository(media()),
        progresses = progresses,
        currentTimeMillis = { 300 },
        eventPublisher = ReadProgressEventPublisher(events::add),
      )
  }

  private class InMemoryBookRepository(
    book: Book,
  ) : BookRepository {
    private val values = linkedMapOf(book.id to book)

    override fun findByIdOrNull(id: BookId): Book? = values[id]

    override fun findAllByLibraryId(libraryId: LibraryId): List<Book> =
      values.values.filter { it.libraryId == libraryId }

    override fun findAllBySeriesId(seriesId: SeriesId): List<Book> =
      values.values.filter { it.seriesId == seriesId }

    override fun findByLibraryIdAndRelativePath(
      libraryId: LibraryId,
      relativePath: String,
    ): Book? = values.values.firstOrNull {
      it.libraryId == libraryId && it.relativePath == relativePath
    }

    override fun insert(book: Book) {
      values[book.id] = book
    }

    override fun insertAll(books: Collection<Book>) = books.forEach(::insert)

    override fun update(book: Book) = insert(book)

    override fun updateAll(books: Collection<Book>) = books.forEach(::update)

    override fun delete(id: BookId) {
      values.remove(id)
    }

    override fun count(): Long = values.size.toLong()
  }

  private class InMemorySeriesRepository : SeriesRepository {
    override fun findByIdOrNull(id: SeriesId): Series? = null

    override fun findAllByLibraryId(libraryId: LibraryId): List<Series> = emptyList()

    override fun findByLibraryIdAndRelativePath(
      libraryId: LibraryId,
      relativePath: String,
    ): Series? = null

    override fun insert(series: Series) = error("Not used")

    override fun insertAll(series: Collection<Series>) = error("Not used")

    override fun update(series: Series) = error("Not used")

    override fun updateAll(series: Collection<Series>) = error("Not used")

    override fun delete(id: SeriesId) = error("Not used")

    override fun count(): Long = 0
  }

  private class InMemoryBookMediaRepository(
    media: BookMedia,
  ) : BookMediaRepository {
    private val values = linkedMapOf(media.bookId to media)

    override fun findByBookIdOrNull(bookId: BookId): BookMedia? = values[bookId]

    override fun upsert(media: BookMedia) {
      values[media.bookId] = media
    }

    override fun deleteByBookId(bookId: BookId) {
      values.remove(bookId)
    }
  }

  private class InMemoryReadProgressRepository(
    initial: ReadProgress?,
  ) : ReadProgressRepository {
    private val values =
      initial
        ?.let { linkedMapOf((it.bookId to it.userId) to it) }
        ?: linkedMapOf()

    override fun findByBookIdAndUserIdOrNull(
      bookId: BookId,
      userId: UserId,
    ): ReadProgress? = values[bookId to userId]

    override fun findAllByBookIdsAndUserId(
      bookIds: Collection<BookId>,
      userId: UserId,
    ): List<ReadProgress> = bookIds.mapNotNull { values[it to userId] }

    override fun findSeriesByIdAndUserIdOrNull(
      seriesId: SeriesId,
      userId: UserId,
    ): SeriesReadProgress? = null

    override fun upsert(progress: ReadProgress) {
      values[progress.bookId to progress.userId] = progress
    }

    override fun upsertIfNewer(progress: ReadProgress): Boolean {
      val existing = values[progress.bookId to progress.userId]
      if (existing != null && progress.readAtMillis <= existing.readAtMillis) return false
      upsert(progress)
      return true
    }

    override fun upsertAll(progresses: Collection<ReadProgress>) = progresses.forEach(::upsert)

    override fun delete(
      bookId: BookId,
      userId: UserId,
    ) {
      values.remove(bookId to userId)
    }

    override fun deleteBySeriesIdAndUserId(
      seriesId: SeriesId,
      userId: UserId,
    ) = error("Not used")
  }

  companion object {
    private val BOOK_ID = BookId("book-1")
    private val LIBRARY_ID = LibraryId("library-1")
    private val SERIES_ID = SeriesId("series-1")
    private val USER_ID = UserId("user-1")

    private fun book(): Book =
      Book(
        id = BOOK_ID,
        libraryId = LIBRARY_ID,
        seriesId = SERIES_ID,
        name = "Synthetic book",
        relativePath = "series/book.epub",
        sourceItemId = "book-item",
        mediaKind = MediaKind.EPUB,
        fileModifiedAtMillis = 1,
        createdAtMillis = 1,
      )

    private fun media(): BookMedia =
      BookMedia(
        bookId = BOOK_ID,
        pageCount = 3,
        createdAtMillis = 1,
      )

    private fun progress(readAtMillis: Long): ReadProgress =
      ReadProgress(
        bookId = BOOK_ID,
        userId = USER_ID,
        page = 1,
        completed = false,
        readAtMillis = readAtMillis,
        deviceId = "device-old",
        deviceName = "Synthetic old reader",
        locatorJson = """{"href":"chapter-1"}""",
        createdAtMillis = 10,
        updatedAtMillis = 10,
      )
  }
}
