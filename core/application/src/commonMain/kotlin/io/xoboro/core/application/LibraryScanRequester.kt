package io.xoboro.core.application

import io.xoboro.core.domain.LibraryId

fun interface LibraryScanRequester {
  fun request(
    libraryId: LibraryId,
    deep: Boolean,
  ): Boolean
}
