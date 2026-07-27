package io.xoboro.server.sources.local

import io.xoboro.core.application.LibraryRootInspector
import io.xoboro.core.application.RootType
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

class InvalidLocalSourceItemException(
  itemId: String,
  cause: Throwable? = null,
) : IllegalArgumentException("Invalid local source item URI: $itemId", cause)

class LocalLibraryRootInspector(
  override val sourceId: String = SOURCE_ID,
) : LibraryRootInspector {
  init {
    require(sourceId.isNotBlank()) { "Local source ID must not be blank" }
  }

  override fun typeOf(itemId: String): RootType {
    val path = itemId.toFilePath()
    return when {
      !Files.exists(path) -> RootType.MISSING
      Files.isDirectory(path) -> RootType.DIRECTORY
      else -> RootType.FILE
    }
  }

  override fun isSameOrAncestor(
    possibleAncestorItemId: String,
    possibleDescendantItemId: String,
  ): Boolean {
    val ancestor = possibleAncestorItemId.toRealFilePath()
    val descendant = possibleDescendantItemId.toRealFilePath()
    return descendant.startsWith(ancestor)
  }

  private fun String.toFilePath(): Path =
    try {
      val uri = URI(this)
      if (uri.scheme != "file") throw InvalidLocalSourceItemException(this)
      Paths.get(uri).toAbsolutePath().normalize()
    } catch (failure: InvalidLocalSourceItemException) {
      throw failure
    } catch (failure: InvalidPathException) {
      throw InvalidLocalSourceItemException(this, failure)
    } catch (failure: IllegalArgumentException) {
      throw InvalidLocalSourceItemException(this, failure)
    }

  private fun String.toRealFilePath(): Path =
    try {
      toFilePath().toRealPath()
    } catch (failure: java.io.IOException) {
      throw InvalidLocalSourceItemException(this, failure)
    }

  companion object {
    const val SOURCE_ID: String = "local"
  }
}
