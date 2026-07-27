package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceArtwork
import io.xoboro.core.application.SourceArtworkAccess
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class LocalSourceArtworkAccess : SourceArtworkAccess {
  override val sourceId: String = LocalLibraryRootInspector.SOURCE_ID

  override fun findBookArtwork(
    rootItemId: String,
    bookItemId: String,
    maximumBytes: Int,
  ): List<SourceArtwork> {
    val root = rootItemId.validatedRoot()
    val book = bookItemId.toFilePath().toRealPath()
    require(book.startsWith(root) && Files.isRegularFile(book)) {
      "Book artwork target must be a regular file inside its library root"
    }
    val leaf = book.fileName.toString()
    val baseName = leaf.substringBeforeLast('.', missingDelimiterValue = leaf)
    val pattern = Regex("""^${Regex.escape(baseName)}(?:-\d+)?\.(?:${EXTENSIONS.joinToString("|")})$""", RegexOption.IGNORE_CASE)
    return findArtwork(book.parent, maximumBytes) { path ->
      pattern.matches(path.fileName.toString())
    }
  }

  override fun findSeriesArtwork(
    rootItemId: String,
    seriesItemId: String,
    maximumBytes: Int,
  ): List<SourceArtwork> {
    val root = rootItemId.validatedRoot()
    val series = seriesItemId.toFilePath().toRealPath()
    require(series.startsWith(root) && Files.isDirectory(series)) {
      "Series artwork target must be a directory inside its library root"
    }
    return findArtwork(series, maximumBytes) { path ->
      val leaf = path.fileName.toString()
      val stem = leaf.substringBeforeLast('.', missingDelimiterValue = leaf).lowercase()
      val extension = leaf.substringAfterLast('.', missingDelimiterValue = "").lowercase()
      stem in SERIES_STEMS && extension in EXTENSIONS
    }
  }

  private fun findArtwork(
    directory: Path,
    maximumBytes: Int,
    matches: (Path) -> Boolean,
  ): List<SourceArtwork> {
    require(maximumBytes > 0) { "Artwork byte limit must be positive" }
    return Files.list(directory).use { paths ->
      paths
        .filter(Files::isRegularFile)
        .filter(matches)
        .sorted(
          compareBy<Path> { it.fileName.toString().lowercase() }
            .thenBy { it.fileName.toString() },
        ).map { path ->
          require(Files.size(path) <= maximumBytes) { "Local artwork exceeds the byte limit" }
          val bytes =
            Files.newInputStream(path).use { input ->
              input.readNBytes(maximumBytes + 1).also {
                require(it.size <= maximumBytes) { "Local artwork exceeds the byte limit" }
              }
            }
          SourceArtwork(path.fileName.toString(), bytes)
        }.toList()
    }
  }

  private fun String.validatedRoot(): Path =
    toFilePath().toRealPath().also { root ->
      require(Files.isDirectory(root)) { "Local artwork root must be a directory" }
    }

  private fun String.toFilePath(): Path {
    val uri = URI(this)
    require(uri.scheme.equals("file", ignoreCase = true)) {
      "Local artwork items must use file URIs"
    }
    return Path.of(uri).toAbsolutePath().normalize()
  }

  private companion object {
    val EXTENSIONS = setOf("png", "jpeg", "jpg", "tbn", "webp", "gif")
    val SERIES_STEMS = setOf("cover", "default", "folder", "poster", "series")
  }
}
