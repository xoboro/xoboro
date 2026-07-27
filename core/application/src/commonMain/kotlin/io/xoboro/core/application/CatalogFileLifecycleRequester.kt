package io.xoboro.core.application

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId

data class BookImportCommand(
  val sourceFile: String,
  val seriesId: SeriesId,
  val upgradeBookId: BookId? = null,
  val destinationName: String? = null,
) {
  init {
    require(sourceFile.isNotBlank()) { "Import source file must not be blank" }
    require(destinationName == null || destinationName.isNotBlank()) {
      "Import destination name must be null or non-blank"
    }
  }
}

interface CatalogFileLifecycleRequester {
  fun importBooks(
    books: List<BookImportCommand>,
    copyMode: SourceCopyMode,
  ): Int

  fun deleteBook(id: BookId): Boolean

  fun deleteSeries(id: SeriesId): Boolean
}
