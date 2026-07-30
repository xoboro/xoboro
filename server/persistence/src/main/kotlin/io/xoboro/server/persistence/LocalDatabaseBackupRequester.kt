package io.xoboro.server.persistence

import io.xoboro.core.application.DatabaseBackupDescriptor
import io.xoboro.core.application.DatabaseBackupRequester
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Stores backups as opaque-ID files inside a dedicated directory so the native API never has to
 * return an absolute filesystem path to a client. The directory must not be shared with anything
 * else; every `*.sqlite` file directly inside it is treated as a backup owned by this component.
 */
class LocalDatabaseBackupRequester(
  private val backups: DatabaseBackupManager,
  directory: Path,
  private val idFactory: () -> String,
) : DatabaseBackupRequester {
  private val directory: Path = directory.toAbsolutePath().normalize()

  override fun list(): List<DatabaseBackupDescriptor> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files
      .list(directory)
      .use { entries ->
        entries
          .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(FILE_SUFFIX) }
          .map { it.toDescriptor() }
          .toList()
      }.sortedByDescending(DatabaseBackupDescriptor::createdAtMillis)
  }

  override fun create(): DatabaseBackupDescriptor {
    Files.createDirectories(directory)
    val id = idFactory().also(::requireValidId)
    val destination = directory.resolve(id.fileName())
    backups.create(destination)
    return destination.toDescriptor(id)
  }

  override fun delete(id: String): Boolean {
    if (!VALID_ID_PATTERN.matches(id)) return false
    val target = directory.resolve(id.fileName())
    if (target.parent != directory || !Files.isRegularFile(target)) {
      return false
    }
    return Files.deleteIfExists(target)
  }

  private fun Path.toDescriptor(id: String = fileName.toString().removeSuffix(FILE_SUFFIX)): DatabaseBackupDescriptor {
    val attributes = Files.readAttributes(this, BasicFileAttributes::class.java)
    return DatabaseBackupDescriptor(
      id = id,
      sizeBytes = attributes.size(),
      createdAtMillis = attributes.lastModifiedTime().toMillis(),
    )
  }

  private fun String.fileName(): String = "$this$FILE_SUFFIX"

  private fun requireValidId(id: String) {
    require(VALID_ID_PATTERN.matches(id)) { "Backup ID factory produced an unsafe ID: $id" }
  }

  private companion object {
    const val FILE_SUFFIX = ".sqlite"
    val VALID_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
  }
}
