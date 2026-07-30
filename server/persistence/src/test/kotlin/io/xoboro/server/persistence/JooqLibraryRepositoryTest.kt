package io.xoboro.server.persistence

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.ScanInterval
import io.xoboro.core.domain.SeriesCover
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqLibraryRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `round trips all Komga-compatible library settings`() {
    withRepository("round-trip") { repository, _ ->
      val expected = libraryFixture()

      repository.insert(expected)

      assertEquals(expected, repository.findById(expected.id))
      assertEquals(expected, repository.findByIdOrNull(expected.id))
      assertEquals(listOf(expected), repository.findAll())
      assertEquals(1L, repository.count())
    }
  }

  @Test
  fun `updates settings and atomically replaces scan exclusions`() {
    withRepository("update") { repository, _ ->
      val original = libraryFixture()
      repository.insert(original)
      val updated =
        original.copy(
          name = "Updated synthetic library",
          settings =
            original.settings.copy(
              scanDirectoryExclusions = setOf("ignored-new"),
              scanInterval = ScanInterval.DAILY,
            ),
          unavailableAtMillis = 1_700_000_003_000L,
          updatedAtMillis = 1_700_000_004_000L,
        )

      repository.update(updated)

      assertEquals(updated, repository.findById(updated.id))
    }
  }

  @Test
  fun `finds only requested IDs and handles empty ID collections`() {
    withRepository("find-ids") { repository, _ ->
      val first = libraryFixture(id = "library-1", name = "Zulu")
      val second =
        libraryFixture(
          id = "library-2",
          name = "Alpha",
          rootUri = "file:///synthetic/second",
        )
      repository.insert(first)
      repository.insert(second)

      assertEquals(listOf(second, first), repository.findAll())
      assertEquals(listOf(first), repository.findAllByIds(listOf(first.id)))
      assertEquals(emptyList(), repository.findAllByIds(emptyList()))
      assertNull(repository.findByIdOrNull(LibraryId("missing")))
      assertFailsWith<NoSuchElementException> {
        repository.findById(LibraryId("missing"))
      }
    }
  }

  @Test
  fun `rejects updating a missing library without writing exclusions`() {
    withRepository("missing-update") { repository, database ->
      assertFailsWith<NoSuchElementException> {
        repository.update(libraryFixture())
      }

      assertEquals(
        0,
        database.dsl
          .fetchOne("SELECT count(*) FROM library_scan_exclusion")
          ?.get(0, Int::class.java),
      )
    }
  }

  @Test
  fun `deleting a library cascades through its complete catalog subtree`() {
    withRepository("delete-cascade") { repository, database ->
      val library = libraryFixture()
      repository.insert(library)
      database.dsl.execute(
        """
        INSERT INTO series
          (id, library_id, relative_uri, name, sort_title, created_at_ms, updated_at_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        "series-1",
        library.id.value,
        "series",
        "Synthetic series",
        "Synthetic series",
        1L,
        1L,
      )
      database.dsl.execute(
        """
        INSERT INTO book (
          id, library_id, series_id, relative_uri, name, media_kind, file_size,
          file_modified_ms, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        "book-1",
        library.id.value,
        "series-1",
        "series/book.cbz",
        "Synthetic book",
        "COMIC_ARCHIVE",
        1L,
        1L,
        1L,
        1L,
      )

      repository.delete(library.id)

      assertEquals(0L, repository.count())
      assertEquals(
        0,
        database.dsl.fetchOne("SELECT count(*) FROM series")?.get(0, Int::class.java),
      )
      assertEquals(
        0,
        database.dsl.fetchOne("SELECT count(*) FROM book")?.get(0, Int::class.java),
      )
      assertEquals(
        0,
        database.dsl
          .fetchOne("SELECT count(*) FROM library_scan_exclusion")
          ?.get(0, Int::class.java),
      )
    }
  }

  @Test
  fun `delete all removes every library`() {
    withRepository("delete-all") { repository, _ ->
      repository.insert(libraryFixture(id = "library-1"))
      repository.insert(
        libraryFixture(
          id = "library-2",
          rootUri = "file:///synthetic/second",
        ),
      )

      repository.deleteAll()

      assertEquals(0L, repository.count())
      assertEquals(emptyList(), repository.findAll())
    }
  }

  private fun withRepository(
    databaseName: String,
    block: (JooqLibraryRepository, XoboroDatabase) -> Unit,
  ) {
    XoboroDatabase.open(
      DatabaseConfig(tempDirectory.resolve("$databaseName.sqlite")),
    ).use { database ->
      block(JooqLibraryRepository(database), database)
    }
  }

  private fun libraryFixture(
    id: String = "library-1",
    name: String = "Synthetic library",
    rootUri: String = "webdav://example.invalid/library",
  ): Library =
    Library(
      id = LibraryId(id),
      name = name,
      root = SourceLocation(sourceId = "synthetic-webdav", itemId = rootUri),
      settings =
        LibrarySettings(
          importComicInfoBook = false,
          importComicInfoSeries = false,
          importComicInfoCollection = false,
          importComicInfoReadList = false,
          importComicInfoSeriesAppendVolume = false,
          importEpubBook = false,
          importEpubSeries = false,
          importPdfBook = false,
          importMylarSeries = false,
          importLocalArtwork = false,
          importBarcodeIsbn = false,
          scanForceModifiedTime = true,
          scanOnStartup = true,
          scanInterval = ScanInterval.WEEKLY,
          scanCbx = false,
          scanPdf = false,
          scanEpub = false,
          scanDirectoryExclusions = setOf("ignored-a", "ignored-b"),
          repairExtensions = true,
          convertToCbz = true,
          emptyTrashAfterScan = true,
          seriesCover = SeriesCover.LAST,
          hashFiles = false,
          hashPages = true,
          hashKoreader = true,
          analyzeDimensions = false,
          oneshotsDirectory = "oneshots",
        ),
      unavailableAtMillis = 1_700_000_002_000L,
      createdAtMillis = 1_700_000_001_000L,
      updatedAtMillis = 1_700_000_002_000L,
    )
}
