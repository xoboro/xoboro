package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceCopyMode
import io.xoboro.core.application.SourceImportRequest
import io.xoboro.core.application.SourceMutationAccess
import io.xoboro.core.application.SourceMutationResult
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LocalSourceMutationAccess : SourceMutationAccess {
  override val sourceId: String = LocalLibraryRootInspector.SOURCE_ID

  override fun delete(
    rootItemId: String,
    itemId: String,
  ): Boolean {
    val root = rootItemId.filePath("Local media root").toRealPath()
    require(Files.isDirectory(root)) { "Local media root must be a directory: $root" }
    val requested = itemId.filePath("Local media item")
    if (!Files.exists(requested)) return false
    val item = requested.toRealPath()
    require(item.startsWith(root)) { "Local media item must remain inside its library root" }
    require(Files.isRegularFile(item)) { "Local media item must be a regular file: $item" }
    return Files.deleteIfExists(item)
  }

  override fun import(
    rootItemId: String,
    destinationParentItemId: String,
    request: SourceImportRequest,
  ): String {
    val root = rootItemId.filePath("Local media root").toRealPath()
    require(Files.isDirectory(root)) { "Local media root must be a directory: $root" }
    val destinationParent =
      destinationParentItemId.filePath("Local destination directory").toRealPath()
    require(Files.isDirectory(destinationParent)) {
      "Local destination must be a directory: $destinationParent"
    }
    require(destinationParent.startsWith(root)) {
      "Local destination must remain inside its library root"
    }
    val source = Path.of(request.sourceFile).toAbsolutePath().normalize().toRealPath()
    require(Files.isRegularFile(source)) { "Import source must be a regular file: $source" }
    val destinationName = request.destinationName ?: source.fileName.toString()
    require(
      destinationName == Path.of(destinationName).fileName.toString() &&
        destinationName !in setOf(".", "..")
    ) {
      "Import destination name must be a file name"
    }
    val destination = destinationParent.resolve(destinationName).normalize()
    require(destination.parent == destinationParent) {
      "Import destination must remain inside its series directory"
    }
    require(request.replaceExisting || !Files.exists(destination)) {
      "Import destination already exists: $destination"
    }

    when (request.copyMode) {
      SourceCopyMode.COPY ->
        atomicCopy(source, destination, request.replaceExisting)
      SourceCopyMode.MOVE ->
        atomicMove(source, destination, request.replaceExisting)
      SourceCopyMode.HARDLINK ->
        atomicHardLink(source, destination, request.replaceExisting)
    }
    return destination.toUri().toString()
  }

  override fun removeArchiveEntries(
    rootItemId: String,
    itemId: String,
    entryNames: Set<String>,
  ): Int {
    require(entryNames.isNotEmpty()) { "Archive entry names must not be empty" }
    require(entryNames.none(String::isBlank)) { "Archive entry names must not be blank" }
    val root = rootItemId.filePath("Local media root").toRealPath()
    require(Files.isDirectory(root)) { "Local media root must be a directory: $root" }
    val archive = itemId.filePath("Local media item").toRealPath()
    require(archive.startsWith(root)) { "Local media item must remain inside its library root" }
    require(Files.isRegularFile(archive)) { "Local media item must be a regular file: $archive" }
    val temporary = Files.createTempFile(archive.parent, ".xoboro-rewrite-", ".tmp")
    var removed = 0
    try {
      ZipInputStream(Files.newInputStream(archive).buffered()).use { input ->
        ZipOutputStream(Files.newOutputStream(temporary).buffered()).use { output ->
          while (true) {
            val entry = input.nextEntry ?: break
            if (entry.name in entryNames) {
              removed += 1
            } else {
              val replacement =
                ZipEntry(entry.name).apply {
                  comment = entry.comment
                  time = entry.time
                  extra = entry.extra
                }
              output.putNextEntry(replacement)
              input.copyTo(output)
              output.closeEntry()
            }
            input.closeEntry()
          }
        }
      }
      if (removed > 0) moveIntoPlace(temporary, archive, replaceExisting = true)
      return removed
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  override fun renameExtension(
    rootItemId: String,
    itemId: String,
    extension: String,
  ): SourceMutationResult {
    val validatedExtension = extension.validatedExtension()
    val root = validatedRoot(rootItemId)
    val source = validatedItem(root, itemId)
    val destination = source.withExtension(validatedExtension)
    if (destination != source) {
      require(!Files.exists(destination)) {
        "Extension repair destination already exists: $destination"
      }
      moveIntoPlace(source, destination, replaceExisting = false)
    }
    return destination.resultRelativeTo(root)
  }

  override fun replaceWithFile(
    rootItemId: String,
    itemId: String,
    replacementFile: String,
    extension: String,
  ): SourceMutationResult {
    val validatedExtension = extension.validatedExtension()
    val root = validatedRoot(rootItemId)
    val source = validatedItem(root, itemId)
    val replacement = Path.of(replacementFile).toAbsolutePath().normalize().toRealPath()
    require(Files.isRegularFile(replacement)) {
      "Replacement media must be a regular file: $replacement"
    }
    val destination = source.withExtension(validatedExtension)
    require(destination == source || !Files.exists(destination)) {
      "Media replacement destination already exists: $destination"
    }
    val temporary = Files.createTempFile(source.parent, ".xoboro-replacement-", ".tmp")
    try {
      Files.copy(replacement, temporary, StandardCopyOption.REPLACE_EXISTING)
      moveIntoPlace(temporary, destination, replaceExisting = destination == source)
      if (destination != source) Files.delete(source)
      return destination.resultRelativeTo(root)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun atomicCopy(
    source: Path,
    destination: Path,
    replaceExisting: Boolean,
  ) {
    val temporary = Files.createTempFile(destination.parent, ".xoboro-import-", ".tmp")
    try {
      Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
      moveIntoPlace(temporary, destination, replaceExisting)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun atomicHardLink(
    source: Path,
    destination: Path,
    replaceExisting: Boolean,
  ) {
    val temporary = Files.createTempFile(destination.parent, ".xoboro-import-", ".tmp")
    Files.delete(temporary)
    try {
      Files.createLink(temporary, source)
      moveIntoPlace(temporary, destination, replaceExisting)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun atomicMove(
    source: Path,
    destination: Path,
    replaceExisting: Boolean,
  ) {
    val options =
      if (replaceExisting) {
        arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } else {
        arrayOf(StandardCopyOption.ATOMIC_MOVE)
      }
    try {
      Files.move(source, destination, *options)
    } catch (_: AtomicMoveNotSupportedException) {
      val fallback =
        if (replaceExisting) arrayOf(StandardCopyOption.REPLACE_EXISTING)
        else emptyArray()
      Files.move(source, destination, *fallback)
    }
  }

  private fun moveIntoPlace(
    temporary: Path,
    destination: Path,
    replaceExisting: Boolean,
  ) {
    val options =
      if (replaceExisting) {
        arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } else {
        arrayOf(StandardCopyOption.ATOMIC_MOVE)
      }
    try {
      Files.move(temporary, destination, *options)
    } catch (_: AtomicMoveNotSupportedException) {
      val fallback =
        if (replaceExisting) arrayOf(StandardCopyOption.REPLACE_EXISTING)
        else emptyArray()
      Files.move(temporary, destination, *fallback)
    }
  }

  private fun String.filePath(label: String): Path {
    val uri = URI(this)
    require(uri.scheme.equals("file", ignoreCase = true)) { "$label must use a file URI" }
    return Path.of(uri).toAbsolutePath().normalize()
  }

  private fun validatedRoot(rootItemId: String): Path =
    rootItemId.filePath("Local media root").toRealPath().also { root ->
      require(Files.isDirectory(root)) { "Local media root must be a directory: $root" }
    }

  private fun validatedItem(
    root: Path,
    itemId: String,
  ): Path =
    itemId.filePath("Local media item").toRealPath().also { item ->
      require(item.startsWith(root)) { "Local media item must remain inside its library root" }
      require(Files.isRegularFile(item)) { "Local media item must be a regular file: $item" }
    }

  private fun String.validatedExtension(): String {
    val normalized = trim().lowercase()
    require(normalized.isNotEmpty() && normalized.none { it == '/' || it == '\\' || it == '.' }) {
      "Media extension must be a plain non-empty suffix"
    }
    return normalized
  }

  private fun Path.withExtension(extension: String): Path {
    val leaf = fileName.toString()
    val stem = leaf.substringBeforeLast('.', missingDelimiterValue = leaf)
    return parent.resolve("$stem.$extension").normalize()
  }

  private fun Path.resultRelativeTo(root: Path): SourceMutationResult {
    val attributes = Files.readAttributes(this, BasicFileAttributes::class.java)
    return SourceMutationResult(
      itemId = toUri().toString(),
      relativePath =
        root.relativize(this).iterator().asSequence().joinToString("/") { it.toString() },
      name = fileName.toString(),
      identity = attributes.fileKey()?.toString(),
      size = attributes.size(),
      modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
    )
  }
}
