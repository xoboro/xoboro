package io.xoboro.compatibility.komga.differential

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
  val arguments = Arguments.parse(args)
  val json = Json { ignoreUnknownKeys = false }
  val suite =
    json.decodeFromString<DifferentialSuite>(
      Files.readString(arguments.suite),
    )
  val sharedAuthorization = System.getenv("KOMGA_DIFFERENTIAL_AUTHORIZATION")
  val pathVariableNames = suite.cases.flatMapTo(mutableSetOf(), DifferentialCase::pathVariables)
  val report =
    DifferentialRunner(JdkDifferentialHttpTransport(), json).run(
      suite = suite,
      referenceUrl = arguments.reference,
      candidateUrl = arguments.candidate,
      referenceAuthorization =
        System.getenv("KOMGA_REFERENCE_AUTHORIZATION") ?: sharedAuthorization,
      candidateAuthorization =
        System.getenv("XOBORO_CANDIDATE_AUTHORIZATION") ?: sharedAuthorization,
      referencePathVariables =
        pathVariableNames.associateWith { name ->
          requireNotNull(System.getenv("KOMGA_REFERENCE_$name")) {
            "KOMGA_REFERENCE_$name must be configured"
          }
        },
      candidatePathVariables =
        pathVariableNames.associateWith { name ->
          requireNotNull(System.getenv("XOBORO_CANDIDATE_$name")) {
            "XOBORO_CANDIDATE_$name must be configured"
          }
        },
    )
  report.failures.forEach { failure ->
    System.err.println(
      "FAIL ${failure.caseName} ${failure.aspect}\n" +
        "  Komga:  ${failure.reference}\n" +
        "  Xoboro: ${failure.candidate}",
    )
  }
  if (!report.passed) {
    System.err.println(
      "${report.failures.size} difference(s) across ${report.caseCount} case(s)",
    )
    exitProcess(1)
  }
  println(
    "PASS ${report.caseCount} Komga ${report.suiteVersion} differential case(s)",
  )
}

private data class Arguments(
  val reference: String,
  val candidate: String,
  val suite: Path,
) {
  companion object {
    fun parse(args: Array<String>): Arguments {
      val values = mutableMapOf<String, String>()
      var index = 0
      while (index < args.size) {
        val key = args[index]
        require(key in REQUIRED && index + 1 < args.size) {
          "Usage: --reference URL --candidate URL --suite FILE"
        }
        require(values.put(key, args[index + 1]) == null) { "Duplicate argument: $key" }
        index += 2
      }
      require(values.keys == REQUIRED) {
        "Usage: --reference URL --candidate URL --suite FILE"
      }
      return Arguments(
        reference = requireNotNull(values["--reference"]),
        candidate = requireNotNull(values["--candidate"]),
        suite = Path.of(requireNotNull(values["--suite"])).toAbsolutePath().normalize(),
      )
    }

    private val REQUIRED = setOf("--reference", "--candidate", "--suite")
  }
}
