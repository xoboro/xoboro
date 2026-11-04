package io.xoboro.server

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.name

/** Retains immutable bundles for documents that stay open across a server upgrade. */
internal class WebAssetArchive(directory: Path) {
  private val root = directory.toAbsolutePath().normalize()

  fun capture(webDirectory: Path) {
    val source = webDirectory.toAbsolutePath().normalize().resolve("assets")
    require(!root.startsWith(source)) { "Web asset archive must be outside the active assets" }
    if (!Files.isDirectory(source)) return
    Files.createDirectories(root)
    val realRoot = root.toRealPath()
    Files.walk(source).use { files ->
      files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && ASSET_NAME.matches(it.name) }
        .forEach { file ->
          val target = root.resolve(source.relativize(file)).normalize()
          Files.createDirectories(target.parent)
          require(target.parent.toRealPath().startsWith(realRoot)) { "Asset archive path escapes its root" }
          if (!Files.exists(target, NOFOLLOW_LINKS)) Files.copy(file, target)
        }
    }
  }

  fun find(segments: List<String>): Path? {
    if (segments.isEmpty() || !ASSET_NAME.matches(segments.last()) || !Files.isDirectory(root)) return null
    val candidate = root.resolve(segments.joinToString("/")).normalize()
    if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, NOFOLLOW_LINKS)) return null
    return candidate.takeIf { it.toRealPath().startsWith(root.toRealPath()) }
  }

  private companion object {
    val ASSET_NAME = Regex("[A-Za-z0-9._-]+-[A-Za-z0-9_-]{6,}\\.(?:js|css|svg|png|jpe?g|webp|avif|woff2?|ttf|otf|wasm)")
  }
}
