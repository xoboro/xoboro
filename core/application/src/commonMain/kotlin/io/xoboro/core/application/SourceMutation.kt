package io.xoboro.core.application

enum class SourceCopyMode {
  MOVE,
  COPY,
  HARDLINK,
}

data class SourceImportRequest(
  val sourceFile: String,
  val destinationName: String?,
  val copyMode: SourceCopyMode,
  val replaceExisting: Boolean = false,
) {
  init {
    require(sourceFile.isNotBlank()) { "Import source file must not be blank" }
    require(destinationName == null || destinationName.isNotBlank()) {
      "Import destination name must be null or non-blank"
    }
  }
}

interface SourceMutationAccess {
  val sourceId: String

  fun delete(
    rootItemId: String,
    itemId: String,
  ): Boolean

  fun import(
    rootItemId: String,
    destinationParentItemId: String,
    request: SourceImportRequest,
  ): String

  fun removeArchiveEntries(
    rootItemId: String,
    itemId: String,
    entryNames: Set<String>,
  ): Int =
    throw UnsupportedOperationException(
      "Archive entry removal is not supported by source '$sourceId'",
    )
}
