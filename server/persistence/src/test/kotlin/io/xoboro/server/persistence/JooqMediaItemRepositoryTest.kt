package io.xoboro.server.persistence

import io.xoboro.core.domain.Audio
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Comic
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaCapability
import io.xoboro.core.domain.MediaItemCore
import io.xoboro.core.domain.MediaItemId
import io.xoboro.core.domain.MediaItemType
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.Video
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqMediaItemRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `projects document adapters and independent timeline media from one library`() {
    withCatalog { database ->
      val books = JooqBookRepository(database)
      val repository = JooqMediaItemRepository(database, books)
      books.insert(document())
      repository.insert(
        Video(
          core = timelineCore("video-1", "video/sample.mkv"),
          durationMillis = 90_000,
        ),
      )
      repository.insert(
        Audio(
          core = timelineCore("audio-1", "audio/sample.flac"),
          durationMillis = 180_000,
        ),
      )

      val comic = assertIs<Comic>(repository.findByIdOrNull(MediaItemId("book-1")))
      assertEquals(MediaItemType.COMIC, comic.type)
      assertTrue(comic.supports(MediaCapability.PAGE_SEQUENCE))
      val video = assertIs<Video>(repository.findByIdOrNull(MediaItemId("video-1")))
      assertEquals(90_000, video.durationMillis)
      assertTrue(video.supports(MediaCapability.VIDEO_CONTENT))
      assertFalse(video.supports(MediaCapability.PAGE_SEQUENCE))
      assertEquals(
        listOf(MediaItemType.AUDIO, MediaItemType.COMIC, MediaItemType.VIDEO),
        repository.findAllByLibraryId(LIBRARY_ID).map { it.type },
      )
      assertEquals(
        1,
        database.dsl.fetchValue("SELECT count(*) FROM book", Int::class.java),
      )
    }
  }

  @Test
  fun `updates and deletes timeline media without a synthetic series or book`() {
    withCatalog { database ->
      val repository = JooqMediaItemRepository(database)
      val initial =
        Video(
          core = timelineCore("timeline-1", "video/original.mkv"),
          durationMillis = null,
        )
      repository.insert(initial)
      repository.update(
        Video(
          core =
            initial.core.copy(
              name = "Updated synthetic video",
              fileSize = 200,
              updatedAtMillis = 2,
            ),
          durationMillis = 120_000,
        ),
      )

      val updated = assertIs<Video>(repository.findByIdOrNull(initial.id))
      assertEquals("Updated synthetic video", updated.name)
      assertEquals(200, updated.fileSize)
      assertEquals(120_000, updated.durationMillis)
      assertEquals(
        0,
        database.dsl.fetchValue("SELECT count(*) FROM book", Int::class.java),
      )
      assertTrue(repository.delete(initial.id))
      assertFalse(repository.delete(initial.id))
      assertNull(repository.findByIdOrNull(initial.id))
    }
  }

  @Test
  fun `keeps canonical rows synchronized with the Komga book adapter`() {
    withCatalog { database ->
      val books = JooqBookRepository(database)
      val repository = JooqMediaItemRepository(database, books)
      val book = document()
      books.insert(book)
      books.update(
        book.copy(
          name = "Updated synthetic document",
          fileSize = 250,
          updatedAtMillis = 2,
        ),
      )

      val item = assertIs<Comic>(repository.findByIdOrNull(book.id))
      assertEquals("Updated synthetic document", item.name)
      assertEquals(250, item.fileSize)
      books.delete(book.id)
      assertNull(repository.findByIdOrNull(book.id))
    }
  }

  private fun withCatalog(block: (XoboroDatabase) -> Unit) {
    XoboroDatabase.open(
      DatabaseConfig(tempDirectory.resolve("media-${System.nanoTime()}.sqlite")),
    ).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("synthetic", "root"),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "documents",
          sourceItemId = "documents",
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      block(database)
    }
  }

  private fun document(): Book =
    Book(
      id = BookId("book-1"),
      libraryId = LIBRARY_ID,
      seriesId = SERIES_ID,
      name = "Synthetic document",
      relativePath = "documents/sample.cbz",
      sourceItemId = "document-source-1",
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1,
      fileSize = 100,
      createdAtMillis = 1,
    )

  private fun timelineCore(
    id: String,
    path: String,
  ): MediaItemCore =
    MediaItemCore(
      id = MediaItemId(id),
      libraryId = LIBRARY_ID,
      name = "Synthetic $id",
      relativePath = path,
      sourceItemId = "source-$id",
      sourceIdentity = "identity-$id",
      fileModifiedAtMillis = 1,
      fileSize = 100,
      createdAtMillis = 1,
    )

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
  }
}
