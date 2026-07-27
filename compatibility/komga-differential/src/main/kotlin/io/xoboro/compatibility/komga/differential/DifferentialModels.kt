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
    require(headers.keys.all(HEADER_NAME::matches)) { "Differential header name is invalid" }
    require(headers.values.none { '\r' in it || '\n' in it }) {
      "Differential header value must not contain line breaks"
    }
  }

  companion object {
    private val METHOD = Regex("^[A-Z]+$")
    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
  }
}

@Serializable
data class ComparisonPolicy(
  val bodyMode: BodyMode = BodyMode.AUTO,
  val compareContentType: Boolean = true,
  val headers: Set<String> = emptySet(),
  val ignoreJsonPaths: Set<String> = emptySet(),
  val unorderedJsonPaths: Set<String> = emptySet(),
) {
  init {
    (ignoreJsonPaths + unorderedJsonPaths).forEach(JsonPathPattern::validate)
  }
}

@Serializable
enum class BodyMode {
  AUTO,
  JSON,
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
