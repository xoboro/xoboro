package io.xoboro.core.application

data class DatabaseBackupDescriptor(
  val id: String,
  val sizeBytes: Long,
  val createdAtMillis: Long,
) {
  init {
    require(id.isNotBlank()) { "Backup ID must not be blank" }
    require(sizeBytes >= 0) { "Backup size must not be negative" }
    require(createdAtMillis >= 0) { "Backup creation timestamp must not be negative" }
  }
}

/**
 * Requests server-managed database backups. Creation and listing are kept synchronous because a
 * `VACUUM INTO` snapshot of the catalog database is a single bounded SQL statement, not an
 * unbounded filesystem walk — unlike library scans, it does not need the durable task queue.
 *
 * Restoring a backup is intentionally not exposed here: it requires taking the live database
 * offline and remains an operator-driven CLI/offline action (see `runDatabaseCommand`).
 */
interface DatabaseBackupRequester {
  fun list(): List<DatabaseBackupDescriptor>

  fun create(): DatabaseBackupDescriptor

  fun delete(id: String): Boolean
}
