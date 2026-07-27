package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceCopyMode
import io.xoboro.core.application.SourceImportRequest
import io.xoboro.core.application.SourceMutationAccess
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
}
