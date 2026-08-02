package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceCopyMode
import io.xoboro.core.application.SourceImportRequest
import io.xoboro.core.application.SourceMutationAccess
import io.xoboro.core.application.SourceMutationResult
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * @param quarantine When set, the original file behind an archive rewrite is preserved by moving
 *   it here instead of letting the rewritten copy overwrite it in place. A single instance of
 *   this class serves every local library root — the root is only known per call, through
 *   `rootItemId` — so "the quarantine directory must never sit inside a library root" is
 *   enforced the first time a root becomes known, inside [removeArchiveEntries] before any file
 *   is touched, rather than at construction.
 */
class LocalSourceMutationAccess(
  private val quarantine: Path? = null,
) : SourceMutationAccess {
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
    val resolvedQuarantine = quarantine?.realPathOrNormalized()
    require(resolvedQuarantine == null || !resolvedQuarantine.startsWith(root)) {
      "Quarantine directory must not be inside the library root: $resolvedQuarantine"
    }
    val temporary = Files.createTempFile(archive.parent, ".xoboro-rewrite-", ".tmp")
    var removed = 0
    val removedNames = mutableSetOf<String>()
    try {
      ZipInputStream(Files.newInputStream(archive).buffered()).use { input ->
        ZipOutputStream(Files.newOutputStream(temporary).buffered()).use { output ->
          while (true) {
            val entry = input.nextEntry ?: break
            if (entry.name in entryNames) {
              removed += 1
              removedNames += entry.name
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
      if (removed > 0) {
        verifyRewrittenArchive(archive, temporary, removedNames)
        replaceArchive(archive, temporary, resolvedQuarantine)
      }
      return removed
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  /**
   * Confirms, via the central directory ([ZipFile], not the sequential [ZipInputStream] used to
   * write [temporary]), that [temporary] holds exactly the entries of [archive] minus
   * [removedNames] — nothing else missing, nothing else added — and that every retained entry
   * still carries its original uncompressed size and is actually readable. A truncated or
   * subtly malformed [archive] can make [ZipInputStream] stop enumerating early without ever
   * raising an exception, which would otherwise let a rewrite silently drop pages nobody asked
   * to remove.
   */
  private fun verifyRewrittenArchive(
    archive: Path,
    temporary: Path,
    removedNames: Set<String>,
  ) {
    ZipFile(archive.toFile()).use { originalZip ->
      ZipFile(temporary.toFile()).use { temporaryZip ->
        val originalEntries = originalZip.entriesByName()
        val temporaryEntries = temporaryZip.entriesByName()
        val expectedNames = originalEntries.keys - removedNames
        check(temporaryEntries.keys == expectedNames) {
          "Archive rewrite of $archive would drop or add entries: expected retained entries " +
            "$expectedNames, rewrite produced ${temporaryEntries.keys}"
        }
        for (name in expectedNames) {
          val originalSize = originalEntries.getValue(name).size
          val temporaryEntry = temporaryEntries.getValue(name)
          check(temporaryEntry.size == originalSize) {
            "Archive rewrite of $archive changed the size of entry '$name': expected " +
              "$originalSize bytes, rewrite produced ${temporaryEntry.size} bytes"
          }
          try {
            temporaryZip.getInputStream(temporaryEntry).use { it.readAllBytes() }
          } catch (failure: IOException) {
            throw IllegalStateException(
              "Archive rewrite of $archive produced an unreadable entry '$name'",
              failure,
            )
          }
        }
      }
    }
  }

  private fun ZipFile.entriesByName(): Map<String, ZipEntry> {
    val byName = mutableMapOf<String, ZipEntry>()
    val remaining = entries()
    while (remaining.hasMoreElements()) {
      val entry = remaining.nextElement()
      byName[entry.name] = entry
    }
    return byName
  }

  /**
   * Replaces [archive] with the verified [temporary] rewrite. With no [quarantineRoot] resolved
   * this is the original in-place overwrite. With one resolved, [archive] is moved aside first
   * so the operator's original survives; if moving [temporary] into place then fails, the
   * original is moved back so the path never ends up with neither file.
   */
  private fun replaceArchive(
    archive: Path,
    temporary: Path,
    quarantineRoot: Path?,
  ) {
    if (quarantineRoot == null) {
      moveIntoPlace(temporary, archive, replaceExisting = true)
      return
    }
    val quarantined = quarantineRoot.resolve(UUID.randomUUID().toString()).resolve(archive.fileName)
    Files.createDirectories(quarantined.parent)
    atomicMove(archive, quarantined, replaceExisting = false)
    try {
      moveIntoPlace(temporary, archive, replaceExisting = false)
    } catch (failure: Throwable) {
      // Caught broadly rather than as `IOException`, because the invariant is about the file
      // system rather than about a class of exception: between the two moves the media item's
      // path holds nothing at all, and anything that escapes here - `Files.move` can also raise
      // `SecurityException` and `UnsupportedOperationException`, neither an `IOException` -
      // would leave the library missing a file it still believes it has.
      try {
        atomicMove(quarantined, archive, replaceExisting = false)
      } catch (rollbackFailure: Throwable) {
        // Reported alongside rather than instead: if the restore fails too, the original is
        // still in quarantine and whoever reads this needs the path, not just the first error.
        failure.addSuppressed(rollbackFailure)
      }
      throw failure
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

  /**
   * Resolves symlinks like [Path.toRealPath] when the path already exists, so it compares
   * correctly against a library root that was itself resolved with [Path.toRealPath]. Falls back
   * to a plain normalized absolute path when the quarantine directory does not exist yet, since
   * it is created on demand.
   */
  private fun Path.realPathOrNormalized(): Path =
    try {
      toRealPath()
    } catch (_: NoSuchFileException) {
      toAbsolutePath().normalize()
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
