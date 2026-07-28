package io.xoboro.compatibility.komga.differential

import kotlinx.serialization.Serializable

@Serializable
data class DifferentialSuite(
  val version: String,
  val cases: List<DifferentialCase>,
) {
  init {
    require(version.isNotBlank()) { "Differential suite version must not be blank" }
    require(cases.isNotEmpty()) { "Differential suite must contain at least one case" }
    require(cases.map(DifferentialCase::name).distinct().size == cases.size) {
      "Differential case names must be unique"
    }
  }
}

@Serializable
data class DifferentialCase(
  val name: String,
  val method: String = "GET",
  val path: String,
  val pathVariables: Set<String> = emptySet(),
  val headers: Map<String, String> = emptyMap(),
  val body: String? = null,
  val comparison: ComparisonPolicy = ComparisonPolicy(),
) {
  init {
    require(name.isNotBlank()) { "Differential case name must not be blank" }
    require(METHOD.matches(method)) { "Differential HTTP method is invalid" }
    require(path.startsWith("/") && !path.startsWith("//")) {
      "Differential request path must be an origin-relative path"
    }
    require(!path.contains('#')) { "Differential request path must not contain a fragment" }
    require(pathVariables.all(PATH_VARIABLE_NAME::matches)) {
      "Differential path variable name is invalid"
    }
    val declaredPlaceholders = pathVariables.mapTo(mutableSetOf()) { "{$it}" }
    val actualPlaceholders = PATH_PLACEHOLDER.findAll(path).mapTo(mutableSetOf()) { it.value }
    require(actualPlaceholders == declaredPlaceholders) {
      "Differential path placeholders must exactly match pathVariables"
    }
    declaredPlaceholders.forEach { placeholder ->
      require(Regex("/${Regex.escape(placeholder)}(?=/|\\?|$)").containsMatchIn(path)) {
        "Differential path variables must occupy a complete path segment"
      }
    }
    require(headers.keys.all(HEADER_NAME::matches)) { "Differential header name is invalid" }
    require(headers.values.none { '\r' in it || '\n' in it }) {
      "Differential header value must not contain line breaks"
    }
  }

  companion object {
    private val METHOD = Regex("^[A-Z]+$")
    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val PATH_VARIABLE_NAME = Regex("^[A-Z][A-Z0-9_]*$")
    private val PATH_PLACEHOLDER = Regex("\\{[^{}]+}")
  }
}

@Serializable
data class ComparisonPolicy(
  val bodyMode: BodyMode = BodyMode.AUTO,
  val compareContentType: Boolean = true,
  val headers: Set<String> = emptySet(),
  val ignoreJsonPaths: Set<String> = emptySet(),
  val unorderedJsonPaths: Set<String> = emptySet(),
  val ignoreXmlPaths: Set<String> = emptySet(),
  val unorderedXmlPaths: Set<String> = emptySet(),
) {
  init {
    (ignoreJsonPaths + unorderedJsonPaths).forEach(JsonPathPattern::validate)
    (ignoreXmlPaths + unorderedXmlPaths).forEach(XmlPathPattern::validate)
  }
}

@Serializable
enum class BodyMode {
  AUTO,
  NONE,
  JSON,
  XML,
  TEXT,
  BINARY,
}

data class HttpSnapshot(
  val status: Int,
  val headers: Map<String, List<String>>,
  val body: ByteArray,
) {
  init {
    require(status in 100..599) { "HTTP status must be valid" }
  }

  fun header(name: String): List<String> =
    headers.entries
      .firstOrNull { (key) -> key.equals(name, ignoreCase = true) }
      ?.value
      .orEmpty()
}

data class DifferentialFailure(
  val caseName: String,
  val aspect: String,
  val reference: String,
  val candidate: String,
)

data class DifferentialReport(
  val suiteVersion: String,
  val caseCount: Int,
  val failures: List<DifferentialFailure>,
) {
  val passed: Boolean = failures.isEmpty()
}
