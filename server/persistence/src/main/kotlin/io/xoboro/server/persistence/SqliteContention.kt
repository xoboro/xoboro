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

/**
 * The same test, for callers outside this module.
 *
 * Modules above persistence cannot read a driver result code and must not try, but some of them do
 * need to tell "the store is busy" apart from a fault they are meant to absorb - see
 * [io.xoboro.server.tasks.BookCoverGenerationLifecycle], which swallows every cover failure except
 * this one. Exposed as a function to inject rather than as a type to import, so those modules keep
 * no dependency on jOOQ or the SQLite driver.
 */
fun isStoreContention(failure: Throwable): Boolean = failure.isSqliteContention()
