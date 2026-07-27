package io.xoboro.server.persistence

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.AnalyzeBook
import io.xoboro.server.media.ZipMediaAnalyzer
import io.xoboro.server.sources.local.LocalSourceMediaAccess
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class BookAnalysisIntegrationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `analyzes a local CBZ and survives database restart`() {
    val databasePath = tempDirectory.resolve("analysis.sqlite")
    val archive = createArchive()

    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      val libraries = JooqLibraryRepository(database)
      val books = JooqBookRepository(database)
      libraries.insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", tempDirectory.toUri().toString()),
          settings = LibrarySettings(analyzeDimensions = true),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "series",
          sourceItemId = archive.parent.toUri().toString(),
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      books.insert(
        Book(
          id = BOOK_ID,
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic book",
          relativePath = "series/book.cbz",
          sourceItemId = archive.toUri().toString(),
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      val analyzer =
        AnalyzeBook(
          books = books,
          libraries = libraries,
          accesses = listOf(LocalSourceMediaAccess()),
          media = JooqBookMediaRepository(database),
          zipAnalyzer = ZipMediaAnalyzer(),
          currentTimeMillis = { 1_700_000_000_000L },
        )

      val result = analyzer.execute(BOOK_ID)

      assertEquals(MediaStatus.READY, result.status)
      assertEquals(listOf("001.png", "002.png"), result.pages.map { it.fileName })
      assertEquals(24, result.pages.first().dimension?.width)
    }

    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      val restored =
        JooqBookMediaRepository(database).findByBookIdOrNull(BOOK_ID)
          ?: error("Media did not survive restart")
      assertEquals(MediaStatus.READY, restored.status)
      assertEquals(2, restored.pageCount)
      assertEquals(1_700_000_000_000L, restored.createdAtMillis)
    }
  }

  private fun createArchive(): Path {
    val directory = Files.createDirectories(tempDirectory.resolve("series"))
    val archive = directory.resolve("book.cbz")
    ZipOutputStream(Files.newOutputStream(archive)).use { output ->
      listOf("002.png" to 12, "001.png" to 24).forEach { (name, width) ->
        val bytes =
          java.io.ByteArrayOutputStream().use { imageBytes ->
            ImageIO.write(
              BufferedImage(width, 40, BufferedImage.TYPE_INT_RGB),
              "png",
              imageBytes,
            )
            imageBytes.toByteArray()
          }
        output.putNextEntry(ZipEntry(name))
        output.write(bytes)
        output.closeEntry()
      }
    }
    return archive
  }

  companion object {
    private val LIBRARY_ID = LibraryId("library-1")
    private val SERIES_ID = SeriesId("series-1")
    private val BOOK_ID = BookId("book-1")
  }
}
