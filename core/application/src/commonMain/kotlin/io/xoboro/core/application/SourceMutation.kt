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

data class SourceMutationResult(
  val itemId: String,
  val relativePath: String,
  val name: String,
  val identity: String?,
  val size: Long,
  val modifiedAtMillis: Long,
) {
  init {
    require(itemId.isNotBlank()) { "Mutated source item ID must not be blank" }
    require(relativePath.isNotBlank()) { "Mutated source relative path must not be blank" }
    require(name.isNotBlank()) { "Mutated source name must not be blank" }
    require(identity == null || identity.isNotBlank()) {
      "Mutated source identity must be null or non-blank"
    }
    require(size >= 0) { "Mutated source size must not be negative" }
    require(modifiedAtMillis >= 0) { "Mutated source timestamp must not be negative" }
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

  fun renameExtension(
    rootItemId: String,
    itemId: String,
    extension: String,
  ): SourceMutationResult =
    throw UnsupportedOperationException(
      "Extension repair is not supported by source '$sourceId'",
    )

  fun replaceWithFile(
    rootItemId: String,
    itemId: String,
    replacementFile: String,
    extension: String,
  ): SourceMutationResult =
    throw UnsupportedOperationException(
      "Media replacement is not supported by source '$sourceId'",
    )
}
