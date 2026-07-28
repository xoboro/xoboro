package io.xoboro.compatibility.komga.differential

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json

class DifferentialRunner(
  private val transport: DifferentialHttpTransport,
  private val json: Json = Json,
) {
  fun run(
    suite: DifferentialSuite,
    referenceUrl: String,
    candidateUrl: String,
    referenceAuthorization: String? = null,
    candidateAuthorization: String? = null,
    referencePathVariables: Map<String, String> = emptyMap(),
    candidatePathVariables: Map<String, String> = emptyMap(),
  ): DifferentialReport {
    val failures =
      suite.cases.flatMap { case ->
        val reference =
          runCatching {
            transport.execute(
              referenceUrl,
              case.resolvePath(referencePathVariables),
              referenceAuthorization.validAuthorization(),
            )
          }
        val candidate =
          runCatching {
            transport.execute(
              candidateUrl,
              case.resolvePath(candidatePathVariables),
              candidateAuthorization.validAuthorization(),
            )
          }
        buildList {
          reference.exceptionOrNull()?.let {
            add(case.failure("transport:reference", it.message ?: it::class.simpleName.orEmpty(), "request failed"))
          }
          candidate.exceptionOrNull()?.let {
            add(case.failure("transport:candidate", "request succeeded", it.message ?: it::class.simpleName.orEmpty()))
          }
          if (reference.isSuccess && candidate.isSuccess) {
            addAll(compare(case, reference.getOrThrow(), candidate.getOrThrow()))
          }
        }
      }
    return DifferentialReport(
      suiteVersion = suite.version,
      caseCount = suite.cases.size,
      failures = failures,
    )
  }

  private fun compare(
    case: DifferentialCase,
    reference: HttpSnapshot,
    candidate: HttpSnapshot,
  ): List<DifferentialFailure> =
    buildList {
      if (reference.status != candidate.status) {
        add(case.failure("status", reference.status, candidate.status))
      }
      if (case.comparison.compareContentType) {
        compareHeader(case, "Content-Type", reference, candidate)?.let(::add)
      }
      case.comparison.headers
        .filterNot { it.equals("Content-Type", ignoreCase = true) }
        .forEach { header ->
        compareHeader(case, header, reference, candidate)?.let(::add)
      }
      compareBody(case, reference, candidate)?.let(::add)
    }

  private fun compareHeader(
    case: DifferentialCase,
    name: String,
    reference: HttpSnapshot,
    candidate: HttpSnapshot,
  ): DifferentialFailure? {
    val expected = reference.header(name).normalizedHeader(name)
    val actual = candidate.header(name).normalizedHeader(name)
    return if (expected == actual) null else case.failure("header:$name", expected, actual)
  }

  private fun List<String>.normalizedHeader(name: String): List<String> =
    if (name.equals("Content-Type", ignoreCase = true)) {
      map { it.substringBefore(';').trim().lowercase() }
    } else {
      map(String::trim)
    }

  private fun compareBody(
    case: DifferentialCase,
    reference: HttpSnapshot,
    candidate: HttpSnapshot,
  ): DifferentialFailure? {
    val mode =
      when (case.comparison.bodyMode) {
        BodyMode.AUTO ->
          when {
            reference.header("Content-Type").any { "json" in it.lowercase() } -> BodyMode.JSON
            reference.header("Content-Type").any { "xml" in it.lowercase() } -> BodyMode.XML
            else -> BodyMode.BINARY
          }
        else -> case.comparison.bodyMode
      }
    if (mode == BodyMode.NONE) return null
    val normalized =
      runCatching {
        when (mode) {
          BodyMode.NONE -> error("NONE body mode must return before normalization")
          BodyMode.JSON -> normalizeJson(case, reference.body) to normalizeJson(case, candidate.body)
          BodyMode.XML -> normalizeXml(case, reference.body) to normalizeXml(case, candidate.body)
          BodyMode.TEXT ->
            reference.body.toText().normalizeLineEndings() to
              candidate.body.toText().normalizeLineEndings()
          BodyMode.BINARY -> reference.body.sha256() to candidate.body.sha256()
          BodyMode.AUTO -> error("AUTO body mode must be resolved")
        }
      }
    if (normalized.isFailure) {
      return case.failure(
        "body:${mode.name.lowercase()}",
        "valid ${mode.name.lowercase()} body",
        normalized.exceptionOrNull()?.message ?: "body normalization failed",
      )
    }
    val (expected, actual) = normalized.getOrThrow()
    return if (expected == actual) null else case.failure("body:${mode.name.lowercase()}", expected, actual)
  }

  private fun normalizeJson(
    case: DifferentialCase,
    body: ByteArray,
  ): String {
    val element = json.parseToJsonElement(body.toText())
    val normalized =
      JsonNormalizer(
        ignorePaths = case.comparison.ignoreJsonPaths,
        unorderedPaths = case.comparison.unorderedJsonPaths,
      ).normalize(element)
    return json.encodeToString(normalized)
  }

  private fun normalizeXml(
    case: DifferentialCase,
    body: ByteArray,
  ): String = XmlNormalizer(case.comparison.ignoreXmlPaths).normalize(body)

  private fun ByteArray.toText(): String = toString(StandardCharsets.UTF_8)

  private fun String.normalizeLineEndings(): String = replace("\r\n", "\n")

  private fun ByteArray.sha256(): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(this)
      .joinToString("") { byte -> "%02x".format(byte) }

  private fun DifferentialCase.failure(
    aspect: String,
    reference: Any,
    candidate: Any,
  ): DifferentialFailure =
    DifferentialFailure(
      caseName = name,
      aspect = aspect,
      reference = reference.toString().bounded(),
      candidate = candidate.toString().bounded(),
    )

  private fun String?.validAuthorization(): String? =
    this?.also {
      require(it.isNotBlank()) { "Differential authorization must not be blank" }
      require('\r' !in it && '\n' !in it) {
        "Differential authorization must not contain line breaks"
      }
    }

  private fun DifferentialCase.resolvePath(variables: Map<String, String>): DifferentialCase {
    val resolved =
      pathVariables.fold(path) { current, name ->
        val value =
          requireNotNull(variables[name]) {
            "Differential path variable '$name' is not configured"
          }
        require(PATH_SEGMENT.matches(value)) {
          "Differential path variable '$name' must be a safe path segment"
        }
        current.replace("{$name}", value)
      }
    return copy(path = resolved, pathVariables = emptySet())
  }

  private fun String.bounded(): String =
    if (length <= MAX_FAILURE_VALUE_LENGTH) {
      this
    } else {
      take(MAX_FAILURE_VALUE_LENGTH) + "… [sha256=${encodeToByteArray().sha256()}]"
    }

  companion object {
    private const val MAX_FAILURE_VALUE_LENGTH = 2_000
    private val PATH_SEGMENT = Regex("^[A-Za-z0-9._~-]+$")
  }
}
