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
  rebuildBookTitleDocument(bookId)
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
  rebuildSeriesTitleDocument(seriesId, includeBooks)
}

/**
 * The same maintenance for the interior-match index, which holds titles only.
 *
 * It is a separate index because its tokeniser is: `catalog_search_fts` matches whole words from their
 * start, and `catalog_title_substring` matches any three-character fragment. Neither subsumes the
 * other - trigram cannot answer a query shorter than three characters, and word search cannot find the
 * middle of a name written without spaces - so both are kept current and the read path ORs them.
 */
private fun DSLContext.rebuildBookTitleDocument(bookId: BookId) {
  execute(
    "DELETE FROM catalog_title_substring WHERE entity_type = 'BOOK' AND entity_id = ?",
    bookId.value,
  )
  execute(
    """
    INSERT INTO catalog_title_substring (entity_type, entity_id, title)
    SELECT 'BOOK', entity_id, title
    FROM catalog_book_title_source
    WHERE entity_id = ?
    """.trimIndent(),
    bookId.value,
  )
}

private fun DSLContext.rebuildSeriesTitleDocument(
  seriesId: SeriesId,
  includeBooks: Boolean,
) {
  execute(
    "DELETE FROM catalog_title_substring WHERE entity_type = 'SERIES' AND entity_id = ?",
    seriesId.value,
  )
  execute(
    """
    INSERT INTO catalog_title_substring (entity_type, entity_id, title)
    SELECT 'SERIES', entity_id, title
    FROM catalog_series_title_source
    WHERE entity_id = ?
    """.trimIndent(),
    seriesId.value,
  )
  if (!includeBooks) return
  execute(
    """
    DELETE FROM catalog_title_substring
    WHERE entity_type = 'BOOK' AND entity_id IN (SELECT id FROM book WHERE series_id = ?)
    """.trimIndent(),
    seriesId.value,
  )
  execute(
    """
    INSERT INTO catalog_title_substring (entity_type, entity_id, title)
    SELECT 'BOOK', entity_id, title
    FROM catalog_book_title_source
    WHERE entity_id IN (SELECT id FROM book WHERE series_id = ?)
    """.trimIndent(),
    seriesId.value,
  )
}

internal fun DSLContext.rebuildBookAndParentSearchDocuments(bookId: BookId) {
  rebuildBookSearchDocument(bookId)
  fetchOne(
    "SELECT series_id FROM book WHERE id = ?",
    bookId.value,
  )?.get(0, String::class.java)
    ?.let { rebuildSeriesSearchDocument(SeriesId(it), includeBooks = false) }
}
