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
  fun `canonicalizes XML namespaces attributes whitespace and ignored paths`() {
    val reference =
      xmlSnapshot(
        """
        <atom:feed xmlns:atom="urn:synthetic:atom">
          <atom:updated>reference-time</atom:updated>
          <atom:entry fixture="same" volatile="reference">
            <atom:title>Synthetic item</atom:title>
          </atom:entry>
        </atom:feed>
        """,
      )
    val candidate =
      xmlSnapshot(
        """
        <feed xmlns="urn:synthetic:atom"><updated>candidate-time</updated><entry
          volatile="candidate" fixture="same"><title>Synthetic item</title></entry></feed>
        """,
      )
    val policy =
      ComparisonPolicy(
        bodyMode = BodyMode.XML,
        ignoreXmlPaths = setOf("/feed/updated", "/feed/entry/@volatile"),
      )

    val report =
      DifferentialRunner(fixtureTransport(reference, candidate))
        .run(suite(policy), REFERENCE, CANDIDATE)

    assertTrue(report.passed)
  }

  @Test
  fun `rejects unsafe XML and reports structural XML differences`() {
    val unsafe =
      xmlSnapshot(
        """<!DOCTYPE feed [<!ENTITY external SYSTEM "file:///invalid">]><feed>&external;</feed>""",
      )
    val safe = xmlSnapshot("<feed><title>Synthetic</title></feed>")
    val malformed =
      DifferentialRunner(fixtureTransport(unsafe, safe))
        .run(suite(ComparisonPolicy(bodyMode = BodyMode.XML)), REFERENCE, CANDIDATE)
    val different =
      DifferentialRunner(
        fixtureTransport(
          xmlSnapshot("<feed><title>Reference</title></feed>"),
          xmlSnapshot("<feed><title>Candidate</title></feed>"),
        ),
      ).run(suite(ComparisonPolicy(bodyMode = BodyMode.XML)), REFERENCE, CANDIDATE)

    assertEquals(listOf("body:xml"), malformed.failures.map(DifferentialFailure::aspect))
    assertEquals(listOf("body:xml"), different.failures.map(DifferentialFailure::aspect))
  }

  @Test
  fun `can compare status and headers without comparing a body`() {
    val report =
      DifferentialRunner(
        fixtureTransport(
          jsonSnapshot("""{"reference":true}"""),
          jsonSnapshot("""{"candidate":true}"""),
        ),
      ).run(
        suite(ComparisonPolicy(bodyMode = BodyMode.NONE)),
        REFERENCE,
        CANDIDATE,
      )

    assertTrue(report.passed)
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
  fun `resolves independent safe path variables for each server`() {
    val observed = mutableListOf<Pair<String, String>>()
    val transport =
      DifferentialHttpTransport { baseUrl, request, _ ->
        observed += baseUrl to request.path
        jsonSnapshot("""{"ok":true}""")
      }
    val suite =
      DifferentialSuite(
        version = "synthetic",
        cases =
          listOf(
            DifferentialCase(
              name = "dynamic resource",
              path = "/api/v1/books/{BOOK_ID}/file",
              pathVariables = setOf("BOOK_ID"),
            ),
          ),
      )

    val report =
      DifferentialRunner(transport).run(
        suite,
        REFERENCE,
        CANDIDATE,
        referencePathVariables = mapOf("BOOK_ID" to "reference-book"),
        candidatePathVariables = mapOf("BOOK_ID" to "candidate-book"),
      )

    assertTrue(report.passed)
    assertEquals(
      listOf(
        REFERENCE to "/api/v1/books/reference-book/file",
        CANDIDATE to "/api/v1/books/candidate-book/file",
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
    assertFailsWith<IllegalArgumentException> {
      ComparisonPolicy(ignoreXmlPaths = setOf("/feed//title"))
    }
    assertFailsWith<IllegalArgumentException> {
      DifferentialCase(
        name = "undeclared variable",
        path = "/api/v1/books/{BOOK_ID}",
      )
    }
    assertFailsWith<IllegalArgumentException> {
      DifferentialCase(
        name = "partial segment",
        path = "/api/v1/books/prefix-{BOOK_ID}",
        pathVariables = setOf("BOOK_ID"),
      )
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

  private fun xmlSnapshot(body: String): HttpSnapshot =
    HttpSnapshot(
      status = 200,
      headers = mapOf("Content-Type" to listOf("application/atom+xml")),
      body = body.trimIndent().encodeToByteArray(),
    )

  companion object {
    private const val REFERENCE = "https://reference.example.invalid"
    private const val CANDIDATE = "https://candidate.example.invalid"
  }
}
