package io.xoboro.server.persistence

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
            "task",
            "catalog_scan_session",
            "catalog_scan_candidate",
            "media",
            "book_page",
            "media_file",
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
            "authentication_activity",
            "server_setting",
            "client_setting_global",
            "client_setting_user",
            "user_announcement_read",
          ),
        ),
      )
      assertEquals("wal", database.dsl.fetchValue("PRAGMA journal_mode", String::class.java))
      assertEquals(1, database.dsl.fetchValue("PRAGMA foreign_keys", Int::class.java))
      assertEquals(10_000, database.dsl.fetchValue("PRAGMA busy_timeout", Int::class.java))
      assertEquals(16, database.migrationResult.migrationsExecuted)
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
      assertEquals(15, database.migrationResult.migrationsExecuted)
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
