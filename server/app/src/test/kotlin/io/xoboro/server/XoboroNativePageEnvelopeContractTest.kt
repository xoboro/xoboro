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
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XoboroCollectionCreationRequest
import io.xoboro.server.api.XoboroCollectionResponse
import io.xoboro.server.api.XoboroReadListCreationRequest
import io.xoboro.server.api.XoboroReadListResponse
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookRepository
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
 * Parameterised paths are also probed now, not left to the two lists the class doc above already
 * distrusts. [seedParameterizedCatalog] adds one media item, whose one page carries a known hash,
 * to one of [seedTwoSeries]'s two rows, and [createCollection]/[createReadList] put that series
 * and that media item into a collection and a read-list over the real `POST` endpoints
 * (administration requires an admin, which the setup token already is) - between the two, every
 * path segment (`{seriesId}`, `{pageHash}`, `{collectionId}`, `{readListId}`, `{mediaItemId}`)
 * needed below has a real value to substitute. [PARAMETERIZED_ENVELOPE_PATHS] is not trusted either: it is checked
 * against `declared.filter { '{' in it }` - every path the spec itself declares `XoboroPage` for
 * - so a fifth parameterised path starting to declare the envelope fails loudly with a message
 * naming the missing resolver, rather than being silently left out of this probe the way it was
 * left out of the two lists before this test existed. [PARAMETERIZED_COLLECTION_PATHS] adds seven
 * more: every other parameterised `GET` whose 200 answers a JSON collection rather than a single
 * resource, found by reading `XoboroNativeCatalogRoutes.kt`, `XoboroNativeCollectionsRoutes.kt`,
 * and `XoboroNativeDeliveryRoutes.kt` rather than trusting the spec text, because the spec
 * documents every one of them as `{ type: object }` - the same uninformative schema a
 * single-resource endpoint carries, so the description cannot distinguish "collection" from
 * "one thing" here. Each of the seven answers a bare JSON array today, never the envelope, so
 * none of them are in `declared` and probing them proves nothing about the *current* code - but a
 * future change that wraps one in `XoboroPageResponse` without updating the description is
 * exactly this file's defect, and only probing catches it as "undeclared" instead of nothing.
 * Single-resource parameterised paths (`/series/{seriesId}`, `/libraries/{libraryId}`, and their
 * kind) were read too and excluded: none of them answer a collection, so probing them could only
 * confirm what reading already settled.
 *
 * Limits, stated rather than left to be discovered:
 * - A path whose probe does not answer `200` with JSON is skipped, so [MUST_BE_PROBED] names
 *   the ones whose coverage matters most; a probe that starts failing for an unrelated reason
 *   fails here rather than silently dropping out of the comparison. That now includes every
 *   parameterised path this test resolves a fixture for.
 * - Parameterised paths taking more than one path parameter (`/media-items/{mediaItemId}/
 *   artworks/{artworkId}`, `/media-items/{mediaItemId}/pages/{pageNumber}`,
 *   `/media-items/{mediaItemId}/resources/{resource...}`, and their `selected` counterparts) are
 *   still not probed. Every one of them answers a single binary resource, not a collection, so
 *   none of them is a candidate for the defect this file exists to catch.
 * - [PARAMETERIZED_ENVELOPE_PATHS] is checked against the description at runtime, so a sixth path
 *   starting to declare `XoboroPage` cannot be missed silently. [PARAMETERIZED_COLLECTION_PATHS]
 *   has no equivalent check - it is a written list, like [MUST_BE_PROBED] and [NEVER_PROBED]
 *   above it, arrived at by reading route source rather than the description, which cannot tell a
 *   collection from a single resource for any of these seven. A new parameterised collection
 *   endpoint added elsewhere is not caught by this file until someone adds it here.
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

    // Not trusted either: [PARAMETERIZED_ENVELOPE_PATHS] must be exactly the parameterised paths
    // the description declares `XoboroPage` for. If a path is added to or removed from either
    // side, this fails by naming the mismatch, rather than this test quietly covering a path that
    // no longer needs it or missing one that newly does.
    val parameterizedDeclared = declared.filter { '{' in it }.toSet()
    require(parameterizedDeclared == PARAMETERIZED_ENVELOPE_PATHS) {
      "the parameterised paths declaring the envelope changed - now $parameterizedDeclared - " +
        "so PARAMETERIZED_ENVELOPE_PATHS and its fixture resolvers need updating to match"
    }

    val answered = mutableSetOf<String>()
    val probed = mutableSetOf<String>()
    val databasePath = temporaryDirectory.resolve("page-envelope.sqlite")
    seedTwoSeries(databasePath)
    val parameterizedSeed = seedParameterizedCatalog(databasePath)

    testApplication {
      application { xoboroModule(openRuntime(databasePath)) }
      val client =
        createClient {
          install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
      val token = adminToken(client)

      for (path in probeable) {
        probe(
          client = client,
          token = token,
          templatePath = path,
          requestPath = path,
          probed = probed,
          answered = answered,
        )
      }

      // Puts the seeded series and media item into a real collection and a real read-list, over
      // the same administrator-only creation endpoints a caller would use - not written directly
      // into the database, because the point is that both members are *visible members of an
      // organised group*, which is what the routes below check for and only the routes can grant.
      val fixtures =
        ParameterizedFixtures(
          seriesId = parameterizedSeed.seriesId,
          mediaItemId = parameterizedSeed.mediaItemId,
          pageHash = parameterizedSeed.pageHash,
          collectionId = createCollection(client, token, parameterizedSeed.seriesId),
          readListId = createReadList(client, token, parameterizedSeed.mediaItemId),
        )

      for (path in PARAMETERIZED_ENVELOPE_PATHS + PARAMETERIZED_COLLECTION_PATHS) {
        probe(
          client = client,
          token = token,
          templatePath = path,
          requestPath = resolveParameterizedPath(path, fixtures),
          probed = probed,
          answered = answered,
        )
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

  /**
   * Requests [requestPath] and records the outcome under [templatePath] - the spelling the
   * description and [MUST_BE_PROBED] use, which for a parameterised path is not the same string
   * as what was actually requested. Shared between the parameterless loop, where the two are
   * equal, and the parameterised one below it, so both are checked by the same rule about what
   * counts as an envelope.
   */
  private suspend fun probe(
    client: HttpClient,
    token: String,
    templatePath: String,
    requestPath: String,
    probed: MutableSet<String>,
    answered: MutableSet<String>,
  ) {
    val response = client.get(requestPath) { bearerAuth(token) }
    val isJson = response.contentType()?.match(ContentType.Application.Json) == true
    if (response.status != HttpStatusCode.OK || !isJson) return
    probed += templatePath
    val body = LENIENT.parseToJsonElement(response.bodyAsText())
    if (body is JsonObject && "items" in body && "totalItems" in body) {
      answered += templatePath
      assertConsistentEnvelope(templatePath, body)
    }
  }

  /**
   * Creates a real collection over `POST /collections`, administrator-only, and returns its id.
   *
   * Not a direct database write: `GET /collections/{collectionId}/series` filters its members to
   * ones *visible* through the catalog, which is exactly what the creation route already checks
   * before accepting [seriesId] - reproducing that check with a second, hand-written definition
   * of "visible" here would be a second source of truth for the same question.
   */
  private suspend fun createCollection(
    client: HttpClient,
    token: String,
    seriesId: String,
  ): String {
    val response =
      client.post("$XOBORO_API_PREFIX/collections") {
        bearerAuth(token)
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
          XoboroCollectionCreationRequest(
            name = "Synthetic parameterised-fixture collection",
            ordered = false,
            seriesIds = listOf(seriesId),
          ),
        )
      }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.body<XoboroCollectionResponse>().id
  }

  /** Same reasoning as [createCollection], for `POST /read-lists`. */
  private suspend fun createReadList(
    client: HttpClient,
    token: String,
    mediaItemId: String,
  ): String {
    val response =
      client.post("$XOBORO_API_PREFIX/read-lists") {
        bearerAuth(token)
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
          XoboroReadListCreationRequest(
            name = "Synthetic parameterised-fixture read list",
            ordered = false,
            mediaItemIds = listOf(mediaItemId),
          ),
        )
      }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.body<XoboroReadListResponse>().id
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
   * Writes one media item, its one page carrying [PARAMETERIZED_PAGE_HASH], directly into the
   * database - the same reasoning as [seedTwoSeries]: no route creates a media item, and running
   * a scan to produce one file would test the scanner, not the envelope. [JooqPageHashRepositoryTest]
   * seeds a shared file hash across two books the same way; one book is enough here because
   * `findMatches` (unlike `findUnknown`, which groups by "more than one match") returns a hash
   * with a single match too.
   *
   * The media item is filed under `series-page-envelope-1`, one of [seedTwoSeries]'s two rows,
   * rather than a series of its own: a third series would be a third row `/series` returns to
   * this admin, and the dedicated `hasNext` check right after the probe loop hardcodes "two rows,
   * two pages of one" as the shape that proves `hasNext` was computed rather than a constant
   * `false` - a real third series would still prove that, but would need that check's page counts
   * rewritten to match, for a fixture with no other reason to need its own series.
   *
   * Returns the plain identifiers rather than domain ids: every caller of this method's result
   * is either substituting into a URL path or serialising a JSON request body, and both want a
   * `String`.
   */
  private fun seedParameterizedCatalog(databasePath: Path): ParameterizedSeed {
    val libraryId = LibraryId("library-page-envelope")
    val seriesId = SeriesId("series-page-envelope-1")
    val mediaItemId = BookId("media-page-envelope-params")
    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqBookRepository(database).insert(
        Book(
          id = mediaItemId,
          libraryId = libraryId,
          seriesId = seriesId,
          name = "Synthetic parameterised-fixture issue.cbz",
          relativePath = "Synthetic series 0/issue.cbz",
          sourceItemId = "file:///synthetic-page-envelope-params/issue.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          fileSize = 100,
          number = 1,
          createdAtMillis = 1,
        ),
      )
      JooqBookMediaRepository(database).upsert(
        BookMedia(
          bookId = mediaItemId,
          status = MediaStatus.READY,
          mediaType = "application/zip",
          profile = MediaProfile.DIVINA,
          pages =
            listOf(
              BookPage(
                number = 1,
                fileName = "001.png",
                mediaType = "image/png",
                fileSize = 12,
                fileHash = PARAMETERIZED_PAGE_HASH,
              ),
            ),
          createdAtMillis = 1,
        ),
      )
    }
    return ParameterizedSeed(
      seriesId = seriesId.value,
      mediaItemId = mediaItemId.value,
      pageHash = PARAMETERIZED_PAGE_HASH,
    )
  }

  /**
   * Resolves a template path from the description - `.../{seriesId}/...` and its kind - to the
   * concrete path this test actually requests. `else` fails rather than falling through, so a
   * parameterised path this test does not yet know how to fixture is a named failure here
   * instead of a silently unprobed path, for both [PARAMETERIZED_ENVELOPE_PATHS] - checked
   * against the description at runtime, so this branch is reachable for real - and
   * [PARAMETERIZED_COLLECTION_PATHS], which is not, but the outcome should still be visible
   * rather than a quiet gap if a typo puts something unresolvable into that list.
   */
  private fun resolveParameterizedPath(
    path: String,
    fixtures: ParameterizedFixtures,
  ): String =
    when (path) {
      "$XOBORO_API_PREFIX/series/{seriesId}/media-items" ->
        "$XOBORO_API_PREFIX/series/${fixtures.seriesId}/media-items"
      "$XOBORO_API_PREFIX/series/{seriesId}/artworks" ->
        "$XOBORO_API_PREFIX/series/${fixtures.seriesId}/artworks"
      "$XOBORO_API_PREFIX/series/{seriesId}/collections" ->
        "$XOBORO_API_PREFIX/series/${fixtures.seriesId}/collections"
      "$XOBORO_API_PREFIX/duplicate-pages/{pageHash}/media-items" ->
        "$XOBORO_API_PREFIX/duplicate-pages/${fixtures.pageHash}/media-items"
      "$XOBORO_API_PREFIX/collections/{collectionId}/series" ->
        "$XOBORO_API_PREFIX/collections/${fixtures.collectionId}/series"
      "$XOBORO_API_PREFIX/read-lists/{readListId}/media-items" ->
        "$XOBORO_API_PREFIX/read-lists/${fixtures.readListId}/media-items"
      "$XOBORO_API_PREFIX/media-items/{mediaItemId}/read-lists" ->
        "$XOBORO_API_PREFIX/media-items/${fixtures.mediaItemId}/read-lists"
      "$XOBORO_API_PREFIX/media-items/{mediaItemId}/artworks" ->
        "$XOBORO_API_PREFIX/media-items/${fixtures.mediaItemId}/artworks"
      "$XOBORO_API_PREFIX/media-items/{mediaItemId}/positions" ->
        "$XOBORO_API_PREFIX/media-items/${fixtures.mediaItemId}/positions"
      "$XOBORO_API_PREFIX/media-items/{mediaItemId}/resources" ->
        "$XOBORO_API_PREFIX/media-items/${fixtures.mediaItemId}/resources"
      "$XOBORO_API_PREFIX/media-items/{mediaItemId}/pages" ->
        "$XOBORO_API_PREFIX/media-items/${fixtures.mediaItemId}/pages"
      else ->
        error(
          "no fixture resolver for parameterised path $path; add one so this test keeps " +
            "covering every path the description promises the page envelope for, or every " +
            "path it names as an undeclared-collection risk",
        )
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
     * The file hash [seedParameterizedCatalog] gives the one page it writes, and the identifier
     * `duplicate-pages/{pageHash}/media-items` is probed with. `findMatches` (unlike
     * `findUnknown`) does not require more than one book to share a hash, so seeding a single
     * page under a hash nobody else uses is enough for the route to answer `200` with one match.
     */
    const val PARAMETERIZED_PAGE_HASH = "page-hash-page-envelope-params"

    /**
     * The parameterised paths the description currently declares `XoboroPage` for - checked
     * against `declared.filter { '{' in it }` at the top of the test, so this going stale is a
     * loud failure rather than a silent gap. These are the four the historical nine-endpoint
     * defect was found among that a fixture could not yet reach.
     */
    val PARAMETERIZED_ENVELOPE_PATHS =
      setOf(
        "$XOBORO_API_PREFIX/series/{seriesId}/media-items",
        "$XOBORO_API_PREFIX/duplicate-pages/{pageHash}/media-items",
        "$XOBORO_API_PREFIX/collections/{collectionId}/series",
        "$XOBORO_API_PREFIX/read-lists/{readListId}/media-items",
      )

    /**
     * Every other parameterised `GET` whose 200 answers a JSON collection, per
     * `XoboroNativeCollectionsRoutes.kt` and `XoboroNativeDeliveryRoutes.kt` - not per the
     * description, which documents every one of these as `{ type: object }` and so cannot
     * distinguish them from a single-resource endpoint. None of them declare the envelope and
     * none of them answer it today; probing them anyway is what would notice a future change
     * that wraps one in `XoboroPageResponse` without updating the description to match, which
     * is exactly how the original nine-endpoint defect went unseen.
     */
    val PARAMETERIZED_COLLECTION_PATHS =
      setOf(
        "$XOBORO_API_PREFIX/series/{seriesId}/artworks",
        "$XOBORO_API_PREFIX/series/{seriesId}/collections",
        "$XOBORO_API_PREFIX/media-items/{mediaItemId}/read-lists",
        "$XOBORO_API_PREFIX/media-items/{mediaItemId}/artworks",
        "$XOBORO_API_PREFIX/media-items/{mediaItemId}/positions",
        "$XOBORO_API_PREFIX/media-items/{mediaItemId}/resources",
        "$XOBORO_API_PREFIX/media-items/{mediaItemId}/pages",
      )

    /**
     * Paths whose probe must reach the comparison.
     *
     * Every one of the parameterless paths here answered the envelope while documented as a bare
     * object. A catalog with no books is enough for all of them to answer `200` - `/series`
     * additionally gets two bookless rows from [seedTwoSeries], but that is for the `hasNext`
     * check after the loop, not a precondition for reaching this comparison. So a `200` here is
     * not conditional on fixtures - if one stops answering `200`, that is a change worth failing
     * on rather than a quiet reduction in what this test covers. [PARAMETERIZED_ENVELOPE_PATHS]
     * and [PARAMETERIZED_COLLECTION_PATHS] are added for the same reason, now that
     * [seedParameterizedCatalog], [createCollection], and [createReadList] give every one of
     * them a real resource to be reached with.
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
      ) + PARAMETERIZED_ENVELOPE_PATHS + PARAMETERIZED_COLLECTION_PATHS
  }

  /** Plain identifiers [seedParameterizedCatalog] wrote into the database. */
  private data class ParameterizedSeed(
    val seriesId: String,
    val mediaItemId: String,
    val pageHash: String,
  )

  /**
   * Everything [resolveParameterizedPath] needs: [ParameterizedSeed]'s three identifiers, plus
   * the collection id and read-list id [createCollection] and [createReadList] mint over HTTP
   * once the application is running.
   */
  private data class ParameterizedFixtures(
    val seriesId: String,
    val mediaItemId: String,
    val pageHash: String,
    val collectionId: String,
    val readListId: String,
  )
}
