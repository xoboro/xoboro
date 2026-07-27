package io.xoboro.server.persistence

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.util.UUID
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

class DatabaseAlreadyOpenException(
  path: Path,
) : IllegalStateException("Database is already open: $path")

class InvalidDatabaseBackupException(
  path: Path,
  reason: String,
) : IllegalArgumentException("Invalid SQLite backup at $path: $reason")

internal class DatabaseFileLock private constructor(
  private val channel: FileChannel,
  private val lock: FileLock,
) : AutoCloseable {
  override fun close() {
    try {
      lock.release()
    } finally {
      channel.close()
    }
  }

  companion object {
    fun acquire(databasePath: Path): DatabaseFileLock {
      val lockPath = databasePath.lockPath()
      lockPath.parent?.let(Files::createDirectories)
      val channel =
        FileChannel.open(
          lockPath,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
        )
      return try {
        val lock =
          try {
            channel.tryLock()
          } catch (_: OverlappingFileLockException) {
            null
          } ?: throw DatabaseAlreadyOpenException(databasePath)
        DatabaseFileLock(channel, lock)
      } catch (failure: Throwable) {
        channel.close()
        throw failure
      }
    }
  }
}

class DatabaseBackupManager internal constructor(
  private val database: XoboroDatabase,
  private val databasePath: Path,
) {
  fun create(
    destination: Path,
    replaceExisting: Boolean = false,
  ): Path {
    val target = destination.toAbsolutePath().normalize()
    require(target != databasePath) { "Backup destination must differ from the live database" }
    require(!Files.exists(target) || !Files.isSameFile(target, databasePath)) {
      "Backup destination must not resolve to the live database"
    }
    target.parent?.let(Files::createDirectories)
    if (!replaceExisting && Files.exists(target)) {
      throw IllegalArgumentException("Backup destination already exists: $target")
    }
    val temporary = target.temporarySibling()
    Files.deleteIfExists(temporary)
    try {
      database.dsl.execute("VACUUM INTO ?", temporary.toString())
      verify(temporary)
      forceFile(temporary)
      moveAtomically(temporary, target, replaceExisting)
      return target
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  companion object {
    fun restore(
      source: Path,
      destination: Path,
      replaceExisting: Boolean = false,
    ): Path {
      val backup = source.toAbsolutePath().normalize()
      val target = destination.toAbsolutePath().normalize()
      require(backup != target) { "Backup source must differ from the database destination" }
      require(!Files.exists(target) || !Files.isSameFile(backup, target)) {
        "Backup source must not resolve to the database destination"
      }
      verify(backup)
      target.parent?.let(Files::createDirectories)
      if (!replaceExisting && Files.exists(target)) {
        throw IllegalArgumentException("Database destination already exists: $target")
      }

      DatabaseFileLock.acquire(target).use {
        val temporary = target.temporarySibling()
        Files.deleteIfExists(temporary)
        try {
          Files.copy(backup, temporary)
          verify(temporary)
          forceFile(temporary)
          Files.deleteIfExists(target.walPath())
          Files.deleteIfExists(target.shmPath())
          moveAtomically(temporary, target, replaceExisting)
          Files.deleteIfExists(target.walPath())
          Files.deleteIfExists(target.shmPath())
          return target
        } finally {
          Files.deleteIfExists(temporary)
        }
      }
    }

    fun verify(path: Path) {
      val candidate = path.toAbsolutePath().normalize()
      if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
        throw InvalidDatabaseBackupException(candidate, "file is missing or unreadable")
      }
      val config =
        SQLiteConfig().apply {
          setReadOnly(true)
          enforceForeignKeys(true)
        }
      val source =
        SQLiteDataSource(config).apply {
          url = "jdbc:sqlite:$candidate"
        }
      val result =
        try {
          source.connection.use { connection ->
            connection.validateBackup()
          }
        } catch (failure: Throwable) {
          throw InvalidDatabaseBackupException(
            candidate,
            failure.message ?: failure::class.simpleName.orEmpty().ifBlank { "open failed" },
          )
        }
      if (result != "ok") {
        throw InvalidDatabaseBackupException(candidate, result)
      }
    }

    private fun Connection.validateBackup(): String {
      val hasSchema =
        prepareStatement(
          """
          SELECT EXISTS(
            SELECT 1 FROM sqlite_master
            WHERE type = 'table' AND name = 'flyway_schema_history'
          )
          """.trimIndent(),
        ).use { statement ->
          statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
      if (!hasSchema) return "Xoboro schema marker is missing"
      val integrity =
        readOnlyIntegrityCheck()
      if (integrity != "ok") return integrity
      val foreignKeyFailure =
        createStatement().use { statement ->
          statement.executeQuery("PRAGMA foreign_key_check").use { rows ->
            rows.next()
          }
        }
      return if (foreignKeyFailure) "foreign key check failed" else "ok"
    }

    private fun Connection.readOnlyIntegrityCheck(): String =
      createStatement().use { statement ->
        statement.executeQuery("PRAGMA integrity_check").use { rows ->
          if (rows.next()) rows.getString(1) else "integrity check returned no result"
        }
      }

    private fun forceFile(path: Path) {
      FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun moveAtomically(
      source: Path,
      destination: Path,
      replaceExisting: Boolean,
    ) {
      val options: Array<CopyOption> =
        if (replaceExisting) {
          arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
          arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
      try {
        Files.move(source, destination, *options)
      } catch (_: AtomicMoveNotSupportedException) {
        val fallback: Array<CopyOption> =
          if (replaceExisting) {
            arrayOf(StandardCopyOption.REPLACE_EXISTING)
          } else {
            emptyArray()
          }
        Files.move(source, destination, *fallback)
      }
      destination.parent?.let { directory ->
        runCatching {
          FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
      }
    }
  }
}

private fun Path.temporarySibling(): Path =
  resolveSibling(".${fileName}.${UUID.randomUUID()}.tmp")

private fun Path.lockPath(): Path = resolveSibling("$fileName.lock")

private fun Path.walPath(): Path = resolveSibling("$fileName-wal")

private fun Path.shmPath(): Path = resolveSibling("$fileName-shm")
