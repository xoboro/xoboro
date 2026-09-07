package io.xoboro.core.application

open class SourceInventoryUnavailableException(
  message: String,
  cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class SourceFile(
  val itemId: String,
  val parentItemId: String,
  val identity: String?,
  val relativePath: String,
  val name: String,
  val extension: String,
  val size: Long,
  val modifiedAtMillis: Long,
) {
  init {
    require(itemId.isNotBlank()) { "Source item ID must not be blank" }
    require(parentItemId.isNotBlank()) { "Source parent item ID must not be blank" }
    require(identity == null || identity.isNotBlank()) {
      "Source identity must be null or non-blank"
    }
    require(relativePath.isNotBlank()) { "Source relative path must not be blank" }
    require(name.isNotBlank()) { "Source file name must not be blank" }
    require(extension == extension.lowercase()) { "Source extension must be lowercase" }
    require(size >= 0) { "Source file size must not be negative" }
    require(modifiedAtMillis >= 0) { "Source modification timestamp must not be negative" }
  }
}

data class SourceInventoryFailure(
  val itemId: String,
  val reason: String,
) {
  init {
    require(itemId.isNotBlank()) { "Failed source item ID must not be blank" }
    require(reason.isNotBlank()) { "Source failure reason must not be blank" }
  }
}

data class SourceInventorySummary(
  val visitedDirectories: Long,
  val emittedFiles: Long,
  val skippedDirectories: Long,
  val failedEntries: Long,
  val fingerprint: String? = null,
) {
  init {
    require(visitedDirectories >= 0) { "Visited directory count must not be negative" }
    require(emittedFiles >= 0) { "Emitted file count must not be negative" }
    require(skippedDirectories >= 0) { "Skipped directory count must not be negative" }
    require(failedEntries >= 0) { "Failed entry count must not be negative" }
    require(fingerprint == null || fingerprint.isNotBlank()) {
      "Inventory fingerprint must be null or non-blank"
    }
  }
}

interface SourceInventory {
  val sourceId: String

  fun inventory(
    rootItemId: String,
    directoryExclusions: Set<String> = emptySet(),
    onFile: (SourceFile) -> Unit,
    onFailure: (SourceInventoryFailure) -> Unit = {},
  ): SourceInventorySummary
}
