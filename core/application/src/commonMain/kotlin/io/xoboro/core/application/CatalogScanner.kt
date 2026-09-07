package io.xoboro.core.application

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.SeriesId

data class ScanSessionId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Scan session ID must not be blank" }
  }
}

data class CatalogCandidate(
  val relativePath: String,
  val sourceItemId: String,
  val sourceIdentity: String?,
  val name: String,
  val mediaKind: MediaKind,
  val fileSize: Long,
  val fileModifiedAtMillis: Long,
  val seriesRelativePath: String,
  val seriesSourceItemId: String,
  val seriesName: String,
  val oneshot: Boolean,
) {
  init {
    require(relativePath.isNotBlank()) { "Candidate relative path must not be blank" }
    require(sourceItemId.isNotBlank()) { "Candidate source item ID must not be blank" }
    require(sourceIdentity == null || sourceIdentity.isNotBlank()) {
      "Candidate source identity must be null or non-blank"
    }
    require(name.isNotBlank()) { "Candidate name must not be blank" }
    require(fileSize >= 0) { "Candidate file size must not be negative" }
    require(fileModifiedAtMillis >= 0) { "Candidate file timestamp must not be negative" }
    require(seriesRelativePath.isNotBlank()) { "Candidate series path must not be blank" }
    require(seriesSourceItemId.isNotBlank()) { "Candidate series source item ID must not be blank" }
    require(seriesName.isNotBlank()) { "Candidate series name must not be blank" }
  }
}

data class CatalogReconciliationResult(
  val addedBooks: Long,
  val changedBooks: Long,
  val movedBooks: Long,
  val restoredBooks: Long,
  val deletedBooks: Long,
  val addedSeries: Long,
  val restoredSeries: Long,
  val deletedSeries: Long,
  val ignoredFiles: Long,
  val failedEntries: Long,
  val partial: Boolean,
) {
  init {
    val counts =
      listOf(
        addedBooks,
        changedBooks,
        movedBooks,
        restoredBooks,
        deletedBooks,
        addedSeries,
        restoredSeries,
        deletedSeries,
        ignoredFiles,
        failedEntries,
      )
    require(counts.none { it < 0 }) { "Reconciliation counts must not be negative" }
    require(partial == (failedEntries > 0)) {
      "A reconciliation is partial exactly when inventory entries failed"
    }
  }
}

enum class CatalogMutationKind {
  ADDED,
  UPDATED,
  DELETED,
}

sealed interface CatalogMutationEvent {
  val kind: CatalogMutationKind
  val libraryId: LibraryId

  data class Book(
    override val kind: CatalogMutationKind,
    val bookId: BookId,
    val seriesId: SeriesId,
    override val libraryId: LibraryId,
  ) : CatalogMutationEvent

  data class Series(
    override val kind: CatalogMutationKind,
    val seriesId: SeriesId,
    override val libraryId: LibraryId,
  ) : CatalogMutationEvent
}

fun interface CatalogMutationEventPublisher {
  fun publish(event: CatalogMutationEvent)
}

data class CatalogImportEvent(
  val bookId: BookId?,
  val sourceFile: String,
  val success: Boolean,
  val message: String? = null,
) {
  init {
    require(sourceFile.isNotBlank()) { "Import source file must not be blank" }
    require(message == null || message.isNotBlank()) {
      "Import failure message must be null or non-blank"
    }
  }
}

fun interface CatalogImportEventPublisher {
  fun publish(event: CatalogImportEvent)
}

interface CatalogReconciliationStore {
  fun begin(
    libraryId: LibraryId,
    deep: Boolean,
    startedAtMillis: Long,
  ): ScanSessionId

  fun stage(
    sessionId: ScanSessionId,
    candidates: List<CatalogCandidate>,
  )

  /**
   * Relative paths of staged candidates whose file name could name one volume of a multi-volume
   * archive set.
   *
   * A coarse filter is enough and is the point: the store narrows to rows worth looking at, and
   * [RarVolumeNames] decides. Keeping the naming rule out of SQL means it can be read and tested as
   * one thing, and it means the store cannot drift from the parser.
   */
  fun stagedVolumeCandidatePaths(sessionId: ScanSessionId): List<String>

  /** Removes staged candidates by relative path, returning how many were removed. */
  fun unstage(
    sessionId: ScanSessionId,
    relativePaths: Collection<String>,
  ): Int

  fun complete(
    sessionId: ScanSessionId,
    failedEntries: Long,
    ignoredFiles: Long,
    completedAtMillis: Long,
  ): CatalogReconciliationResult

  fun abort(
    sessionId: ScanSessionId,
    abortedAtMillis: Long,
  )
}

class UnknownSourceInventoryException(
  sourceId: String,
) : IllegalArgumentException("No inventory registered for source: $sourceId")

class CatalogScanner(
  inventories: Collection<SourceInventory>,
  private val reconciliationStore: CatalogReconciliationStore,
  private val checkpointStore: CatalogScanCheckpointStore? = null,
  private val currentTimeMillis: () -> Long,
  private val batchSize: Int = 500,
) {
  private val inventoriesBySourceId = inventories.associateBy(SourceInventory::sourceId)

  init {
    require(batchSize > 0) { "Inventory batch size must be positive" }
    require(inventories.none { it.sourceId.isBlank() }) {
      "Inventory source IDs must not be blank"
    }
    require(inventoriesBySourceId.size == inventories.size) {
      "Inventory source IDs must be unique"
    }
  }

  fun scan(
    library: Library,
    deep: Boolean,
  ): CatalogReconciliationResult {
    val inventory =
      inventoriesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceInventoryException(library.root.sourceId)
    val sourceFingerprint = inventory.fingerprintOrNull(library, deep)
    if (
      sourceFingerprint != null &&
        sourceFingerprint.failedEntries == 0L &&
        checkpointStore.matchesSafely(library, sourceFingerprint.value)
    ) {
      return unchangedResult()
    }

    val startedAtMillis = currentTimeMillis()
    require(startedAtMillis >= 0) { "Scan timestamp must not be negative" }
    val session = reconciliationStore.begin(library.id, deep, startedAtMillis)
    val batch = ArrayList<CatalogCandidate>(batchSize)
    var ignoredFiles = 0L

    return try {
      val summary =
        inventory.inventory(
          rootItemId = library.root.itemId,
          directoryExclusions = library.settings.scanDirectoryExclusions,
          onFile = { file ->
            file.toCandidate(library)?.let { candidate ->
              batch += candidate
              if (batch.size == batchSize) {
                reconciliationStore.stage(session, batch.toList())
                batch.clear()
              }
            } ?: run {
              ignoredFiles += 1
            }
          },
        )
      if (batch.isNotEmpty()) {
        reconciliationStore.stage(session, batch.toList())
      }
      // Runs after every candidate is staged, because the decision needs the whole set: a continuation
      // volume is only recognisable as one when its first volume is present beside it. Doing this in
      // the streaming callback would mean buffering the entire library in memory.
      ignoredFiles += suppressContinuationVolumes(session)
      val completedAtMillis = currentTimeMillis()
      val result = reconciliationStore.complete(
        sessionId = session,
        failedEntries = summary.failedEntries,
        ignoredFiles = ignoredFiles,
        completedAtMillis = completedAtMillis,
      )
      if (
        !deep &&
          !result.partial &&
          summary.failedEntries == 0L &&
          (summary.fingerprint != null || sourceFingerprint?.failedEntries == 0L)
      ) {
        runCatching {
          checkpointStore?.replace(
            library,
            summary.fingerprint ?: sourceFingerprint!!.value,
            completedAtMillis,
          )
        }
      }
      result
    } catch (failure: Throwable) {
      runCatching {
        reconciliationStore.abort(session, currentTimeMillis())
      }.exceptionOrNull()?.let(failure::addSuppressed)
      throw failure
    }
  }

  private fun SourceInventory.fingerprintOrNull(
    library: Library,
    deep: Boolean,
  ): SourceInventoryFingerprint? {
    if (
      deep ||
        checkpointStore == null ||
        this !is FingerprintingSourceInventory
    ) return null
    if (!checkpointStore.existsSafely(library)) return null
    return try {
      fingerprint(
        rootItemId = library.root.itemId,
        directoryExclusions = library.settings.scanDirectoryExclusions,
      )
    } catch (failure: SourceInventoryUnavailableException) {
      throw failure
    } catch (_: Exception) {
      null
    }
  }

  private fun CatalogScanCheckpointStore?.matchesSafely(
    library: Library,
    fingerprint: String,
  ): Boolean = runCatching { this?.matches(library, fingerprint) == true }.getOrDefault(false)

  private fun CatalogScanCheckpointStore?.existsSafely(library: Library): Boolean =
    runCatching { this?.exists(library) == true }.getOrDefault(false)

  /**
   * Drops staged candidates that are continuation volumes of a multi-volume archive whose first volume
   * is staged beside them, and returns how many were dropped so the scan can count them as ignored.
   *
   * A multi-volume set is one logical archive split across files. Only the first volume opens; every
   * other one surfaced as its own book with a media item that could never be read.
   *
   * Two conditions must hold together, and each guards against a different mistake:
   *
   * - The name matches the tool-generated `.partN.rar` shape ([RarVolumeNames]), so a book genuinely
   *   titled `Series - part 2.cbr` is untouched.
   * - **The first volume is present in the same directory.** A lone `x.part2.rar` with no `x.part1.rar`
   *   beside it is far more likely an oddly named book than half a set, and suppressing it would make
   *   a book silently vanish — strictly worse than leaving a broken one visible, which is what happens
   *   today and at least shows the user something is wrong.
   *
   * Grouping is per directory because a set lives in one directory. Two unrelated series that both
   * have a `part1`/`part2` pair in different folders must not interact.
   */
  private fun suppressContinuationVolumes(session: ScanSessionId): Long {
    val paths = reconciliationStore.stagedVolumeCandidatePaths(session)
    if (paths.isEmpty()) return 0
    val volumesBySet =
      paths
        .mapNotNull { path ->
          RarVolumeNames.parseOrNull(path.substringAfterLast('/'))?.let { volume ->
            Triple(path.substringBeforeLast('/', missingDelimiterValue = ""), volume, path)
          }
        }.groupBy { (directory, volume, _) -> directory to volume.stem }
    val suppressed =
      volumesBySet.values.flatMap { volumes ->
        if (volumes.none { (_, volume, _) -> volume.isFirst }) {
          emptyList()
        } else {
          volumes.filterNot { (_, volume, _) -> volume.isFirst }.map { (_, _, path) -> path }
        }
      }
    if (suppressed.isEmpty()) return 0
    return reconciliationStore.unstage(session, suppressed).toLong()
  }

  private fun SourceFile.toCandidate(library: Library): CatalogCandidate? {
    val mediaKind =
      when (extension) {
        "cbz", "zip", "cbr", "rar" ->
          MediaKind.COMIC_ARCHIVE.takeIf { library.settings.scanCbx }
        "pdf" -> MediaKind.PDF.takeIf { library.settings.scanPdf }
        "epub" -> MediaKind.EPUB.takeIf { library.settings.scanEpub }
        else -> null
      } ?: return null
    val bookName = name.substringBeforeLast('.', missingDelimiterValue = name)
    val parentPath = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
    val isOneshot =
      library.settings.oneshotsDirectory?.let { marker ->
        relativePath.contains(marker, ignoreCase = true)
      } ?: false
    val seriesPath =
      when {
        isOneshot -> relativePath
        parentPath.isBlank() -> ROOT_SERIES_PATH
        else -> parentPath
      }
    val seriesName =
      when {
        isOneshot -> bookName
        parentPath.isBlank() -> library.name
        else -> parentPath.substringAfterLast('/')
      }
    return CatalogCandidate(
      relativePath = relativePath,
      sourceItemId = itemId,
      sourceIdentity = identity,
      name = bookName,
      mediaKind = mediaKind,
      fileSize = size,
      fileModifiedAtMillis = modifiedAtMillis,
      seriesRelativePath = seriesPath,
      seriesSourceItemId = if (isOneshot) itemId else parentItemId,
      seriesName = seriesName,
      oneshot = isOneshot,
    )
  }

  companion object {
    const val ROOT_SERIES_PATH: String = "."

    private fun unchangedResult(): CatalogReconciliationResult =
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
        failedEntries = 0,
        partial = false,
      )
  }
}
