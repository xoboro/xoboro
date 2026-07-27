package io.xoboro.server.persistence

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaItemFingerprintAlgorithm
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class JooqMediaItemFingerprintIndexTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `returns every media item with the requested sparse fingerprint`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("catalog.sqlite"))).use { database ->
      val libraryId = LibraryId("library-1")
      JooqLibraryRepository(database).insert(
        Library(
          id = libraryId,
          name = "Synthetic library",
          root = SourceLocation("local", "root"),
          createdAtMillis = 1,
        ),
      )
      val seriesId = SeriesId("series-1")
      JooqSeriesRepository(database).insert(
        Series(
          id = seriesId,
          libraryId = libraryId,
          name = "Synthetic series",
          relativePath = "series",
          sourceItemId = "series",
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      val books = JooqBookRepository(database)
      listOf("media-2", "media-1").forEach { id ->
        books.insert(
          Book(
            id = BookId(id),
            libraryId = libraryId,
            seriesId = seriesId,
            name = id,
            relativePath = "series/$id.cbz",
            sourceItemId = "series/$id.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 1,
            fileHashKoreader = "shared-fingerprint",
            createdAtMillis = 1,
          ),
        )
      }

      assertEquals(
        listOf(BookId("media-1"), BookId("media-2")),
        JooqMediaItemFingerprintIndex(database).findAll(
          MediaItemFingerprintAlgorithm.KOREADER_PARTIAL_MD5,
          "shared-fingerprint",
        ),
      )
    }
  }
}
