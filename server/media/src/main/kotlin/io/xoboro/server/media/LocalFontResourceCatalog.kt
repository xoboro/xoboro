package io.xoboro.server.media

import io.xoboro.core.application.FontResource
import io.xoboro.core.application.FontResourceCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class LocalFontResourceCatalog(
  directory: Path,
) : FontResourceCatalog {
  private val fonts: Map<String, List<Path>> = discover(directory)

  override fun families(): Set<String> = fonts.keys

  override fun resource(
    family: String,
    fileName: String,
  ): FontResource? {
    val path = fonts[family]?.firstOrNull { it.name == fileName } ?: return null
    require(Files.size(path) <= MAXIMUM_FONT_BYTES) {
      "Font resource exceeds the size limit"
    }
    return FontResource(
      fileName = path.name,
      mediaType = "font/${path.extension.lowercase()}",
      bytes = Files.readAllBytes(path),
    )
  }

  override fun css(family: String): String? =
    fonts[family]
      ?.groupBy(::characteristics)
      ?.entries
      ?.sortedWith(compareBy({ it.key.style }, { it.key.weight }))
      ?.joinToString("\n") { (characteristics, paths) ->
        val sources =
          paths
            .sortedBy(Path::toString)
            .joinToString(",") { path ->
              val format =
                when (path.extension.lowercase()) {
                  "ttf" -> "truetype"
                  "otf" -> "opentype"
                  else -> path.extension.lowercase()
                }
              "url('${path.name.cssString()}') format('$format')"
            }
        """
        @font-face {
            font-family: '${family.cssString()}';
            src: $sources;
            font-weight: ${characteristics.weight};
            font-style: ${characteristics.style};
        }

        """.trimIndent()
      }

  private fun discover(directory: Path): Map<String, List<Path>> {
    if (!directory.isDirectory() || !Files.isReadable(directory)) return emptyMap()
    return Files.list(directory).use { families ->
      families
        .filter(Path::isDirectory)
        .sorted()
        .toList()
        .associate { family ->
          family.name to
            Files.list(family).use { files ->
              files
                .filter(Path::isRegularFile)
                .filter(Files::isReadable)
                .filter { it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                .sorted()
                .toList()
            }
        }
    }
  }

  private fun characteristics(path: Path): FontCharacteristics =
    FontCharacteristics(
      style = if (path.name.contains("italic", ignoreCase = true)) "italic" else "normal",
      weight = if (path.name.contains("bold", ignoreCase = true)) "bold" else "normal",
    )

  private fun String.cssString(): String =
    replace("\\", "\\\\").replace("'", "\\'")

  private data class FontCharacteristics(
    val style: String,
    val weight: String,
  )

  private companion object {
    val SUPPORTED_EXTENSIONS = setOf("woff", "woff2", "ttf", "otf")
    const val MAXIMUM_FONT_BYTES = 50L * 1_024 * 1_024
  }
}
