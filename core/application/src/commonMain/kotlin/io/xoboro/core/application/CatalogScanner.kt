package io.xoboro.core.application

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind

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
    val startedAtMillis = currentTimeMillis()
    require(startedAtMillis >= 0) { "Scan timestamp must not be negative" }
    val inventory =
      inventoriesBySourceId[library.root.sourceId]
        ?: throw UnknownSourceInventoryException(library.root.sourceId)
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
      reconciliationStore.complete(
        sessionId = session,
        failedEntries = summary.failedEntries,
        ignoredFiles = ignoredFiles,
        completedAtMillis = currentTimeMillis(),
      )
    } catch (failure: Throwable) {
      runCatching {
        reconciliationStore.abort(session, currentTimeMillis())
      }.exceptionOrNull()?.let(failure::addSuppressed)
      throw failure
    }
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
  }
}
