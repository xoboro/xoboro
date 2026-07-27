package io.xoboro.server.sources.local

import io.xoboro.server.media.MaterializedMedia
import io.xoboro.server.media.SourceMediaAccess
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class LocalSourceMediaAccess : SourceMediaAccess {
  override val sourceId: String = "local"

  override fun materialize(
    rootItemId: String,
    itemId: String,
  ): MaterializedMedia {
    val root = filePath(rootItemId, "Local media root").toRealPath()
    require(Files.isDirectory(root)) { "Local media root must be a directory: $root" }
    val path = filePath(itemId, "Local media item").toRealPath()
    require(path.startsWith(root)) { "Local media item must remain inside its library root" }
    require(Files.isRegularFile(path)) { "Local media item must be a regular file: $path" }
    return LocalMaterializedMedia(path)
  }

  private fun filePath(
    itemId: String,
    label: String,
  ): Path {
    val uri = URI(itemId)
    require(uri.scheme.equals("file", ignoreCase = true)) { "$label must use a file URI" }
    return Path.of(uri).toAbsolutePath().normalize()
  }

  private class LocalMaterializedMedia(
    override val path: Path,
  ) : MaterializedMedia {
    override fun close() = Unit
  }
}
