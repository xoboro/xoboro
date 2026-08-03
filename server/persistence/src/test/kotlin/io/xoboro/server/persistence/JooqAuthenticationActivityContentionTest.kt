package io.xoboro.server.persistence

import io.xoboro.core.domain.AuthenticationActivity
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * Recording that a login happened must never prevent the login.
 *
 * Observed against a real library: while a WebDAV scan held the SQLite write lock, the
 * `authentication_activity` INSERT exceeded its busy timeout and took the whole `POST /session`
 * down with a `500`. The request's actual work had succeeded; only the bookkeeping had not.
 */
class JooqAuthenticationActivityContentionTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `does not fail the caller when the database is locked`() {
    val path = tempDirectory.resolve("activity.sqlite")
    XoboroDatabase.open(DatabaseConfig(path, busyTimeoutMillis = 50)).use { database ->
      val activities = JooqAuthenticationActivityRepository(database)

      DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { holder ->
        holder.autoCommit = false
        holder.createStatement().use {
          it.executeUpdate("UPDATE authentication_activity SET source = source")
        }
        try {
          // Must return rather than throw. The record is lost, which is the lesser harm and is
          // logged; failing every login during a large scan is not.
          activities.insert(activity())
        } finally {
          holder.rollback()
        }
      }

      // And once the lock is gone the next record lands, so nothing is permanently broken.
      activities.insert(activity())
      assertEquals(
        1,
        database.dsl.fetchValue("SELECT count(*) FROM authentication_activity", Int::class.java),
      )
    }
  }

  private fun activity(): AuthenticationActivity =
    AuthenticationActivity(
      email = "reader@example.invalid",
      ip = "127.0.0.1",
      userAgent = "synthetic-agent",
      success = true,
      dateTimeMillis = 1_000,
      source = "synthetic",
    )
}
