package io.xoboro.server.persistence

import io.xoboro.core.domain.MediaFileKind
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.jooq.impl.DSL
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource

class XoboroDatabaseTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `runs migrations and configures durable SQLite pragmas`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("catalog.sqlite"))).use { database ->
      val tables =
        database.dsl.fetchValues(
          "SELECT name FROM sqlite_master WHERE type = 'table'",
          String::class.java,
        )

      assertTrue(
        tables.containsAll(
          listOf(
            "flyway_schema_history",
            "library",
            "library_scan_exclusion",
            "series",
            "book",
            "media_item",
            "task",
            "catalog_scan_session",
            "catalog_scan_candidate",
            "media",
            "book_page",
            "media_file",
            "media_position",
            "media_navigation_entry",
            "book_metadata",
            "book_metadata_author",
            "book_metadata_tag",
            "book_metadata_link",
            "series_metadata",
            "series_metadata_genre",
            "series_metadata_tag",
            "series_metadata_sharing_label",
            "series_metadata_link",
            "series_metadata_alternate_title",
            "read_progress",
            "read_progress_series",
            "user_account",
            "user_role",
            "user_library_sharing",
            "user_sharing_label",
            "user_api_key",
            "user_session",
            "authentication_activity",
            "server_setting",
            "client_setting_global",
            "client_setting_user",
            "user_announcement_read",
            "artwork_thumbnail",
            "page_hash_known",
            "historical_event",
            "historical_event_property",
            "sync_point",
            "media_sync_item",
            "media_sync_read_list",
            "media_sync_read_list_item",
            "media_sync_progress",
            "catalog_search_fts",
            "series_book_metadata_aggregation",
            "series_book_metadata_aggregation_author",
            "series_book_metadata_aggregation_tag",
            "series_book_metadata_aggregation_dirty",
          ),
        ),
      )
      assertEquals("wal", database.dsl.fetchValue("PRAGMA journal_mode", String::class.java))
      assertEquals(1, database.dsl.fetchValue("PRAGMA foreign_keys", Int::class.java))
      assertEquals(10_000, database.dsl.fetchValue("PRAGMA busy_timeout", Int::class.java))
      assertEquals(31, database.migrationResult.migrationsExecuted)
    }
  }

  @Test
  fun `registers case insensitive regular expressions on every pooled connection`() {
    XoboroDatabase
      .open(
        DatabaseConfig(
          path = tempDirectory.resolve("regexp.sqlite"),
          maximumPoolSize = 3,
        ),
      ).use { database ->
        val connections = List(3) { database.dataSource.connection }
        try {
          connections.forEach { connection ->
            connection.prepareStatement("SELECT ? REGEXP ?").use { statement ->
              statement.setString(1, "TheAlpha")
              statement.setString(2, "^the")
              statement.executeQuery().use { result ->
                assertTrue(result.next())
                assertEquals(1, result.getInt(1))
              }
            }
          }
          assertFailsWith<SQLException> {
            connections.first().createStatement().use { statement ->
              statement.executeQuery("SELECT 'Synthetic' REGEXP '['")
            }
          }
        } finally {
          connections.asReversed().forEach(AutoCloseable::close)
        }
      }
  }

  @Test
  fun `committed rows survive closing and reopening the database`() {
    val path = tempDirectory.resolve("restart.sqlite")

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      insertLibrary(database, id = "library-1", name = "Synthetic library")
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      assertEquals(
        "Synthetic library",
        database.dsl
          .fetchOne("SELECT name FROM library WHERE id = ?", "library-1")
          ?.get(0, String::class.java),
      )
      assertEquals(0, database.migrationResult.migrationsExecuted)
    }
  }

  @Test
  fun `upgrades a version one catalog without losing existing libraries`() {
    val path = tempDirectory.resolve("upgrade.sqlite").toAbsolutePath()
    val legacyDataSource =
      SQLiteDataSource().apply {
        url = "jdbc:sqlite:$path"
      }
    Flyway.configure()
      .dataSource(legacyDataSource)
      .locations("classpath:db/migration")
      .target("1")
      .load()
      .migrate()
    legacyDataSource.connection.use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
        VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "legacy-library")
        statement.setString(2, "Legacy synthetic library")
        statement.setString(3, "file:///synthetic/legacy")
        statement.setLong(4, 1L)
        statement.setLong(5, 1L)
        statement.executeUpdate()
      }
      connection.prepareStatement(
        """
        INSERT INTO series
          (id, library_id, relative_uri, name, sort_title, created_at_ms, updated_at_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "legacy-series")
        statement.setString(2, "legacy-library")
        statement.setString(3, "legacy-series-path")
        statement.setString(4, "Legacy synthetic series")
        statement.setString(5, "Legacy synthetic series")
        statement.setLong(6, 1L)
        statement.setLong(7, 1L)
        statement.executeUpdate()
      }
      connection.prepareStatement(
        """
        INSERT INTO book (
          id, library_id, series_id, relative_uri, name, media_kind, file_size,
          file_modified_ms, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "legacy-book")
        statement.setString(2, "legacy-library")
        statement.setString(3, "legacy-series")
        statement.setString(4, "legacy-series-path/book.cbz")
        statement.setString(5, "Legacy synthetic book")
        statement.setString(6, "COMIC_ARCHIVE")
        statement.setLong(7, 1L)
        statement.setLong(8, 1L)
        statement.setLong(9, 1L)
        statement.setLong(10, 1L)
        statement.executeUpdate()
      }
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      assertEquals(30, database.migrationResult.migrationsExecuted)
      assertEquals(
        "Legacy synthetic library",
        database.dsl
          .fetchOne("SELECT name FROM library WHERE id = ?", "legacy-library")
          ?.get(0, String::class.java),
      )
      assertEquals(
        "EVERY_6H",
        database.dsl
          .fetchOne("SELECT scan_interval FROM library WHERE id = ?", "legacy-library")
          ?.get(0, String::class.java),
      )
      assertEquals(
        "legacy-series-path",
        database.dsl
          .fetchOne("SELECT source_item_id FROM series WHERE id = ?", "legacy-series")
          ?.get(0, String::class.java),
      )
      assertEquals(
        "legacy-series-path/book.cbz",
        database.dsl
          .fetchOne("SELECT source_item_id FROM book WHERE id = ?", "legacy-book")
          ?.get(0, String::class.java),
      )
      assertEquals(
        "COMIC",
        database.dsl
          .fetchOne("SELECT media_item_type FROM book WHERE id = ?", "legacy-book")
          ?.get(0, String::class.java),
      )
      assertEquals(
        "COMIC",
        database.dsl
          .fetchOne(
            "SELECT media_item_type FROM media_item WHERE id = ?",
            "legacy-book",
          )
          ?.get(0, String::class.java),
      )
      assertEquals(
        "Legacy synthetic series",
        database.dsl
          .fetchOne(
            "SELECT title FROM series_metadata WHERE series_id = ?",
            "legacy-series",
          )
          ?.get(0, String::class.java),
      )
      assertEquals(
        "Legacy synthetic book",
        database.dsl
          .fetchOne(
            "SELECT title FROM book_metadata WHERE book_id = ?",
            "legacy-book",
          )
          ?.get(0, String::class.java),
      )
    }
  }

  @Test
  fun `restates analyzed positions under the Readium total progression convention`() {
    // V30 rewrites values the analyzer had already written as `position / count`. Counting
    // migrations cannot show that: the count above would be satisfied by an empty file. This
    // seeds the old convention at V29 and reads the column back after V30 has run.
    val path = tempDirectory.resolve("total-progression.sqlite").toAbsolutePath()
    val legacyDataSource =
      SQLiteDataSource().apply {
        url = "jdbc:sqlite:$path"
      }
    Flyway.configure()
      .dataSource(legacyDataSource)
      .locations("classpath:db/migration")
      .target("29")
      .load()
      .migrate()
    legacyDataSource.connection.use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
        VALUES ('library-1', 'Synthetic library', 'file:///synthetic', 1, 1)
        """.trimIndent(),
      ).use { it.executeUpdate() }
      connection.prepareStatement(
        """
        INSERT INTO series
          (id, library_id, relative_uri, source_item_id, name, sort_title,
           created_at_ms, updated_at_ms)
        VALUES ('series-1', 'library-1', 'series', 'series', 'Synthetic series',
                'Synthetic series', 1, 1)
        """.trimIndent(),
      ).use { it.executeUpdate() }
      connection.prepareStatement(
        """
        INSERT INTO book (
          id, library_id, series_id, relative_uri, source_item_id, name, media_kind,
          file_size, file_modified_ms, created_at_ms, updated_at_ms
        ) VALUES (?, 'library-1', 'series-1', ?, ?, ?, 'EPUB', 1, 1, 1, 1)
        """.trimIndent(),
      ).use { statement ->
        listOf("book-two", "book-four").forEach { id ->
          statement.setString(1, id)
          statement.setString(2, "series/$id.epub")
          statement.setString(3, "series/$id.epub")
          statement.setString(4, id)
          statement.executeUpdate()
        }
      }
      connection.prepareStatement(
        """
        INSERT INTO media_position
          (book_id, position, href, media_type, progression, total_progression)
        VALUES (?, ?, ?, 'application/xhtml+xml', 0, ?)
        """.trimIndent(),
      ).use { statement ->
        // `position / count` for each book, which is what the analyzer wrote before ADR 0106.
        // Two books with different position counts, because a migration that divided by the
        // table's total row count instead of the per-book count would still produce plausible
        // numbers for a single book.
        fun seedPositions(
          bookId: String,
          count: Int,
        ) {
          (1..count).forEach { position ->
            statement.setString(1, bookId)
            statement.setInt(2, position)
            statement.setString(3, "chapter-$position.xhtml")
            statement.setDouble(4, position.toDouble() / count)
            statement.executeUpdate()
          }
        }
        seedPositions("book-two", 2)
        seedPositions("book-four", 4)
      }
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      assertEquals(2, database.migrationResult.migrationsExecuted)

      fun storedProgressions(bookId: String): List<Double> =
        database.dsl
          .fetch(
            "SELECT total_progression FROM media_position WHERE book_id = ? ORDER BY position",
            bookId,
          ).map { requireNotNull(it.get(0, Double::class.java)) }

      // `(position - 1) / count`, per book. The second book is what rules out a global
      // divisor: over all six rows it would have made `book-two` read 0 then 0.1666...
      assertEquals(listOf(0.0, 0.5), storedProgressions("book-two"))
      assertEquals(listOf(0.0, 0.25, 0.5, 0.75), storedProgressions("book-four"))
    }
  }

  @Test
  fun `rolls back the entire transaction when its block fails`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("rollback.sqlite"))).use { database ->
      assertFailsWith<IllegalStateException> {
        database.transaction { transaction ->
          transaction.execute(
            """
            INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            "library-rollback",
            "Rollback library",
            "file:///synthetic/rollback",
            1L,
            1L,
          )
          error("force rollback")
        }
      }

      assertEquals(
        0,
        database.dsl.fetchCount(DSL.table(DSL.name("library"))),
      )
    }
  }

  @Test
  fun `rejects series whose library does not exist`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("foreign-key.sqlite"))).use { database ->
      val failure =
        assertFailsWith<org.jooq.exception.DataAccessException> {
          database.dsl.execute(
            """
            INSERT INTO series
              (id, library_id, relative_uri, name, sort_title, created_at_ms, updated_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "series-1",
            "missing-library",
            "synthetic-series",
            "Synthetic series",
            "Synthetic series",
            1L,
            1L,
          )
        }

      assertTrue(failure.cause is SQLException)
    }
  }

  @Test
  fun `accepts every media file kind the domain declares and no other`() {
    // V29 rebuilt `media_file` to widen its `kind` CHECK for `EPUB_COVER`. Counting migrations, as
    // the test above does, says only that a file ran - it would pass just as well if the new
    // constraint listed the wrong values, or if the rebuild had quietly dropped the constraint
    // altogether and started accepting anything. So this asserts what the constraint does: every
    // name `MediaFileKind` can be persisted under is accepted, and a name outside it is refused.
    //
    // The kinds are read off the enum rather than repeated as a literal list, because a list here
    // and a list in the migration agreeing says nothing about a value both of them omit - the enum
    // is what `JooqBookMediaRepository` actually writes.
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("media-file-kind.sqlite"))).use { database ->
      insertLibrary(database, id = "library-1", name = "Synthetic library")
      database.dsl.execute(
        """
        INSERT INTO series
          (id, library_id, relative_uri, name, sort_title, created_at_ms, updated_at_ms)
        VALUES ('series-1', 'library-1', 'synthetic-series', 'Synthetic series', 'Synthetic series', 1, 1)
        """.trimIndent(),
      )
      database.dsl.execute(
        """
        INSERT INTO book (
          id, library_id, series_id, relative_uri, name, media_kind, file_size,
          file_modified_ms, created_at_ms, updated_at_ms
        ) VALUES ('book-1', 'library-1', 'series-1', 'synthetic-series/book.epub',
          'Synthetic book', 'EPUB', 1, 1, 1, 1)
        """.trimIndent(),
      )

      MediaFileKind.entries.forEachIndexed { index, kind ->
        database.dsl.execute(
          """
          INSERT INTO media_file (book_id, number, file_name, media_type, file_size, kind)
          VALUES ('book-1', ?, ?, 'image/png', 1, ?)
          """.trimIndent(),
          index + 1,
          "file-$index.png",
          kind.name,
        )
      }
      assertEquals(
        MediaFileKind.entries.size,
        database.dsl.fetchCount(DSL.table(DSL.name("media_file"))),
      )

      val failure =
        assertFailsWith<org.jooq.exception.DataAccessException> {
          database.dsl.execute(
            """
            INSERT INTO media_file (book_id, number, file_name, media_type, file_size, kind)
            VALUES ('book-1', 900, 'unknown.png', 'image/png', 1, 'EPUB_SOMETHING_ELSE')
            """.trimIndent(),
          )
        }
      assertTrue(failure.cause is SQLException)
    }
  }

  @Test
  fun `rejects duplicate library root locations`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("uniqueness.sqlite"))).use { database ->
      insertLibrary(database, id = "library-1", name = "First")

      assertFailsWith<org.jooq.exception.DataAccessException> {
        insertLibrary(database, id = "library-2", name = "Second")
      }
    }
  }

  private fun insertLibrary(
    database: XoboroDatabase,
    id: String,
    name: String,
  ) {
    database.dsl.execute(
      """
      INSERT INTO library (id, name, root_uri, created_at_ms, updated_at_ms)
      VALUES (?, ?, ?, ?, ?)
      """.trimIndent(),
      id,
      name,
      "file:///synthetic/library",
      1L,
      1L,
    )
  }
}
