package io.xoboro.server.persistence

import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jooq.impl.DSL
import org.junit.jupiter.api.io.TempDir

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

      assertTrue(tables.containsAll(listOf("flyway_schema_history", "library", "series", "book")))
      assertEquals("wal", database.dsl.fetchValue("PRAGMA journal_mode", String::class.java))
      assertEquals(1, database.dsl.fetchValue("PRAGMA foreign_keys", Int::class.java))
      assertEquals(10_000, database.dsl.fetchValue("PRAGMA busy_timeout", Int::class.java))
      assertEquals(1, database.migrationResult.migrationsExecuted)
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
