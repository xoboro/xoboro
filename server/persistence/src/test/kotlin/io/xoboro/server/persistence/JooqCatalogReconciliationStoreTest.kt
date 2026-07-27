package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogCandidate
import io.xoboro.core.application.ScanSessionId
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqCatalogReconciliationStoreTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `initial scan creates catalog rows and analysis tasks`() {
    withStore("initial") { fixture ->
      val result =
        fixture.scan(
          candidates =
            listOf(
              candidate("Series/001.cbz", "identity-1"),
              candidate("Series/002.cbz", "identity-2"),
              candidate("Root.epub", "identity-root", mediaKind = MediaKind.EPUB),
            ),
        )

      assertEquals(3L, result.addedBooks)
      assertEquals(2L, result.addedSeries)
      assertEquals(0L, result.changedBooks)
      assertEquals(3L, fixture.books.count())
      assertEquals(2L, fixture.series.count())
      assertEquals(
        mapOf("." to 1, "Series" to 2),
        fixture.series.findAllByLibraryId(LIBRARY_ID).associate { it.relativePath to it.bookCount },
      )
      assertEquals(3L, fixture.taskCount())
      assertTrue(fixture.candidateTableIsEmpty())
    }
  }

  @Test
  fun `identical repeated scan is idempotent and preserves IDs`() {
    withStore("idempotent") { fixture ->
      val files =
        listOf(
          candidate("Series/001.cbz", "identity-1"),
          candidate("Series/002.cbz", "identity-2"),
        )
      fixture.scan(files)
      val originalIds =
        fixture.books.findAllByLibraryId(LIBRARY_ID).associate { it.relativePath to it.id }
      fixture.clearTasks()

      val result = fixture.scan(files)

      assertEquals(0L, result.addedBooks)
      assertEquals(0L, result.changedBooks)
      assertEquals(0L, result.movedBooks)
      assertEquals(0L, result.deletedBooks)
      assertEquals(
        originalIds,
        fixture.books.findAllByLibraryId(LIBRARY_ID).associate { it.relativePath to it.id },
      )
      assertEquals(0L, fixture.taskCount())
    }
  }

  @Test
  fun `detects unique identity moves additions and deletions without opening content`() {
    withStore("moves") { fixture ->
      fixture.scan(
        listOf(
          candidate("Old/kept.cbz", "identity-kept"),
          candidate("Old/removed.cbz", "identity-removed"),
        ),
      )
      val kept =
        fixture.books.findByLibraryIdAndRelativePath(LIBRARY_ID, "Old/kept.cbz")
          ?: error("missing fixture")
      fixture.books.update(
        kept.copy(
          fileHash = "preserved-hash",
          fileHashKoreader = "preserved-koreader-hash",
        ),
      )
      fixture.clearTasks()

      val result =
        fixture.scan(
          listOf(
            candidate("Moved/kept.cbz", "identity-kept"),
            candidate("New/new.cbz", "identity-new"),
          ),
        )

      assertEquals(1L, result.movedBooks)
      assertEquals(1L, result.addedBooks)
      assertEquals(1L, result.deletedBooks)
      assertEquals(2L, result.addedSeries)
      assertEquals(1L, result.deletedSeries)
      val moved =
        fixture.books.findByLibraryIdAndRelativePath(LIBRARY_ID, "Moved/kept.cbz")
          ?: error("moved book missing")
      assertEquals(kept.id, moved.id)
      assertEquals("preserved-hash", moved.fileHash)
      assertEquals("preserved-koreader-hash", moved.fileHashKoreader)
      assertEquals(
        fixture.series.findByLibraryIdAndRelativePath(LIBRARY_ID, "Moved")?.id,
        moved.seriesId,
      )
      assertNotEquals(
        null,
        fixture.books.findByLibraryIdAndRelativePath(LIBRARY_ID, "Old/removed.cbz")?.deletedAtMillis,
      )
      assertEquals(2L, fixture.taskCount())
    }
  }

  @Test
  fun `changed deleted book is restored and stale hashes are cleared`() {
    withStore("restore") { fixture ->
      val originalCandidate = candidate("Series/book.cbz", "identity-1")
      fixture.scan(listOf(originalCandidate))
      val original =
        fixture.books.findAllByLibraryId(LIBRARY_ID).single().copy(
          fileHash = "stale-hash",
          fileHashKoreader = "stale-koreader",
        )
      fixture.books.update(original)
      fixture.clearTasks()
      fixture.scan(emptyList())
      assertNotEquals(null, fixture.books.findByIdOrNull(original.id)?.deletedAtMillis)
      fixture.clearTasks()

      val result =
        fixture.scan(
          listOf(
            originalCandidate.copy(
              fileSize = 200L,
              fileModifiedAtMillis = 200L,
            ),
          ),
        )

      assertEquals(1L, result.changedBooks)
      assertEquals(1L, result.restoredBooks)
      assertEquals(1L, result.restoredSeries)
      val restored = fixture.books.findByIdOrNull(original.id) ?: error("not restored")
      assertNull(restored.deletedAtMillis)
      assertEquals("", restored.fileHash)
      assertEquals("", restored.fileHashKoreader)
      assertEquals(1L, fixture.taskCount())
    }
  }

  @Test
  fun `partial inventory never deletes unseen books or reduces series count`() {
    withStore("partial") { fixture ->
      fixture.scan(
        listOf(
          candidate("Series/001.cbz", "identity-1"),
          candidate("Series/002.cbz", "identity-2"),
        ),
      )
      fixture.clearTasks()

      val result =
        fixture.scan(
          candidates = listOf(candidate("Series/001.cbz", "identity-1")),
          failedEntries = 1,
        )

      assertEquals(true, result.partial)
      assertEquals(0L, result.deletedBooks)
      assertEquals(0L, result.deletedSeries)
      assertEquals(
        2,
        fixture.series.findByLibraryIdAndRelativePath(LIBRARY_ID, "Series")?.bookCount,
      )
      assertTrue(
        fixture.books.findAllByLibraryId(LIBRARY_ID).all { it.deletedAtMillis == null },
      )
    }
  }

  @Test
  fun `ambiguous source identities are never treated as moves`() {
    withStore("ambiguous") { fixture ->
      fixture.scan(
        listOf(
          candidate("First/a.cbz", "shared-identity"),
          candidate("Second/b.cbz", "shared-identity"),
        ),
      )
      fixture.clearTasks()

      val result =
        fixture.scan(
          listOf(candidate("Third/c.cbz", "shared-identity")),
        )

      assertEquals(0L, result.movedBooks)
      assertEquals(1L, result.addedBooks)
      assertEquals(2L, result.deletedBooks)
      assertEquals(3L, fixture.books.count())
    }
  }

  @Test
  fun `deep scan queues unchanged books while regular scan does not`() {
    withStore("deep") { fixture ->
      val files = listOf(candidate("Series/book.cbz", "identity-1"))
      fixture.scan(files)
      fixture.clearTasks()

      fixture.scan(files, deep = true)

      assertEquals(1L, fixture.taskCount())
    }
  }

  @Test
  fun `abort cleans staged candidates and completed sessions cannot be reused`() {
    withStore("abort") { fixture ->
      val session = fixture.store.begin(LIBRARY_ID, deep = false, startedAtMillis = 1L)
      fixture.store.stage(session, listOf(candidate("Series/book.cbz", "identity-1")))

      fixture.store.abort(session, abortedAtMillis = 2L)

      assertTrue(fixture.candidateTableIsEmpty())
      assertFailsWith<IllegalStateException> {
        fixture.store.stage(session, listOf(candidate("Other/book.cbz", "identity-2")))
      }
      assertFailsWith<IllegalStateException> {
        fixture.store.complete(session, 0, 0, 3L)
      }
    }
  }

  @Test
  fun `rejects completion and abort timestamps that precede scan start`() {
    withStore("timestamp-order") { fixture ->
      val completionSession =
        fixture.store.begin(
          LIBRARY_ID,
          deep = false,
          startedAtMillis = 1_700_000_000_000L,
        )
      assertFailsWith<IllegalArgumentException> {
        fixture.store.complete(
          completionSession,
          failedEntries = 0,
          ignoredFiles = 0,
          completedAtMillis = 1_699_999_999_999L,
        )
      }

      val abortSession =
        fixture.store.begin(
          LIBRARY_ID,
          deep = false,
          startedAtMillis = 1_700_000_000_000L,
        )
      assertFailsWith<IllegalArgumentException> {
        fixture.store.abort(abortSession, abortedAtMillis = 1_699_999_999_999L)
      }
    }
  }

  private fun withStore(
    databaseName: String,
    block: (Fixture) -> Unit,
  ) {
    XoboroDatabase.open(
      DatabaseConfig(tempDirectory.resolve("$databaseName.sqlite")),
    ).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1L,
        ),
      )
      block(Fixture(database))
    }
  }

  private class Fixture(
    val database: XoboroDatabase,
  ) {
    private var sessionNumber = 0
    val store =
      JooqCatalogReconciliationStore(database) {
        sessionNumber += 1
        "scan-$sessionNumber"
      }
    val books: BookRepository = JooqBookRepository(database)
    val series: SeriesRepository = JooqSeriesRepository(database)

    fun scan(
      candidates: List<CatalogCandidate>,
      deep: Boolean = false,
      failedEntries: Long = 0,
    ) =
      store.begin(LIBRARY_ID, deep, sessionNumber.toLong() + 1).let { session ->
        store.stage(session, candidates)
        store.complete(
          sessionId = session,
          failedEntries = failedEntries,
          ignoredFiles = 0,
          completedAtMillis = sessionNumber.toLong() + 100,
        )
      }

    fun taskCount(): Long =
      database.dsl
        .fetchOne("SELECT count(*) FROM task")
        ?.get(0)
        ?.let { it as Number }
        ?.toLong()
        ?: 0L

    fun clearTasks() {
      database.dsl.execute("DELETE FROM task")
    }

    fun candidateTableIsEmpty(): Boolean =
      database.dsl.fetchOne("SELECT count(*) FROM catalog_scan_candidate")
        ?.get(0)
        ?.let { it as Number }
        ?.toLong() == 0L
  }

  private fun candidate(
    relativePath: String,
    identity: String?,
    mediaKind: MediaKind = MediaKind.COMIC_ARCHIVE,
  ): CatalogCandidate {
    val parent = relativePath.substringBeforeLast('/', ".")
    return CatalogCandidate(
      relativePath = relativePath,
      sourceItemId = "file:///synthetic/$relativePath",
      sourceIdentity = identity,
      name = relativePath.substringAfterLast('/').substringBeforeLast('.'),
      mediaKind = mediaKind,
      fileSize = 100L,
      fileModifiedAtMillis = 100L,
      seriesRelativePath = parent,
      seriesSourceItemId = "file:///synthetic/$parent",
      seriesName = if (parent == ".") "Synthetic library" else parent.substringAfterLast('/'),
      oneshot = false,
    )
  }

  companion object {
    private val LIBRARY_ID = LibraryId("library-1")
  }
}
