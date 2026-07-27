package io.xoboro.compatibility.komga.differential

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DifferentialRunnerTest {
  @Test
  fun `normalizes ignored fields unordered arrays and object keys`() {
    val transport =
      fixtureTransport(
        reference =
          jsonSnapshot(
            """
            {
              "id": "reference-id",
              "name": "Synthetic item",
              "roles": ["ADMIN", "USER"],
              "children": [
                {"id": "reference-child", "value": 1}
              ]
            }
            """,
          ),
        candidate =
          jsonSnapshot(
            """
            {
              "children": [
                {"value": 1, "id": "candidate-child"}
              ],
              "roles": ["USER", "ADMIN"],
              "name": "Synthetic item",
              "id": "candidate-id"
            }
            """,
          ),
      )
    val suite =
      suite(
        ComparisonPolicy(
          ignoreJsonPaths = setOf("/id", "/children/*/id"),
          unorderedJsonPaths = setOf("/roles"),
        ),
      )

    val report = DifferentialRunner(transport).run(suite, REFERENCE, CANDIDATE)

    assertTrue(report.passed)
    assertEquals(1, report.caseCount)
  }

  @Test
  fun `reports status header and body differences independently`() {
    val transport =
      fixtureTransport(
        reference =
          HttpSnapshot(
            status = 200,
            headers =
              mapOf(
                "Content-Type" to listOf("application/json;charset=UTF-8"),
                "ETag" to listOf("reference"),
              ),
            body = """{"value":1}""".encodeToByteArray(),
          ),
        candidate =
          HttpSnapshot(
            status = 201,
            headers =
              mapOf(
                "content-type" to listOf("application/problem+json"),
                "etag" to listOf("candidate"),
              ),
            body = """{"value":2}""".encodeToByteArray(),
          ),
      )
    val suite = suite(ComparisonPolicy(headers = setOf("ETag")))

    val report = DifferentialRunner(transport).run(suite, REFERENCE, CANDIDATE)

    assertFalse(report.passed)
    assertEquals(
      setOf("status", "header:Content-Type", "header:ETag", "body:json"),
      report.failures.mapTo(mutableSetOf(), DifferentialFailure::aspect),
    )
  }

  @Test
  fun `hashes binary bodies without decoding them`() {
    val matching =
      DifferentialRunner(
        fixtureTransport(
          HttpSnapshot(200, mapOf("Content-Type" to listOf("image/jpeg")), byteArrayOf(0, -1)),
          HttpSnapshot(200, mapOf("content-type" to listOf("image/jpeg")), byteArrayOf(0, -1)),
        ),
      ).run(
        suite(ComparisonPolicy(bodyMode = BodyMode.BINARY)),
        REFERENCE,
        CANDIDATE,
      )
    val different =
      DifferentialRunner(
        fixtureTransport(
          HttpSnapshot(200, mapOf("Content-Type" to listOf("image/jpeg")), byteArrayOf(0, -1)),
          HttpSnapshot(200, mapOf("Content-Type" to listOf("image/jpeg")), byteArrayOf(0, 1)),
        ),
      ).run(
        suite(ComparisonPolicy(bodyMode = BodyMode.BINARY)),
        REFERENCE,
        CANDIDATE,
      )

    assertTrue(matching.passed)
    assertEquals(listOf("body:binary"), different.failures.map(DifferentialFailure::aspect))
  }

  @Test
  fun `passes authorization to each endpoint without storing it in a suite`() {
    val observed = mutableListOf<Pair<String, String?>>()
    val transport =
      DifferentialHttpTransport { baseUrl, _, authorization ->
        observed += baseUrl to authorization
        jsonSnapshot("""{"ok":true}""")
      }

    DifferentialRunner(transport).run(
      suite(),
      REFERENCE,
      CANDIDATE,
      referenceAuthorization = "Reference synthetic token",
      candidateAuthorization = "Candidate synthetic token",
    )

    assertEquals(
      listOf<Pair<String, String?>>(
        REFERENCE to "Reference synthetic token",
        CANDIDATE to "Candidate synthetic token",
      ),
      observed,
    )
  }

  @Test
  fun `rejects unsafe requests and malformed paths`() {
    assertFailsWith<IllegalArgumentException> {
      DifferentialCase(name = "unsafe", path = "https://example.invalid/private")
    }
    assertFailsWith<IllegalArgumentException> {
      DifferentialCase(
        name = "unsafe",
        path = "/safe",
        headers = mapOf("X-Synthetic" to "value\r\nInjected: true"),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      ComparisonPolicy(ignoreJsonPaths = setOf("/invalid~2path"))
    }
  }

  @Test
  fun `reports transport and malformed body failures without aborting the suite`() {
    val transport =
      DifferentialHttpTransport { baseUrl, _, _ ->
        if (baseUrl == REFERENCE) {
          jsonSnapshot("""{"ok":true}""")
        } else {
          HttpSnapshot(
            status = 200,
            headers = mapOf("Content-Type" to listOf("application/json")),
            body = "not-json".encodeToByteArray(),
          )
        }
      }

    val malformed = DifferentialRunner(transport).run(suite(), REFERENCE, CANDIDATE)
    val unavailable =
      DifferentialRunner(
        DifferentialHttpTransport { baseUrl, _, _ ->
          if (baseUrl == REFERENCE) error("Synthetic network failure") else jsonSnapshot("""{"ok":true}""")
        },
      ).run(suite(), REFERENCE, CANDIDATE)

    assertEquals(listOf("body:json"), malformed.failures.map(DifferentialFailure::aspect))
    assertEquals(
      listOf("transport:reference"),
      unavailable.failures.map(DifferentialFailure::aspect),
    )
  }

  private fun suite(policy: ComparisonPolicy = ComparisonPolicy()): DifferentialSuite =
    DifferentialSuite(
      version = "synthetic",
      cases =
        listOf(
          DifferentialCase(
            name = "synthetic case",
            path = "/api/synthetic",
            comparison = policy,
          ),
        ),
    )

  private fun fixtureTransport(
    reference: HttpSnapshot,
    candidate: HttpSnapshot,
  ): DifferentialHttpTransport =
    DifferentialHttpTransport { baseUrl, _, _ ->
      when (baseUrl) {
        REFERENCE -> reference
        CANDIDATE -> candidate
        else -> error("Unexpected synthetic endpoint")
      }
    }

  private fun jsonSnapshot(body: String): HttpSnapshot =
    HttpSnapshot(
      status = 200,
      headers = mapOf("Content-Type" to listOf("application/json")),
      body = body.trimIndent().encodeToByteArray(),
    )

  companion object {
    private const val REFERENCE = "https://reference.example.invalid"
    private const val CANDIDATE = "https://candidate.example.invalid"
  }
}
