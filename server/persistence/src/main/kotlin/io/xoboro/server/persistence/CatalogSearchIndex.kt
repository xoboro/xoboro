package io.xoboro.server.persistence

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId
import org.jooq.DSLContext

/**
 * Index maintenance for the paths that write metadata rather than catalogue rows.
 *
 * Every statement here addresses an index row by the rowid `catalog_search_key` holds for the entity
 * (V36). The columns that name the entity are UNINDEXED in both FTS5 tables - they have to be, FTS5
 * offers no other way to carry a value it must not tokenise - so `WHERE entity_id = ?` can only be
 * answered by reading the entire index. Editing one book's metadata was paying a pass over the whole
 * catalogue for each index it maintains; the rowid is a b-tree seek.
 *
 * Each rebuild claims the key before it uses it. The claim is almost always a no-op, and it is what
 * makes the join below total: without a key the `INSERT ... SELECT` would match nothing and leave the
 * entity silently unindexed, which no search can distinguish from an entity that has no text.
 */
private const val CLAIM_BOOK_KEY = """
  INSERT INTO catalog_search_key (entity_type, entity_id) VALUES ('BOOK', ?)
  ON CONFLICT (entity_type, entity_id) DO NOTHING
"""

private const val CLAIM_SERIES_KEY = """
  INSERT INTO catalog_search_key (entity_type, entity_id) VALUES ('SERIES', ?)
  ON CONFLICT (entity_type, entity_id) DO NOTHING
"""

/**
 * `WHERE true` is not filtering anything. SQLite cannot tell where a `SELECT` ends and an upsert
 * clause begins - `ON` could just as well open a join constraint - so an `INSERT ... SELECT ... ON
 * CONFLICT` is a parse error until a `WHERE` closes the select off. The `VALUES` forms above need no
 * such marker.
 */
private const val CLAIM_KEYS_FOR_SERIES_BOOKS = """
  INSERT INTO catalog_search_key (entity_type, entity_id)
  SELECT 'BOOK', id FROM book WHERE series_id = ? AND true
  ON CONFLICT (entity_type, entity_id) DO NOTHING
"""

internal fun DSLContext.rebuildBookSearchDocument(bookId: BookId) {
  execute(CLAIM_BOOK_KEY, bookId.value)
  execute(
    """
    DELETE FROM catalog_search_fts
    WHERE rowid = (
      SELECT index_rowid FROM catalog_search_key
      WHERE entity_type = 'BOOK' AND entity_id = ?
    )
    """.trimIndent(),
    bookId.value,
  )
  execute(
    """
    INSERT INTO catalog_search_fts (
      rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
    )
    SELECT
      search_key.index_rowid, 'BOOK', source.entity_id, source.title, source.summary,
      source.contributors, source.labels, source.identifiers
    FROM catalog_book_search_source source
    JOIN catalog_search_key search_key
      ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
    WHERE source.entity_id = ?
    """.trimIndent(),
    bookId.value,
  )
  rebuildBookTitleDocument(bookId)
}

internal fun DSLContext.rebuildSeriesSearchDocument(
  seriesId: SeriesId,
  includeBooks: Boolean,
) {
  execute(CLAIM_SERIES_KEY, seriesId.value)
  execute(
    """
    DELETE FROM catalog_search_fts
    WHERE rowid = (
      SELECT index_rowid FROM catalog_search_key
      WHERE entity_type = 'SERIES' AND entity_id = ?
    )
    """.trimIndent(),
    seriesId.value,
  )
  execute(
    """
    INSERT INTO catalog_search_fts (
      rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
    )
    SELECT
      search_key.index_rowid, 'SERIES', source.entity_id, source.title, source.summary,
      source.contributors, source.labels, source.identifiers
    FROM catalog_series_search_source source
    JOIN catalog_search_key search_key
      ON search_key.entity_type = 'SERIES' AND search_key.entity_id = source.entity_id
    WHERE source.entity_id = ?
    """.trimIndent(),
    seriesId.value,
  )
  if (includeBooks) {
    execute(CLAIM_KEYS_FOR_SERIES_BOOKS, seriesId.value)
    // A series' books are addressed as one set rather than one at a time. Nothing here can loop, and
    // one pass for the whole series is what this path already cost.
    execute(
      """
      DELETE FROM catalog_search_fts
      WHERE rowid IN (
        SELECT index_rowid FROM catalog_search_key
        WHERE entity_type = 'BOOK'
          AND entity_id IN (SELECT id FROM book WHERE series_id = ?)
      )
      """.trimIndent(),
      seriesId.value,
    )
    execute(
      """
      INSERT INTO catalog_search_fts (
        rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
      )
      SELECT
        search_key.index_rowid, 'BOOK', source.entity_id, source.title, source.summary,
        source.contributors, source.labels, source.identifiers
      FROM catalog_book_search_source source
      JOIN catalog_search_key search_key
        ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
      WHERE source.entity_id IN (SELECT id FROM book WHERE series_id = ?)
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
 *
 * Both indexes store an entity under the same key. Neither holds more than one row per entity, so one
 * number addresses it in both, and a single allocation keeps them from disagreeing about which row
 * belongs to whom.
 */
private fun DSLContext.rebuildBookTitleDocument(bookId: BookId) {
  execute(CLAIM_BOOK_KEY, bookId.value)
  execute(
    """
    DELETE FROM catalog_title_substring
    WHERE rowid = (
      SELECT index_rowid FROM catalog_search_key
      WHERE entity_type = 'BOOK' AND entity_id = ?
    )
    """.trimIndent(),
    bookId.value,
  )
  execute(
    """
    INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
    SELECT search_key.index_rowid, 'BOOK', source.entity_id, source.title
    FROM catalog_book_title_source source
    JOIN catalog_search_key search_key
      ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
    WHERE source.entity_id = ?
    """.trimIndent(),
    bookId.value,
  )
}

private fun DSLContext.rebuildSeriesTitleDocument(
  seriesId: SeriesId,
  includeBooks: Boolean,
) {
  execute(CLAIM_SERIES_KEY, seriesId.value)
  execute(
    """
    DELETE FROM catalog_title_substring
    WHERE rowid = (
      SELECT index_rowid FROM catalog_search_key
      WHERE entity_type = 'SERIES' AND entity_id = ?
    )
    """.trimIndent(),
    seriesId.value,
  )
  execute(
    """
    INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
    SELECT search_key.index_rowid, 'SERIES', source.entity_id, source.title
    FROM catalog_series_title_source source
    JOIN catalog_search_key search_key
      ON search_key.entity_type = 'SERIES' AND search_key.entity_id = source.entity_id
    WHERE source.entity_id = ?
    """.trimIndent(),
    seriesId.value,
  )
  if (!includeBooks) return
  execute(CLAIM_KEYS_FOR_SERIES_BOOKS, seriesId.value)
  execute(
    """
    DELETE FROM catalog_title_substring
    WHERE rowid IN (
      SELECT index_rowid FROM catalog_search_key
      WHERE entity_type = 'BOOK'
        AND entity_id IN (SELECT id FROM book WHERE series_id = ?)
    )
    """.trimIndent(),
    seriesId.value,
  )
  execute(
    """
    INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
    SELECT search_key.index_rowid, 'BOOK', source.entity_id, source.title
    FROM catalog_book_title_source source
    JOIN catalog_search_key search_key
      ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
    WHERE source.entity_id IN (SELECT id FROM book WHERE series_id = ?)
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
