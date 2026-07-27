package io.xoboro.core.application

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogScannerTest {
  @Test
  fun `filters media derives series and stages bounded batches`() {
    val inventory =
      FakeInventory(
        files =
          listOf(
            sourceFile("Root.CBZ"),
            sourceFile("Series/001.cbz"),
            sourceFile("Series/notes.txt"),
            sourceFile("OneShots/Special.epub"),
            sourceFile("Disabled/document.pdf"),
          ),
      )
    val store = RecordingStore()
    val times = ArrayDeque(listOf(10L, 20L))
    val scanner =
      CatalogScanner(
        inventories = listOf(inventory),
        reconciliationStore = store,
        currentTimeMillis = times::removeFirst,
        batchSize = 2,
      )
    val library =
      libraryFixture(
        settings =
          LibrarySettings(
            scanPdf = false,
            oneshotsDirectory = "oneshots",
          ),
      )

    val result = scanner.scan(library, deep = true)

    assertEquals(result, store.result)
    assertEquals(listOf(2, 1), store.staged.map(List<CatalogCandidate>::size))
    val candidates = store.staged.flatten()
    assertEquals(listOf("Root.CBZ", "Series/001.cbz", "OneShots/Special.epub"), candidates.map { it.relativePath })
    assertEquals(CatalogScanner.ROOT_SERIES_PATH, candidates[0].seriesRelativePath)
    assertEquals("Synthetic library", candidates[0].seriesName)
    assertEquals("Series", candidates[1].seriesName)
    assertEquals(MediaKind.COMIC_ARCHIVE, candidates[1].mediaKind)
    assertEquals("OneShots/Special.epub", candidates[2].seriesRelativePath)
    assertEquals("Special", candidates[2].seriesName)
    assertEquals(true, candidates[2].oneshot)
    assertEquals(2L, store.ignoredFiles)
    assertEquals(10L, store.startedAtMillis)
    assertEquals(20L, store.completedAtMillis)
    assertEquals(true, store.deep)
  }

  @Test
  fun `passes exclusions and preserves inventory failure count`() {
    val inventory =
      FakeInventory(
        files = listOf(sourceFile("Series/book.cbz")),
        summary =
          SourceInventorySummary(
            visitedDirectories = 1,
            emittedFiles = 1,
            skippedDirectories = 1,
            failedEntries = 2,
          ),
      )
    val store = RecordingStore(result = reconciliationResult(failedEntries = 2, partial = true))
    val times = ArrayDeque(listOf(10L, 20L))
    val scanner =
      CatalogScanner(
        inventories = listOf(inventory),
        reconciliationStore = store,
        currentTimeMillis = times::removeFirst,
      )
    val library =
      libraryFixture(
        settings = LibrarySettings(scanDirectoryExclusions = setOf("cache")),
      )

    scanner.scan(library, deep = false)

    assertEquals(setOf("cache"), inventory.receivedExclusions)
    assertEquals(2L, store.failedEntries)
  }

  @Test
  fun `aborts a session when inventory fails`() {
    val inventory = FakeInventory(failure = IllegalStateException("synthetic failure"))
    val store = RecordingStore()
    val times = ArrayDeque(listOf(10L, 11L))
    val scanner =
      CatalogScanner(
        inventories = listOf(inventory),
        reconciliationStore = store,
        currentTimeMillis = times::removeFirst,
      )

    assertFailsWith<IllegalStateException> {
      scanner.scan(libraryFixture(), deep = false)
    }

    assertEquals(11L, store.abortedAtMillis)
  }

  @Test
  fun `rejects unknown and duplicate inventories`() {
    val store = RecordingStore()
    val scanner =
      CatalogScanner(
        inventories = emptyList(),
        reconciliationStore = store,
        currentTimeMillis = { 1L },
      )
    assertFailsWith<UnknownSourceInventoryException> {
      scanner.scan(libraryFixture(), deep = false)
    }
    assertFailsWith<IllegalArgumentException> {
      CatalogScanner(
        inventories = listOf(FakeInventory(), FakeInventory()),
        reconciliationStore = store,
        currentTimeMillis = { 1L },
      )
    }
  }

  private class FakeInventory(
    val files: List<SourceFile> = emptyList(),
    private val summary: SourceInventorySummary =
      SourceInventorySummary(
        visitedDirectories = 1,
        emittedFiles = files.size.toLong(),
        skippedDirectories = 0,
        failedEntries = 0,
      ),
    private val failure: Throwable? = null,
    override val sourceId: String = "local",
  ) : SourceInventory {
    var receivedExclusions: Set<String> = emptySet()

    override fun inventory(
      rootItemId: String,
      directoryExclusions: Set<String>,
      onFile: (SourceFile) -> Unit,
      onFailure: (SourceInventoryFailure) -> Unit,
    ): SourceInventorySummary {
      receivedExclusions = directoryExclusions
      failure?.let { throw it }
      files.forEach(onFile)
      return summary
    }
  }

  private class RecordingStore(
    val result: CatalogReconciliationResult = reconciliationResult(),
  ) : CatalogReconciliationStore {
    val sessionId = ScanSessionId("scan-1")
    val staged = mutableListOf<List<CatalogCandidate>>()
    var deep: Boolean? = null
    var startedAtMillis: Long? = null
    var completedAtMillis: Long? = null
    var abortedAtMillis: Long? = null
    var failedEntries: Long? = null
    var ignoredFiles: Long? = null

    override fun begin(
      libraryId: LibraryId,
      deep: Boolean,
      startedAtMillis: Long,
    ): ScanSessionId {
      this.deep = deep
      this.startedAtMillis = startedAtMillis
      return sessionId
    }

    override fun stage(
      sessionId: ScanSessionId,
      candidates: List<CatalogCandidate>,
    ) {
      assertEquals(this.sessionId, sessionId)
      staged += candidates
    }

    override fun complete(
      sessionId: ScanSessionId,
      failedEntries: Long,
      ignoredFiles: Long,
      completedAtMillis: Long,
    ): CatalogReconciliationResult {
      assertEquals(this.sessionId, sessionId)
      this.failedEntries = failedEntries
      this.ignoredFiles = ignoredFiles
      this.completedAtMillis = completedAtMillis
      return result
    }

    override fun abort(
      sessionId: ScanSessionId,
      abortedAtMillis: Long,
    ) {
      assertEquals(this.sessionId, sessionId)
      this.abortedAtMillis = abortedAtMillis
    }
  }

  private fun sourceFile(relativePath: String): SourceFile =
    SourceFile(
      itemId = "file:///synthetic/$relativePath",
      parentItemId =
        "file:///synthetic/${relativePath.substringBeforeLast('/', missingDelimiterValue = "")}",
      identity = "identity-$relativePath",
      relativePath = relativePath,
      name = relativePath.substringAfterLast('/'),
      extension = relativePath.substringAfterLast('.', "").lowercase(),
      size = 1L,
      modifiedAtMillis = 1L,
    )

  private fun libraryFixture(
    settings: LibrarySettings = LibrarySettings(),
  ): Library =
    Library(
      id = LibraryId("library-1"),
      name = "Synthetic library",
      root = SourceLocation("local", "file:///synthetic"),
      settings = settings,
      createdAtMillis = 1L,
    )

  companion object {
    private fun reconciliationResult(
      failedEntries: Long = 0,
      partial: Boolean = false,
    ): CatalogReconciliationResult =
      CatalogReconciliationResult(
        addedBooks = 0,
        changedBooks = 0,
        movedBooks = 0,
        restoredBooks = 0,
        deletedBooks = 0,
        addedSeries = 0,
        restoredSeries = 0,
        deletedSeries = 0,
        ignoredFiles = 0,
        failedEntries = failedEntries,
        partial = partial,
      )
  }
}
