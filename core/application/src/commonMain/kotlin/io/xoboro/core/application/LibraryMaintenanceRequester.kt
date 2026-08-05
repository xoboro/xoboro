package io.xoboro.core.application

import io.xoboro.core.domain.LibraryId

interface LibraryMaintenanceRequester {
  /**
   * Asks for every book in the library to be analyzed, and reports whether the request was taken.
   *
   * Reports a [TaskEnqueue] rather than a count of queued tasks. The count was a count of inserts
   * this call performed inline - one per book, tens of thousands of them inside a single request -
   * and that shape is what failed the request outright when a scan held the write lock. The work is
   * now queued as one task and fanned out by a worker, so there is no count to report at this point
   * and nothing left that a request can lose halfway.
   */
  fun analyze(libraryId: LibraryId): TaskEnqueue

  /** Asks for the library's metadata to be re-read from its sidecars. See [analyze]. */
  fun refreshMetadata(libraryId: LibraryId): TaskEnqueue

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
