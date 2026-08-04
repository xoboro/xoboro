package io.xoboro.server.sources.local

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a local media item URI to a real path inside its library root.
 *
 * Shared by [LocalSourceMediaAccess] and [LocalSourceRandomAccess] rather than copied into each,
 * because the containment check is what keeps an item URI from addressing a file outside the
 * library, and a containment check that exists twice is one that eventually only holds in one place.
 */
internal object LocalMediaItemPath {
  fun resolve(
    rootItemId: String,
    itemId: String,
  ): Path {
    val root = filePath(rootItemId, "Local media root").toRealPath()
    require(Files.isDirectory(root)) { "Local media root must be a directory: $root" }
    val path = filePath(itemId, "Local media item").toRealPath()
    require(path.startsWith(root)) { "Local media item must remain inside its library root" }
    require(Files.isRegularFile(path)) { "Local media item must be a regular file: $path" }
    return path
  }

  private fun filePath(
    itemId: String,
    label: String,
  ): Path {
    val uri = URI(itemId)
    require(uri.scheme.equals("file", ignoreCase = true)) { "$label must use a file URI" }
    return Path.of(uri).toAbsolutePath().normalize()
  }
}
