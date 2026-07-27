package io.xoboro.compatibility.komga.differential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal class JsonNormalizer(
  ignorePaths: Set<String>,
  unorderedPaths: Set<String>,
) {
  private val ignored = ignorePaths.map(::JsonPathPattern)
  private val unordered = unorderedPaths.map(::JsonPathPattern)

  fun normalize(element: JsonElement): JsonElement = normalize(element, emptyList())

  private fun normalize(
    element: JsonElement,
    path: List<String>,
  ): JsonElement =
    when (element) {
      is JsonObject ->
        JsonObject(
          element
            .asSequence()
            .filterNot { (key) -> ignored.any { it.matches(path + key) } }
            .sortedBy { (key) -> key }
            .associate { (key, value) -> key to normalize(value, path + key) },
        )
      is JsonArray -> {
        val normalized = element.mapIndexed { index, value -> normalize(value, path + index.toString()) }
        JsonArray(
          if (unordered.any { it.matches(path) }) {
            normalized.sortedBy(CANONICAL_JSON::encodeToString)
          } else {
            normalized
          },
        )
      }
      else -> element
    }

  companion object {
    private val CANONICAL_JSON = Json
  }
}

internal class JsonPathPattern private constructor(
  private val segments: List<String>,
) {
  constructor(value: String) : this(parse(value))

  fun matches(path: List<String>): Boolean =
    segments.size == path.size &&
      segments.zip(path).all { (expected, actual) -> expected == "*" || expected == actual }

  companion object {
    fun validate(value: String) {
      parse(value)
    }

    private fun parse(value: String): List<String> {
      require(value.startsWith("/")) {
        "JSON path must use JSON Pointer syntax and start with '/'"
      }
      require(value.length > 1) { "Root JSON path is not supported" }
      return value
        .removePrefix("/")
        .split("/")
        .map { segment ->
          require(segment.isNotEmpty()) { "JSON path segments must not be empty" }
          decode(segment)
        }
    }

    private fun decode(value: String): String {
      var index = 0
      val decoded = StringBuilder()
      while (index < value.length) {
        if (value[index] != '~') {
          decoded.append(value[index++])
          continue
        }
        require(index + 1 < value.length) { "JSON path contains an invalid escape" }
        decoded.append(
          when (value[index + 1]) {
            '0' -> '~'
            '1' -> '/'
            else -> throw IllegalArgumentException("JSON path contains an invalid escape")
          },
        )
        index += 2
      }
      return decoded.toString()
    }
  }
}
