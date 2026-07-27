package io.xoboro.server.persistence

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId
import org.jooq.DSLContext

internal fun DSLContext.rebuildBookSearchDocument(bookId: BookId) {
  execute(
    "DELETE FROM catalog_search_fts WHERE entity_type = 'BOOK' AND entity_id = ?",
    bookId.value,
  )
  execute(
    """
    INSERT INTO catalog_search_fts (
      entity_type, entity_id, title, summary, contributors, labels, identifiers
    )
    SELECT 'BOOK', entity_id, title, summary, contributors, labels, identifiers
    FROM catalog_book_search_source
    WHERE entity_id = ?
    """.trimIndent(),
    bookId.value,
  )
}

internal fun DSLContext.rebuildSeriesSearchDocument(
  seriesId: SeriesId,
  includeBooks: Boolean,
) {
  execute(
    "DELETE FROM catalog_search_fts WHERE entity_type = 'SERIES' AND entity_id = ?",
    seriesId.value,
  )
  execute(
    """
    INSERT INTO catalog_search_fts (
      entity_type, entity_id, title, summary, contributors, labels, identifiers
    )
    SELECT 'SERIES', entity_id, title, summary, contributors, labels, identifiers
    FROM catalog_series_search_source
    WHERE entity_id = ?
    """.trimIndent(),
    seriesId.value,
  )
  if (includeBooks) {
    execute(
      """
      DELETE FROM catalog_search_fts
      WHERE entity_type = 'BOOK' AND entity_id IN (
        SELECT id FROM book WHERE series_id = ?
      )
      """.trimIndent(),
      seriesId.value,
    )
    execute(
      """
      INSERT INTO catalog_search_fts (
        entity_type, entity_id, title, summary, contributors, labels, identifiers
      )
      SELECT 'BOOK', entity_id, title, summary, contributors, labels, identifiers
      FROM catalog_book_search_source
      WHERE entity_id IN (SELECT id FROM book WHERE series_id = ?)
      """.trimIndent(),
      seriesId.value,
    )
  }
}

internal fun DSLContext.rebuildBookAndParentSearchDocuments(bookId: BookId) {
  rebuildBookSearchDocument(bookId)
  fetchOne(
    "SELECT series_id FROM book WHERE id = ?",
    bookId.value,
  )?.get(0, String::class.java)
    ?.let { rebuildSeriesSearchDocument(SeriesId(it), includeBooks = false) }
}
