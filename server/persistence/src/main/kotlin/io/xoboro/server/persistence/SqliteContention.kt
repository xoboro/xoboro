package io.xoboro.server.persistence

import java.sql.SQLTransientException
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
  generateSequence(this) { it.cause }.any(::isTransientStoreFailure)

private fun isTransientStoreFailure(failure: Throwable): Boolean =
  when (failure) {
    is SQLiteException ->
      failure.resultCode == SQLiteErrorCode.SQLITE_BUSY ||
        failure.resultCode == SQLiteErrorCode.SQLITE_LOCKED
    // The connection pool running dry is the same condition one layer up, and it is reached the same
    // way: a scan holding the write lock keeps its worker's connection, and the pool is sized close
    // to the worker count. JDBC defines this type as an operation that may succeed when retried,
    // which is exactly the judgement callers here want. Matched on the type rather than on the
    // message, which is neither localised nor stable. Left unrecognised, this dead-lettered a
    // REFRESH_LIBRARY_METADATA task that had nothing wrong with it.
    is SQLTransientException -> true
    else -> false
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
