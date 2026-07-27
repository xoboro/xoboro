package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceSidecarAccess
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class LocalSourceSidecarAccess : SourceSidecarAccess {
  override val sourceId: String = "local"

  override fun readSeriesSidecar(
    rootItemId: String,
    seriesItemId: String,
    fileName: String,
    maximumBytes: Int,
  ): ByteArray? {
    require(fileName.isNotBlank() && Path.of(fileName).fileName.toString() == fileName) {
      "Sidecar file name must be a simple file name"
    }
    require(maximumBytes > 0) { "Sidecar byte limit must be positive" }
    val root = rootItemId.toFilePath().toRealPath()
    val series = seriesItemId.toFilePath().toRealPath()
    require(series.startsWith(root)) { "Series item must remain inside its library root" }
    require(Files.isDirectory(series)) { "Series item must be a directory" }
    val sidecar = series.resolve(fileName)
    if (!Files.isRegularFile(sidecar)) return null
    require(Files.size(sidecar) <= maximumBytes) { "Sidecar exceeds the byte limit" }
    return Files.newInputStream(sidecar).use { input ->
      input.readNBytes(maximumBytes + 1).also {
        require(it.size <= maximumBytes) { "Sidecar exceeds the byte limit" }
      }
    }
  }

  private fun String.toFilePath(): Path {
    val uri = URI(this)
    require(uri.scheme.equals("file", ignoreCase = true)) { "Local items must use file URIs" }
    return Path.of(uri).toAbsolutePath().normalize()
  }
}
