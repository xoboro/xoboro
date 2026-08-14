package io.xoboro.server.persistence

/**
 * A catalog listing's FROM clause, held as its joins rather than as one string so that each query
 * can drop the ones it never reads.
 *
 * SQLite does not eliminate a join it does not read, not even a LEFT JOIN on a unique key.
 * Measured against the live catalog (145,105 books): counting through
 * `book`+`series`+`book_metadata`+`series_metadata` costs 177ms, and counting the same rows
 * through `book` alone costs 4ms. They carry columns that a count does not select and an unsorted
 * query does not order by, so rendering the clause per query is what lets the count skip them.
 *
 * Dropping a metadata join cannot change the number of rows: `initialize_book_metadata` and
 * `initialize_series_metadata` (V14) create the metadata row with the book or series it belongs
 * to, and the foreign key removes it with them, so the join is one-to-one by construction rather
 * than by coincidence. A join that does narrow the rows says so with [CatalogSqlJoin.narrowsRows].
 */
internal class CatalogSqlFrom(
  private val root: String,
  private val joins: List<CatalogSqlJoin>,
) {
  /**
   * The clause narrowed to the joins that [readers] reference, either directly or through the ON
   * clause of a join that is itself kept, plus every join that narrows the rows.
   */
  fun readBy(vararg readers: String): CatalogSqlFromRendering {
    val required =
      joins.filter(CatalogSqlJoin::narrowsRows).map(CatalogSqlJoin::alias).toMutableSet()
    var frontier = readers.toList() + joins.filter { it.alias in required }.map(CatalogSqlJoin::sql)
    while (frontier.isNotEmpty()) {
      val reached = joins.filter { it.alias !in required && frontier.any(it::isReferencedBy) }
      reached.forEach { required += it.alias }
      frontier = reached.map(CatalogSqlJoin::sql)
    }
    val kept = joins.filter { it.alias in required }
    return CatalogSqlFromRendering(
      sql = (listOf(root) + kept.map(CatalogSqlJoin::sql)).joinToString("\n"),
      bindings = kept.flatMap(CatalogSqlJoin::bindings),
    )
  }
}

/**
 * One join of a [CatalogSqlFrom], kept next to the bindings its ON clause carries so that dropping
 * the join drops its parameters with it and the positional ones that follow still line up.
 */
internal data class CatalogSqlJoin(
  val alias: String,
  val sql: String,
  val bindings: List<Any?> = emptyList(),
  /**
   * Whether dropping this join would change which rows the query returns. A join that only carries
   * columns can be dropped by a query that reads none of them; one that narrows the rows cannot,
   * however little of it the query names.
   */
  val narrowsRows: Boolean = false,
) {
  fun isReferencedBy(sql: String): Boolean = aliasReference.containsMatchIn(sql)

  /**
   * `alias.` where the alias is a whole word. Plain substring matching would read the `s.` that
   * ends `series_progress.` as a reference to the `series` join, and keep every join forever.
   */
  private val aliasReference = Regex("(?<![A-Za-z0-9_])${Regex.escape(alias)}\\.")
}

internal data class CatalogSqlFromRendering(
  val sql: String,
  val bindings: List<Any?>,
)
