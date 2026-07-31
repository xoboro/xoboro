package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.io.TempDir

/**
 * Asks the running server what shape each list endpoint answers with, and holds the OpenAPI
 * description to that answer.
 *
 * The page envelope has been the repository's most expensive class of defect, and every guard
 * written for it so far has been a comparison between two hand-maintained artifacts. The
 * duplicate-page routes answered the envelope while the client assigned it to an array; the
 * screen rendered nothing and every test passed, because the fixtures encoded the same wrong
 * belief as the code. `web/tests/pageEnvelope.test.js` then compared a hand-written list of
 * paged paths against the spec — and passed while nine endpoints answered the envelope with
 * `schema: { type: object }` documented, because the omission was in both lists at once.
 *
 * So neither list is the authority here. The server is asked, over HTTP, through the real
 * module: a body is an envelope when it is a JSON object carrying `items` and `totalItems`, and
 * the spec must declare `XoboroPage` for exactly those paths.
 *
 * Recognising an envelope from two of its seven keys leaves the rest of the shape unchecked: a
 * response could answer `items` and `totalItems` while `hasNext` is hardcoded and never told the
 * truth about a second page, which "does this look like an envelope" cannot see. So every
 * recognised envelope is also checked for the other five keys and for internal consistency -
 * `hasPrevious` against `page`, `hasNext` and `totalPages` against `page`/`totalItems`/`size` -
 * in [assertConsistentEnvelope].
 *
 * That consistency check is vacuous against an empty catalog, though: with zero items
 * `totalPages` is defined as `0`, so `hasNext` is `false` whether it was computed or hardcoded,
 * and no response in [probeable] can tell the two apart. [seedTwoSeries] gives `/series` two
 * rows before the probe loop runs, purely so the dedicated check after the loop can request
 * `size=1` and observe a real second page - `hasNext = true` on the first of them - which a
 * hardcoded `false` cannot produce.
 *
 * Limits, stated rather than left to be discovered:
 * - Only paths with no template parameter are probed. Filling `{seriesId}` needs a fixture per
 *   endpoint, so `/series/{seriesId}/media-items` and its kind are still checked by nothing but
 *   the two lists. The parameterless set is where all nine misdeclared endpoints were.
 * - A path whose probe does not answer `200` with JSON is skipped, so [MUST_BE_PROBED] names
 *   the ones whose coverage matters most; a probe that starts failing for an unrelated reason
 *   fails here rather than silently dropping out of the comparison.
 */
class XoboroNativePageEnvelopeContractTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `declares the page envelope for exactly the endpoints that answer one`() {
    val spec = readSpec()
    val declared = pathsDeclaringEnvelope(spec)
    val probeable = parameterlessGetPaths(spec)
    require(probeable.size > 20) { "only ${probeable.size} parameterless GET paths parsed" }

    val answered = mutableSetOf<String>()
    val probed = mutableSetOf<String>()
    val databasePath = temporaryDirectory.resolve("page-envelope.sqlite")
    seedTwoSeries(databasePath)

    testApplication {
      application { xoboroModule(openRuntime(databasePath)) }
      val client =
        createClient {
          install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
      val token = adminToken(client)

      for (path in probeable) {
        val response = client.get(path) { bearerAuth(token) }
        val isJson =
          response.contentType()?.match(ContentType.Application.Json) == true
        if (response.status != HttpStatusCode.OK || !isJson) continue
        probed += path
        val body = LENIENT.parseToJsonElement(response.bodyAsText())
        if (body is JsonObject && "items" in body && "totalItems" in body) {
          answered += path
          assertConsistentEnvelope(path, body)
        }
      }

      // The loop above cannot catch a hardcoded `hasNext`: every path it probes answers
      // `totalPages = 0` against this catalog, so `page + 1 < totalPages` is `false`
      // whether it was computed or hardcoded. `/series` has the two rows [seedTwoSeries]
      // planted, so `size=1` forces a real second page here, and the first of the two
      // must report a `hasNext` that a hardcoded `false` could not.
      val firstOfTwo =
        LENIENT
          .parseToJsonElement(
            client.get("$XOBORO_API_PREFIX/series?size=1") { bearerAuth(token) }.bodyAsText(),
          ).jsonObject
      assertEquals(2L, firstOfTwo.getValue("totalItems").jsonPrimitive.long, "$firstOfTwo")
      assertEquals(2, firstOfTwo.getValue("totalPages").jsonPrimitive.int, "$firstOfTwo")
      assertEquals(false, firstOfTwo.getValue("hasPrevious").jsonPrimitive.boolean, "$firstOfTwo")
      assertEquals(true, firstOfTwo.getValue("hasNext").jsonPrimitive.boolean, "$firstOfTwo")

      val secondOfTwo =
        LENIENT
          .parseToJsonElement(
            client
              .get("$XOBORO_API_PREFIX/series?size=1&page=1") { bearerAuth(token) }
              .bodyAsText(),
          ).jsonObject
      assertEquals(true, secondOfTwo.getValue("hasPrevious").jsonPrimitive.boolean, "$secondOfTwo")
      assertEquals(false, secondOfTwo.getValue("hasNext").jsonPrimitive.boolean, "$secondOfTwo")
    }

    assertTrue(
      probed.containsAll(MUST_BE_PROBED),
      "these paths were not probed, so nothing was checked about them: " +
        (MUST_BE_PROBED - probed).sorted().joinToString(", "),
    )

    val undeclared = (answered - declared).sorted()
    val overdeclared = (declared.intersect(probed) - answered).sorted()

    assertTrue(
      undeclared.isEmpty(),
      "these answer the page envelope but the description does not say so, so a client " +
        "written from the description will assign an envelope to an array:\n" +
        undeclared.joinToString("\n"),
    )
    assertTrue(
      overdeclared.isEmpty(),
      "the description promises the page envelope and these answer something else:\n" +
        overdeclared.joinToString("\n"),
    )
  }

  private fun readSpec(): String =
    requireNotNull(javaClass.classLoader.getResourceAsStream(SPEC_RESOURCE)) {
      "$SPEC_RESOURCE is missing from the runtime classpath"
    }.use { it.readBytes().decodeToString() }

  /**
   * Every path whose `get` names the shared envelope as its `200` body.
   *
   * Written as a scan rather than a YAML parse for the same reason the neighbouring contract
   * test gives: a YAML dependency for two tests is a poor trade, and the file's indentation is
   * fixed. The `"200"` block is tracked explicitly so a `XoboroPage` reference under a `400`
   * cannot be mistaken for one under a success.
   */
  private fun pathsDeclaringEnvelope(spec: String): Set<String> {
    val declaring = mutableSetOf<String>()
    var path: String? = null
    var inGet = false
    var inSuccess = false
    spec.lineSequence().forEach { line ->
      when {
        PATH_LINE.matches(line) -> {
          path = line.trim().removeSuffix(":")
          inGet = false
          inSuccess = false
        }
        OPERATION_LINE.matches(line) -> {
          inGet = line.trim() == "get:"
          inSuccess = false
        }
        RESPONSE_LINE.matches(line) -> inSuccess = line.trim() == "\"200\":"
        inGet && inSuccess && ENVELOPE_REFERENCE in line -> path?.let(declaring::add)
      }
    }
    require(declaring.isNotEmpty()) { "no endpoint declares $ENVELOPE_REFERENCE" }
    return declaring
  }

  private fun parameterlessGetPaths(spec: String): List<String> {
    val paths = mutableListOf<String>()
    var path: String? = null
    spec.lineSequence().forEach { line ->
      when {
        PATH_LINE.matches(line) -> path = line.trim().removeSuffix(":")
        OPERATION_LINE.matches(line) && line.trim() == "get:" ->
          path?.takeIf { '{' !in it && it !in NEVER_PROBED }?.let(paths::add)
      }
    }
    return paths
  }

  private suspend fun adminToken(client: HttpClient): String {
    val setup =
      client.post("$XOBORO_API_PREFIX/setup") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
          SetupRequest(
            email = "admin@example.invalid",
            password = "synthetic-password",
            transport = SessionTransport.BEARER,
          ),
        )
      }
    assertEquals(HttpStatusCode.Created, setup.status)
    return requireNotNull(setup.body<SessionResponse>().accessToken)
  }

  private fun openRuntime(databasePath: Path): XoboroRuntime =
    XoboroRuntime.open(
      ServerConfig(
        port = 25_702,
        databasePath = databasePath,
        workerCount = 1,
        taskPollMillis = 50,
        taskFailurePollMillis = 50,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
        backupsDirectory = temporaryDirectory.resolve("backups"),
        webDirectory = temporaryDirectory.resolve("no-web-directory"),
      ),
    )

  /**
   * Writes two series directly into the database `openRuntime` will then serve from.
   *
   * Bypassing the HTTP API and any library scan on purpose: the point of these two rows is
   * only to give `/series` a second page, and neither route exists to create a series with
   * no books, nor should a scan be run just to produce one. [XoboroNativeDiscoveryFeedApplicationTest]
   * seeds its catalog the same way for the same reason - real repositories, no scan.
   */
  private fun seedTwoSeries(databasePath: Path) {
    val libraryId = LibraryId("library-page-envelope")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = libraryId,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic-page-envelope"),
          createdAtMillis = 1,
        ),
      )
      val series = JooqSeriesRepository(database)
      val seriesMetadata = JooqSeriesMetadataRepository(database)
      listOf("series-page-envelope-1", "series-page-envelope-2").forEachIndexed { index, identifier ->
        val seriesId = SeriesId(identifier)
        series.insert(
          Series(
            id = seriesId,
            libraryId = libraryId,
            name = "Synthetic series $index",
            relativePath = "Synthetic series $index",
            sourceItemId = "file:///synthetic-page-envelope/$identifier",
            fileModifiedAtMillis = 1,
            createdAtMillis = (index + 1).toLong(),
          ),
        )
        seriesMetadata.upsert(
          SeriesMetadata(
            seriesId = seriesId,
            title = "Synthetic series $index",
            createdAtMillis = (index + 1).toLong(),
          ),
        )
      }
    }
  }

  /**
   * Every key the envelope owes a caller must be present, and the pagination fields must
   * agree with each other for the response actually returned. Recognising an envelope from
   * `items` and `totalItems` alone would let a response carry both while `hasPrevious`,
   * `hasNext` or `totalPages` told a caller something false - hardcoded, stale, or simply
   * wrong - with nothing here noticing.
   */
  private fun assertConsistentEnvelope(
    path: String,
    body: JsonObject,
  ) {
    assertTrue(
      ENVELOPE_KEYS.all { it in body },
      "$path: envelope is missing a key; body has ${body.keys.sorted()}",
    )
    val page = body.getValue("page").jsonPrimitive.int
    val size = body.getValue("size").jsonPrimitive.int
    val totalItems = body.getValue("totalItems").jsonPrimitive.long
    val totalPages = body.getValue("totalPages").jsonPrimitive.int
    val hasPrevious = body.getValue("hasPrevious").jsonPrimitive.boolean
    val hasNext = body.getValue("hasNext").jsonPrimitive.boolean
    val expectedTotalPages =
      if (totalItems == 0L) {
        0
      } else {
        ((totalItems - 1) / size + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      }
    assertEquals(expectedTotalPages, totalPages, "$path: totalPages disagrees with totalItems/size")
    assertEquals(page > 0, hasPrevious, "$path: hasPrevious disagrees with page")
    assertEquals(page + 1 < totalPages, hasNext, "$path: hasNext disagrees with page/totalPages")
  }

  private companion object {
    const val SPEC_RESOURCE = "openapi/xoboro-native-v1.yaml"
    const val ENVELOPE_REFERENCE = "XoboroPage\""
    val LENIENT = Json { ignoreUnknownKeys = true }

    /** Every key [io.xoboro.server.api.XoboroPageResponse] declares, checked by [assertConsistentEnvelope]. */
    val ENVELOPE_KEYS =
      setOf("items", "page", "size", "totalItems", "totalPages", "hasPrevious", "hasNext")
    val PATH_LINE = Regex("^ {2}/\\S*:$")
    val OPERATION_LINE = Regex("^ {4}(get|post|put|patch|delete):$")
    val RESPONSE_LINE = Regex("^ {8}\"\\d{3}\":$")

    /**
     * The event stream, which never completes by design.
     *
     * A plain `GET` against it does not return, so probing it hangs the whole test rather than
     * reporting anything. It answers `text/event-stream` and could not be an envelope.
     */
    val NEVER_PROBED = setOf("$XOBORO_API_PREFIX/events")

    /**
     * Paths whose probe must reach the comparison.
     *
     * Every one of these answered the envelope while documented as a bare object. A catalog
     * with no books is enough for all of them to answer `200` - `/series` additionally gets
     * two bookless rows from [seedTwoSeries], but that is for the `hasNext` check after the
     * loop, not a precondition for reaching this comparison. So a `200` here is not
     * conditional on fixtures - if one stops answering `200`, that is a change worth failing
     * on rather than a quiet reduction in what this test covers.
     */
    val MUST_BE_PROBED =
      setOf(
        "$XOBORO_API_PREFIX/me/authentication-activity",
        "$XOBORO_API_PREFIX/media-items/feeds/keep-reading",
        "$XOBORO_API_PREFIX/media-items/feeds/new",
        "$XOBORO_API_PREFIX/media-items/feeds/on-deck",
        "$XOBORO_API_PREFIX/media-items/feeds/recently-read",
        "$XOBORO_API_PREFIX/media-items/feeds/updated",
        "$XOBORO_API_PREFIX/series/feeds/new",
        "$XOBORO_API_PREFIX/series/feeds/recently-read",
        "$XOBORO_API_PREFIX/series/feeds/updated",
        "$XOBORO_API_PREFIX/series",
        "$XOBORO_API_PREFIX/media-items",
        "$XOBORO_API_PREFIX/history",
        "$XOBORO_API_PREFIX/authentication-activity",
        "$XOBORO_API_PREFIX/duplicate-pages",
        "$XOBORO_API_PREFIX/duplicate-pages/decided",
        "$XOBORO_API_PREFIX/facets/authors",
        "$XOBORO_API_PREFIX/collections",
        "$XOBORO_API_PREFIX/read-lists",
        "$XOBORO_API_PREFIX/libraries",
        "$XOBORO_API_PREFIX/users",
      )
  }
}
