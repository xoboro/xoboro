package io.xoboro.server.persistence

import org.jooq.exception.DataAccessException
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * Whether a failure is SQLite's transient lock contention rather than a real fault.
 *
 * Matched on the driver's own result code through the cause chain instead of on message text,
 * which is localised and version-dependent. `busy_timeout` covers waiting for a fresh lock but
 * not a mid-transaction upgrade, and a large scan can hold the write lock past any timeout worth
 * configuring, so callers on a request path need to be able to recognise this and decide rather
 * than let it become a `500`.
 */
internal fun Throwable.isSqliteContention(): Boolean =
  generateSequence(this) { it.cause }
    .filterIsInstance<SQLiteException>()
    .any {
      it.resultCode == SQLiteErrorCode.SQLITE_BUSY || it.resultCode == SQLiteErrorCode.SQLITE_LOCKED
    }

internal fun DataAccessException.isDatabaseLocked(): Boolean = isSqliteContention()
