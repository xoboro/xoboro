package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceFile
import io.xoboro.core.application.SourceInventory
import io.xoboro.core.application.SourceInventoryFailure
import io.xoboro.core.application.SourceInventorySummary
import io.xoboro.core.application.SourceInventoryUnavailableException
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class LocalInventoryUnavailableException(
  rootItemId: String,
) : SourceInventoryUnavailableException(
    "Local inventory root is not a readable directory: $rootItemId",
  )

class LocalSourceInventory(
  override val sourceId: String = LocalLibraryRootInspector.SOURCE_ID,
) : SourceInventory {
  init {
    require(sourceId.isNotBlank()) { "Local source ID must not be blank" }
  }

  override fun inventory(
    rootItemId: String,
    directoryExclusions: Set<String>,
    onFile: (SourceFile) -> Unit,
    onFailure: (SourceInventoryFailure) -> Unit,
  ): SourceInventorySummary {
    require(directoryExclusions.none(String::isBlank)) {
      "Directory exclusions must not contain blank entries"
    }
    val root = rootItemId.toFilePath()
    if (!Files.isDirectory(root) || !Files.isReadable(root)) {
      throw LocalInventoryUnavailableException(rootItemId)
    }

    var visitedDirectories = 0L
    var emittedFiles = 0L
    var skippedDirectories = 0L
    var failedEntries = 0L
    Files.walkFileTree(
      root,
      setOf(FileVisitOption.FOLLOW_LINKS),
      Int.MAX_VALUE,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(
          dir: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          val relativePath = root.relativize(dir).portablePath()
          val shouldSkip =
            dir != root &&
              (
                dir.fileName.toString().startsWith(".") ||
                  directoryExclusions.any { exclusion ->
                    relativePath.contains(exclusion, ignoreCase = true)
                  }
              )
          if (shouldSkip) {
            skippedDirectories += 1
            return FileVisitResult.SKIP_SUBTREE
          }
          visitedDirectories += 1
          return FileVisitResult.CONTINUE
        }

        override fun visitFile(
          file: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          if (attrs.isRegularFile && !file.fileName.toString().startsWith(".")) {
            val relativePath = root.relativize(file).portablePath()
            val name = file.fileName.toString()
            onFile(
              SourceFile(
                itemId = file.toAbsolutePath().normalize().toUri().toString(),
                parentItemId = file.parent.toAbsolutePath().normalize().toUri().toString(),
                identity = attrs.durableIdentity(),
                relativePath = relativePath,
                name = name,
                extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(),
                size = attrs.size(),
                modifiedAtMillis = attrs.lastModifiedTime().toMillis(),
              ),
            )
            emittedFiles += 1
          }
          return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(
          file: Path,
          exception: IOException,
        ): FileVisitResult {
          failedEntries += 1
          onFailure(
            SourceInventoryFailure(
              itemId = file.toAbsolutePath().normalize().toUri().toString(),
              reason = exception.message ?: exception::class.simpleName.orEmpty().ifBlank { "I/O error" },
            ),
          )
          return FileVisitResult.CONTINUE
        }
      },
    )

    return SourceInventorySummary(
      visitedDirectories = visitedDirectories,
      emittedFiles = emittedFiles,
      skippedDirectories = skippedDirectories,
      failedEntries = failedEntries,
    )
  }

  private fun String.toFilePath(): Path =
    try {
      val uri = URI(this)
      if (uri.scheme != "file") throw InvalidLocalSourceItemException(this)
      Paths.get(uri).toAbsolutePath().normalize()
    } catch (failure: InvalidLocalSourceItemException) {
      throw failure
    } catch (failure: IllegalArgumentException) {
      throw InvalidLocalSourceItemException(this, failure)
    }

  private fun Path.portablePath(): String =
    iterator().asSequence().joinToString("/") { it.toString() }
}
