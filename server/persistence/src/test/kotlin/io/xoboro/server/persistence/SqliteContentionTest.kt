package io.xoboro.server.persistence

import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jooq.exception.DataAccessException
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

class SqliteContentionTest {
  @Test
  fun `recognises a busy lock`() {
    assertTrue(
      isStoreContention(
        DataAccessException("wrapped", SQLiteException("busy", SQLiteErrorCode.SQLITE_BUSY)),
      ),
    )
  }

  @Test
  fun `recognises an exhausted connection pool`() {
    // Hikari's answer when every connection is held. A worker keeps its connection for the length of
    // its task, so a scan is enough to empty a pool sized near the worker count — and a task told the
    // pool was empty had nothing wrong with it. Unrecognised, this dead-lettered a metadata refresh
    // at attempt 11.
    assertTrue(
      isStoreContention(
        DataAccessException(
          "Error getting connection from data source",
          SQLTransientConnectionException("Connection is not available, request timed out"),
        ),
      ),
    )
  }

  @Test
  fun `does not mistake a real fault for contention`() {
    assertFalse(
      isStoreContention(
        DataAccessException(
          "wrapped",
          SQLiteException("constraint failed", SQLiteErrorCode.SQLITE_CONSTRAINT),
        ),
      ),
    )
    assertFalse(isStoreContention(DataAccessException("wrapped", SQLException("syntax error"))))
    assertFalse(isStoreContention(IllegalStateException("not a database failure")))
  }

  @Test
  fun `leaves a request a connection while every worker is busy`() {
    // A worker holds its connection for the length of its task, so the pool has to exceed the worker
    // count or a read waits on work that may take minutes. It used to be workerCount + 2.
    (1..20).forEach { workers ->
      val pool = DatabaseConfig.poolSizeForWorkers(workers)
      assertTrue(
        pool > workers || pool == 16,
        "a pool of $pool for $workers workers leaves nothing for requests",
      )
    }
    assertTrue(DatabaseConfig.poolSizeForWorkers(4) >= 12)
    assertTrue(DatabaseConfig.poolSizeForWorkers(100) == 16, "the pool must stay bounded")
  }
}
