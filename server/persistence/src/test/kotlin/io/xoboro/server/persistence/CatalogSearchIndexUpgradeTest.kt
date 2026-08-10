package io.xoboro.server.persistence

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource

/**
 * V36 on a catalogue that already has one, which is the only interesting case and the one every
 * other test misses: they open a fresh database, so V36 runs against two empty indexes and cannot
 * tell adoption from a rebuild.
 *
 * The word index is the largest table in the database and rebuilding it measured 52.2 s on the
 * deployed catalogue - a stall in front of every request while the process starts. V36 avoids it by
 * having the key table adopt the rowids the index already uses rather than assigning new ones. The
 * assertion that pins that is the *identity* of the rowid across the upgrade: a rebuild would also
 * leave one row per entity under a consistent key and satisfy every count here, and would differ
 * only in that the numbers changed.
 */
class CatalogSearchIndexUpgradeTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `the word index keeps the rowids it already had`() {
    val path = tempDirectory.resolve("upgrade.sqlite").toAbsolutePath()
    val before = seedCatalogueAtVersion35(path)

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      assertEquals(1, database.migrationResult.migrationsExecuted)

      assertEquals(
        before.bookFtsRowid,
        database.rowidOf("catalog_search_fts", "BOOK", BOOK_ID),
        "the book's word-index row must still be the row it was, not a rewritten copy",
      )
      assertEquals(
        before.seriesFtsRowid,
        database.rowidOf("catalog_search_fts", "SERIES", SERIES_ID),
      )
      assertEquals(
        before.bookFtsRowid,
        database.keyOf("BOOK", BOOK_ID),
        "and the key has to be the number the index is already using",
      )
      assertEquals(before.seriesFtsRowid, database.keyOf("SERIES", SERIES_ID))
      assertEquals(
        before.ftsDigest,
        database.digestOf("catalog_search_fts"),
        "adopting must not disturb the indexed text",
      )
    }
  }

  /**
   * The interior-match index cannot be adopted alongside the word index - its rowids are its own and
   * both cannot be the key - so it is the one index V36 rebuilds. Cheap: its source view carries one
   * correlated subquery where the word index's carries five.
   */
  @Test
  fun `the interior-match index is rebuilt onto the adopted keys`() {
    val path = tempDirectory.resolve("substring.sqlite").toAbsolutePath()
    val before = seedCatalogueAtVersion35(path)

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      assertEquals(
        database.keyOf("BOOK", BOOK_ID),
        database.rowidOf("catalog_title_substring", "BOOK", BOOK_ID),
      )
      assertEquals(
        database.keyOf("SERIES", SERIES_ID),
        database.rowidOf("catalog_title_substring", "SERIES", SERIES_ID),
      )
      assertEquals(before.titleDigest, database.digestOf("catalog_title_substring"))
      assertEquals(2, database.countOf("catalog_title_substring"))
    }
  }

  /**
   * An entity the word index never held has no rowid to adopt, and would join against nothing
   * forever after - silently unindexed, which no search can distinguish from an entity whose text is
   * empty. Its row is removed here before the upgrade to produce exactly that state.
   */
  @Test
  fun `an entity missing from the word index still gets a key`() {
    val path = tempDirectory.resolve("missing.sqlite").toAbsolutePath()
    val before = seedCatalogueAtVersion35(path)
    dataSource(path).connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "DELETE FROM catalog_search_fts WHERE entity_type = 'BOOK' AND entity_id = '$BOOK_ID'",
        )
      }
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val key = database.keyOf("BOOK", BOOK_ID)
      assertNotEquals(0L, key, "the book still needs a key it can be indexed under later")
      assertNotEquals(
        before.seriesFtsRowid,
        key,
        "and it must not be handed a number another entity's row is sitting on",
      )
      assertEquals(
        key,
        database.rowidOf("catalog_title_substring", "BOOK", BOOK_ID),
        "the rebuilt interior-match index writes it under that key",
      )
      // Stated rather than discovered later: the word index is adopted, not rebuilt, so a row that
      // was already missing stays missing. The symptom is an entity findable by fragment and not by
      // word. `rebuildBookSearchDocument` is what repairs one.
      assertEquals(
        0,
        database.countOf("catalog_search_fts", "BOOK", BOOK_ID),
        "adopting cannot restore a word-index row that was not there to adopt",
      )
    }
  }

  /**
   * A duplicated word-index row is the one pre-existing fault that could stop the upgrade dead: two
   * rows for one entity would claim the key twice and fail its `UNIQUE` constraint, aborting the
   * migration and leaving the server unable to start.
   *
   * Resolving it rather than tolerating it also matters. Deleting by entity took every row an entity
   * had, so a duplicate healed itself on the next write; deleting by rowid takes one, so a duplicate
   * that survived the upgrade would survive every write after it - hence the assertion that a rename
   * afterwards still leaves one row.
   */
  @Test
  fun `a duplicated word-index row does not stop the upgrade and does not survive it`() {
    val path = tempDirectory.resolve("duplicate.sqlite").toAbsolutePath()
    seedCatalogueAtVersion35(path)
    dataSource(path).connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO catalog_search_fts (
            entity_type, entity_id, title, summary, contributors, labels, identifiers
          )
          SELECT 'BOOK', entity_id, title, summary, contributors, labels, identifiers
          FROM catalog_book_search_source WHERE entity_id = '$BOOK_ID'
          """.trimIndent(),
        )
      }
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      assertEquals(
        1,
        database.countOf("catalog_search_fts", "BOOK", BOOK_ID),
        "the upgrade has to leave one row, not adopt both and not fail",
      )
      assertEquals(
        database.keyOf("BOOK", BOOK_ID),
        database.rowidOf("catalog_search_fts", "BOOK", BOOK_ID),
        "and the survivor has to be the one the key names",
      )

      database.dsl.execute("UPDATE book SET name = ? WHERE id = ?", "Renamed", BOOK_ID)

      assertEquals(
        1,
        database.countOf("catalog_search_fts", "BOOK", BOOK_ID),
        "a duplicate that outlived the upgrade would outlive every write after it",
      )
    }
  }

  /** The upgraded database has to behave like a freshly created one from here on. */
  @Test
  fun `deleting a book after the upgrade takes both index rows and its key`() {
    val path = tempDirectory.resolve("delete-after.sqlite").toAbsolutePath()
    seedCatalogueAtVersion35(path)

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      database.dsl.execute("DELETE FROM book WHERE id = ?", BOOK_ID)

      assertEquals(0, database.countOf("catalog_search_fts", "BOOK", BOOK_ID))
      assertEquals(0, database.countOf("catalog_title_substring", "BOOK", BOOK_ID))
      assertEquals(
        0,
        database.dsl
          .fetchOne(
            "SELECT count(*) FROM catalog_search_key WHERE entity_type = 'BOOK' AND entity_id = ?",
            BOOK_ID,
          )?.get(0, Int::class.java),
      )
    }
  }

  private fun dataSource(path: Path) = SQLiteDataSource().apply { url = "jdbc:sqlite:$path" }

  private fun seedCatalogueAtVersion35(path: Path): SeededCatalogue {
    val source = dataSource(path)
    Flyway
      .configure()
      .dataSource(source)
      .locations("classpath:db/migration")
      .target("35")
      .load()
      .migrate()
    source.connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
          VALUES ('$LIBRARY_ID', 'Synthetic library', 'file:///synthetic', 1, 1)
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO series (
            id, library_id, relative_uri, name, sort_title, created_at_ms, updated_at_ms,
            source_item_id, file_modified_ms, book_count, deleted_at_ms, oneshot
          ) VALUES (
            '$SERIES_ID', '$LIBRARY_ID', 'series', 'Synthetic series', 'Synthetic series', 1, 1,
            'file:///synthetic/series', 1, 1, NULL, 0
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO book (
            id, library_id, series_id, relative_uri, name, media_kind, file_size,
            file_modified_ms, created_at_ms, updated_at_ms, source_item_id, source_identity,
            file_hash, file_hash_koreader, number, deleted_at_ms, oneshot, media_item_type
          ) VALUES (
            '$BOOK_ID', '$LIBRARY_ID', '$SERIES_ID', 'series/book.cbz', 'Synthetic book',
            'COMIC_ARCHIVE', 100, 1, 1, 1, 'file:///synthetic/series/book.cbz', NULL, '', '', 1,
            NULL, 0, 'COMIC'
          )
          """.trimIndent(),
        )
      }
      return connection.createStatement().use { statement ->
        fun single(sql: String): Long =
          statement.executeQuery(sql).use { rows ->
            require(rows.next()) { "expected one row from: $sql" }
            rows.getLong(1)
          }
        SeededCatalogue(
          bookFtsRowid =
            single(
              "SELECT rowid FROM catalog_search_fts " +
                "WHERE entity_type = 'BOOK' AND entity_id = '$BOOK_ID'",
            ),
          seriesFtsRowid =
            single(
              "SELECT rowid FROM catalog_search_fts " +
                "WHERE entity_type = 'SERIES' AND entity_id = '$SERIES_ID'",
            ),
          ftsDigest = single(FTS_DIGEST_SQL),
          titleDigest = single(TITLE_DIGEST_SQL),
        )
      }
    }
  }

  private fun XoboroDatabase.rowidOf(
    table: String,
    entityType: String,
    entityId: String,
  ): Long =
    dsl
      .fetchOne(
        "SELECT rowid FROM $table WHERE entity_type = ? AND entity_id = ?",
        entityType,
        entityId,
      )?.get(0, Long::class.java)
      ?: error("$entityType $entityId is not indexed in $table")

  private fun XoboroDatabase.keyOf(
    entityType: String,
    entityId: String,
  ): Long =
    dsl
      .fetchOne(
        "SELECT index_rowid FROM catalog_search_key WHERE entity_type = ? AND entity_id = ?",
        entityType,
        entityId,
      )?.get(0, Long::class.java)
      ?: error("$entityType $entityId has no key")

  private fun XoboroDatabase.digestOf(table: String): Long =
    dsl
      .fetchOne(if (table == "catalog_search_fts") FTS_DIGEST_SQL else TITLE_DIGEST_SQL)
      ?.get(0, Long::class.java)
      ?: error("digest of $table returned nothing")

  private fun XoboroDatabase.countOf(table: String): Int =
    dsl.fetchOne("SELECT count(*) FROM $table")?.get(0, Int::class.java)
      ?: error("counting $table returned nothing")

  private fun XoboroDatabase.countOf(
    table: String,
    entityType: String,
    entityId: String,
  ): Int =
    dsl
      .fetchOne(
        "SELECT count(*) FROM $table WHERE entity_type = ? AND entity_id = ?",
        entityType,
        entityId,
      )?.get(0, Int::class.java)
      ?: error("counting $table returned nothing")

  private data class SeededCatalogue(
    val bookFtsRowid: Long,
    val seriesFtsRowid: Long,
    val ftsDigest: Long,
    val titleDigest: Long,
  )

  private companion object {
    private const val LIBRARY_ID = "library-1"
    private const val SERIES_ID = "series-1"
    private const val BOOK_ID = "book-1"

    // Length rather than content: it detects a changed index without reading anything out of one.
    private const val FTS_DIGEST_SQL =
      """
      SELECT coalesce(sum(length(coalesce(entity_type, '') || coalesce(entity_id, '')
        || coalesce(title, '') || coalesce(summary, '') || coalesce(contributors, '')
        || coalesce(labels, '') || coalesce(identifiers, ''))), 0)
      FROM catalog_search_fts
      """

    private const val TITLE_DIGEST_SQL =
      """
      SELECT coalesce(sum(length(coalesce(entity_type, '') || coalesce(entity_id, '')
        || coalesce(title, ''))), 0)
      FROM catalog_title_substring
      """
  }
}
