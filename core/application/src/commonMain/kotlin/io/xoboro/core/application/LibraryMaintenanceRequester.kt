package io.xoboro.core.application

import io.xoboro.core.domain.LibraryId

interface LibraryMaintenanceRequester {
  fun analyze(libraryId: LibraryId): Int

  fun refreshMetadata(libraryId: LibraryId): Int

  fun emptyTrash(libraryId: LibraryId): Boolean
}

data class EmptyTrashResult(
  val deletedBooks: Int,
  val deletedSeries: Int,
) {
  init {
    require(deletedBooks >= 0) { "Deleted book count must not be negative" }
    require(deletedSeries >= 0) { "Deleted series count must not be negative" }
  }
}

fun interface LibraryTrashStore {
  fun emptyTrash(libraryId: LibraryId): EmptyTrashResult
}
