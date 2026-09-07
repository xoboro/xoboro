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
  fun `skips reconciliation when a regular fingerprint exactly matches its checkpoint`() {
    val inventory =
      FakeFingerprintInventory(
        files = listOf(sourceFile("Series/book.cbz")),
        fingerprint = SourceInventoryFingerprint("same", failedEntries = 0),
      )
    val store = RecordingStore()
    val checkpoints = RecordingCheckpointStore(matches = true)
    val scanner =
      CatalogScanner(
        inventories = listOf(inventory),
        reconciliationStore = store,
        checkpointStore = checkpoints,
        currentTimeMillis = { 10L },
      )

    val result = scanner.scan(libraryFixture(), deep = false)

    assertEquals(reconciliationResult(), result)
    assertEquals(1, inventory.fingerprintCalls)
    assertEquals(0, inventory.inventoryCalls)
    assertEquals(null, store.startedAtMillis)
    assertEquals(emptyList(), checkpoints.replacements)
  }

  @Test
  fun `first regular scan inventories once and checkpoints the inventory fingerprint`() {
    val inventory =
      FakeFingerprintInventory(
        files = listOf(sourceFile("Series/book.cbz")),
        fingerprint = SourceInventoryFingerprint("probe", failedEntries = 0),
        summary =
          SourceInventorySummary(
            visitedDirectories = 1,
            emittedFiles = 1,
            skippedDirectories = 0,
            failedEntries = 0,
            fingerprint = "inventory",
          ),
      )
    val store = RecordingStore()
    val checkpoints = RecordingCheckpointStore(matches = false, exists = false)
    val times = ArrayDeque(listOf(10L, 20L))
    val scanner =
      CatalogScanner(
        inventories = listOf(inventory),
        reconciliationStore = store,
        checkpointStore = checkpoints,
        currentTimeMillis = times::removeFirst,
      )

    scanner.scan(libraryFixture(), deep = false)

    assertEquals(0, inventory.fingerprintCalls)
    assertEquals(1, inventory.inventoryCalls)
    assertEquals(listOf("inventory" to 20L), checkpoints.replacements)
  }

  @Test
  fun `reconciles a changed fingerprint and checkpoints it after completion`() {
    val inventory =
      FakeFingerprintInventory(
        files = listOf(sourceFile("Series/book.cbz")),
        fingerprint = SourceInventoryFingerprint("changed", failedEntries = 0),
      )
    val store = RecordingStore()
    val checkpoints = RecordingCheckpointStore(matches = false)
    val times = ArrayDeque(listOf(10L, 20L))
    val scanner =
      CatalogScanner(
        inventories = listOf(inventory),
        reconciliationStore = store,
        checkpointStore = checkpoints,
        currentTimeMillis = times::removeFirst,
      )

    scanner.scan(libraryFixture(), deep = false)

    assertEquals(1, inventory.fingerprintCalls)
    assertEquals(1, inventory.inventoryCalls)
    assertEquals(listOf("changed" to 20L), checkpoints.replacements)
  }

  @Test
  fun `deep scans never probe or replace a fingerprint checkpoint`() {
    val inventory =
      FakeFingerprintInventory(
        files = listOf(sourceFile("Series/book.cbz")),
        fingerprint = SourceInventoryFingerprint("same", failedEntries = 0),
      )
    val store = RecordingStore()
    val checkpoints = RecordingCheckpointStore(matches = true)
    val times = ArrayDeque(listOf(10L, 20L))
    val scanner =
      CatalogScanner(
        inventories = listOf(inventory),
        reconciliationStore = store,
        checkpointStore = checkpoints,
        currentTimeMillis = times::removeFirst,
      )

    scanner.scan(libraryFixture(), deep = true)

    assertEquals(0, inventory.fingerprintCalls)
    assertEquals(1, inventory.inventoryCalls)
    assertEquals(emptyList(), checkpoints.replacements)
  }

  @Test
  fun `a fingerprint probe with failed entries never skips or replaces`() {
    val inventory =
      FakeFingerprintInventory(
        files = listOf(sourceFile("Series/book.cbz")),
        fingerprint = SourceInventoryFingerprint("partial-probe", failedEntries = 1),
      )
    val store = RecordingStore()
    val checkpoints = RecordingCheckpointStore(matches = true)
    val times = ArrayDeque(listOf(10L, 20L))
    val scanner =
      CatalogScanner(
        inventories = listOf(inventory),
        reconciliationStore = store,
        checkpointStore = checkpoints,
        currentTimeMillis = times::removeFirst,
      )

    scanner.scan(libraryFixture(), deep = false)

    assertEquals(1, inventory.inventoryCalls)
    assertEquals(null, checkpoints.matchChecks)
    assertEquals(emptyList(), checkpoints.replacements)
  }

  @Test
  fun `a partial reconciliation never replaces a successful probe checkpoint`() {
    val partial =
      SourceInventorySummary(
        visitedDirectories = 1,
        emittedFiles = 1,
        skippedDirectories = 0,
        failedEntries = 1,
      )
    val inventory =
      FakeFingerprintInventory(
        files = listOf(sourceFile("Series/book.cbz")),
        fingerprint = SourceInventoryFingerprint("changed", failedEntries = 0),
        summary = partial,
      )
    val store = RecordingStore(result = reconciliationResult(failedEntries = 1, partial = true))
    val checkpoints = RecordingCheckpointStore(matches = false)
    val times = ArrayDeque(listOf(10L, 20L))
    val scanner =
      CatalogScanner(
        inventories = listOf(inventory),
        reconciliationStore = store,
        checkpointStore = checkpoints,
        currentTimeMillis = times::removeFirst,
      )

    scanner.scan(libraryFixture(), deep = false)

    assertEquals(emptyList(), checkpoints.replacements)
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

  private class FakeFingerprintInventory(
    private val files: List<SourceFile>,
    private val fingerprint: SourceInventoryFingerprint,
    private val summary: SourceInventorySummary =
      SourceInventorySummary(
        visitedDirectories = 1,
        emittedFiles = files.size.toLong(),
        skippedDirectories = 0,
        failedEntries = 0,
      ),
    override val sourceId: String = "local",
  ) : FingerprintingSourceInventory {
    var fingerprintCalls = 0
    var inventoryCalls = 0

    override fun fingerprint(
      rootItemId: String,
      directoryExclusions: Set<String>,
    ): SourceInventoryFingerprint {
      fingerprintCalls += 1
      return fingerprint
    }

    override fun inventory(
      rootItemId: String,
      directoryExclusions: Set<String>,
      onFile: (SourceFile) -> Unit,
      onFailure: (SourceInventoryFailure) -> Unit,
    ): SourceInventorySummary {
      inventoryCalls += 1
      files.forEach(onFile)
      return summary
    }
  }

  private class RecordingCheckpointStore(
    private val matches: Boolean,
    private val exists: Boolean = true,
  ) : CatalogScanCheckpointStore {
    var matchChecks: String? = null
    val replacements = mutableListOf<Pair<String, Long>>()

    override fun exists(library: Library): Boolean = exists

    override fun matches(
      library: Library,
      sourceFingerprint: String,
    ): Boolean {
      matchChecks = sourceFingerprint
      return matches
    }

    override fun replace(
      library: Library,
      sourceFingerprint: String,
      completedAtMillis: Long,
    ) {
      replacements += sourceFingerprint to completedAtMillis
    }
  }

  private class RecordingStore(
    val result: CatalogReconciliationResult = reconciliationResult(),
  ) : CatalogReconciliationStore {
    val sessionId = ScanSessionId("scan-1")
    val staged = mutableListOf<List<CatalogCandidate>>()
    val unstaged = mutableSetOf<String>()
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

    /**
     * Derived from what was staged rather than tracked separately, so the fake cannot report a volume
     * candidate the scanner never staged. The coarse `.part` filter mirrors the real store's `LIKE`.
     */
    override fun stagedVolumeCandidatePaths(sessionId: ScanSessionId): List<String> {
      assertEquals(this.sessionId, sessionId)
      return staged
        .flatten()
        .filterNot { it.relativePath in unstaged }
        .filter { it.mediaKind == MediaKind.COMIC_ARCHIVE }
        .map(CatalogCandidate::relativePath)
        .filter { it.lowercase().contains(".part") }
        .sorted()
    }

    override fun unstage(
      sessionId: ScanSessionId,
      relativePaths: Collection<String>,
    ): Int {
      assertEquals(this.sessionId, sessionId)
      unstaged += relativePaths
      return relativePaths.size
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

  @Test
  fun `suppresses continuation volumes of a multi-volume archive set`() {
    val inventory =
      FakeInventory(
        files =
          listOf(
            sourceFile("Series/Volume 01.part1.rar"),
            sourceFile("Series/Volume 01.part2.rar"),
            sourceFile("Series/Volume 01.part03.rar"),
            sourceFile("Series/Volume 02.cbz"),
          ),
      )
    val store = RecordingStore()
    val scanner = scanner(inventory, store)

    scanner.scan(libraryFixture(), deep = false)

    // Only the first volume survives: the others are continuations of one logical archive and could
    // never have opened on their own.
    assertEquals(
      listOf("Series/Volume 01.part1.rar", "Series/Volume 02.cbz"),
      store.staged.flatten().filterNot { it.relativePath in store.unstaged }.map { it.relativePath },
    )
    // Counted as ignored, so the scan reports them rather than making them vanish from every total.
    assertEquals(2L, store.ignoredFiles)
  }

  @Test
  fun `keeps a lone continuation volume whose first volume is absent`() {
    val inventory =
      FakeInventory(
        files =
          listOf(
            sourceFile("Series/Volume 01.part2.rar"),
            sourceFile("Series/Volume 01.part3.rar"),
          ),
      )
    val store = RecordingStore()
    val scanner = scanner(inventory, store)

    scanner.scan(libraryFixture(), deep = false)

    // Without a first volume these are far more likely oddly named books than half a set. Suppressing
    // them would make a book silently vanish, which is worse than leaving a broken one visible.
    assertEquals(emptySet(), store.unstaged)
    assertEquals(0L, store.ignoredFiles)
  }

  @Test
  fun `keeps a book whose title merely contains the word part`() {
    val inventory =
      FakeInventory(
        files =
          listOf(
            sourceFile("Series/Story.part1.rar"),
            sourceFile("Series/Story - part 2.cbr"),
            sourceFile("Series/Story part 3.cbr"),
          ),
      )
    val store = RecordingStore()
    val scanner = scanner(inventory, store)

    scanner.scan(libraryFixture(), deep = false)

    // The first-volume name is present, so a looser rule would have taken both real books with it.
    assertEquals(emptySet(), store.unstaged)
  }

  @Test
  fun `keeps sets in different directories apart`() {
    val inventory =
      FakeInventory(
        files =
          listOf(
            sourceFile("First/Volume.part1.rar"),
            sourceFile("Second/Volume.part2.rar"),
          ),
      )
    val store = RecordingStore()
    val scanner = scanner(inventory, store)

    scanner.scan(libraryFixture(), deep = false)

    // A set lives in one directory. The `part1` in First must not make the `part2` in Second a
    // continuation of it.
    assertEquals(emptySet(), store.unstaged)
  }

  private fun scanner(
    inventory: FakeInventory,
    store: RecordingStore,
  ): CatalogScanner {
    val times = ArrayDeque(listOf(10L, 20L))
    return CatalogScanner(
      inventories = listOf(inventory),
      reconciliationStore = store,
      currentTimeMillis = times::removeFirst,
    )
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
