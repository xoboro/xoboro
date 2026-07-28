package io.xoboro.server.metadata

import io.xoboro.core.application.SeriesMetadataPatch
import io.xoboro.core.application.SeriesMetadataProvider
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesStatus

class OneShotSeriesMetadataProvider(
  private val bookMetadata: BookMetadataRepository,
) : SeriesMetadataProvider {
  override fun provide(
    library: Library,
    series: Series,
    books: List<Book>,
  ): SeriesMetadataPatch? {
    if (!series.oneshot) return null
    val metadata =
      books
        .asSequence()
        .sortedWith(compareBy<Book> { it.number }.thenBy(Book::relativePath))
        .mapNotNull { bookMetadata.findByBookIdOrNull(it.id) }
        .firstOrNull()
        ?: return null
    return SeriesMetadataPatch(
      title = metadata.title,
      titleSort = metadata.title,
      status = SeriesStatus.ENDED,
      summary = metadata.summary,
      totalBookCount = 1,
    )
  }
}
