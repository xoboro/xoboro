package io.xoboro.server.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogSqlFromTest {
  private fun bookFrom() =
    CatalogSqlFrom(
      root = "book b",
      joins =
        listOf(
          CatalogSqlJoin("s", "JOIN series s ON s.id = b.series_id"),
          CatalogSqlJoin("bm", "JOIN book_metadata bm ON bm.book_id = b.id"),
          CatalogSqlJoin("sm", "JOIN series_metadata sm ON sm.series_id = b.series_id"),
          CatalogSqlJoin(
            alias = "sort_progress",
            sql = "LEFT JOIN read_progress sort_progress ON sort_progress.user_id = ?",
            bindings = listOf("user-1"),
          ),
        ),
    )

  @Test
  fun `a query that reads no join renders the root alone`() {
    val rendered = bookFrom().readBy("b.deleted_at_ms IS NULL")

    assertEquals("book b", rendered.sql)
    assertEquals(emptyList(), rendered.bindings)
  }

  @Test
  fun `a query keeps only the joins it reads`() {
    val rendered = bookFrom().readBy("b.deleted_at_ms IS NULL", "sm.title_sort ASC")

    assertTrue(rendered.sql.contains("JOIN series_metadata sm"), rendered.sql)
    assertFalse(rendered.sql.contains("JOIN book_metadata bm"), rendered.sql)
    assertFalse(rendered.sql.contains("JOIN series s"), rendered.sql)
  }

  @Test
  fun `a dropped join takes its bindings with it`() {
    assertEquals(emptyList(), bookFrom().readBy("b.id = ?").bindings)
    assertEquals(listOf("user-1"), bookFrom().readBy("sort_progress.read_at_ms DESC").bindings)
  }

  /**
   * `series_progress.` ends in the characters `s.`, so substring matching would read it as a
   * reference to the `series` join and no query would ever drop one.
   */
  @Test
  fun `an alias is matched as a whole word, not as the tail of another name`() {
    val rendered =
      bookFrom().readBy(
        """
        EXISTS (
          SELECT 1 FROM read_progress_series series_progress
          WHERE series_progress.series_id = b.series_id
        )
        """.trimIndent(),
      )

    assertEquals("book b", rendered.sql)
  }

  @Test
  fun `a join reached only through another join's ON clause is kept`() {
    val from =
      CatalogSqlFrom(
        root = "series s",
        joins =
          listOf(
            CatalogSqlJoin("sm", "JOIN series_metadata sm ON sm.series_id = s.id"),
            CatalogSqlJoin("ba", "LEFT JOIN aggregation ba ON ba.series_id = sm.series_id"),
          ),
      )

    val rendered = from.readBy("ba.release_date IS NOT NULL")

    assertTrue(rendered.sql.contains("LEFT JOIN aggregation ba"), rendered.sql)
    assertTrue(rendered.sql.contains("JOIN series_metadata sm"), rendered.sql)
  }

  @Test
  fun `a join that narrows the rows survives a query that never names it`() {
    val from =
      CatalogSqlFrom(
        root = "book b",
        joins =
          listOf(
            CatalogSqlJoin("bm", "JOIN book_metadata bm ON bm.book_id = b.id"),
            CatalogSqlJoin(
              alias = "keep_progress",
              sql = "JOIN read_progress keep_progress ON keep_progress.user_id = ?",
              bindings = listOf("user-1"),
              narrowsRows = true,
            ),
          ),
      )

    val rendered = from.readBy("b.deleted_at_ms IS NULL")

    assertTrue(rendered.sql.contains("JOIN read_progress keep_progress"), rendered.sql)
    assertFalse(rendered.sql.contains("JOIN book_metadata bm"), rendered.sql)
    assertEquals(listOf("user-1"), rendered.bindings)
  }

  @Test
  fun `kept joins render in declaration order so their bindings stay positional`() {
    val from =
      CatalogSqlFrom(
        root = "book b",
        joins =
          listOf(
            CatalogSqlJoin("first", "JOIN a first ON first.id = b.id AND first.x = ?", listOf(1)),
            CatalogSqlJoin(
              "second",
              "JOIN c second ON second.id = b.id AND second.x = ?",
              listOf(2),
            ),
          ),
      )

    assertEquals(listOf<Any?>(1, 2), from.readBy("second.y", "first.y").bindings)
  }
}
