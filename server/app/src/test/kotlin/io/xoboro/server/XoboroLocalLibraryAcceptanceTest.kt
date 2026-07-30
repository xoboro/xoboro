package io.xoboro.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XoboroApiError
import io.xoboro.server.api.XoboroLibraryAdministrationRequest
import io.xoboro.server.api.XoboroLibraryResponse
import io.xoboro.server.api.XoboroLibrarySourceRequest
import io.xoboro.server.api.XoboroMediaItemResponse
import io.xoboro.server.api.XoboroMediaProgressRequest
import io.xoboro.server.api.XoboroPageResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir

/**
 * Drives a real library tree through the native API only, from an unclaimed server to read progress
 * that survives a restart.
 *
 * This is the `Local-library end-to-end acceptance` release gate, and the emphasis is on what the other
 * tests do **not** do. `XoboroNativeCatalogApplicationTest` seeds the database directly, so it proves
 * the read surface serves persisted rows but never that a scan produces them. The performance harness
 * scans a generated tree but measures instead of asserting, and never restarts. Neither walks
 * navigation, progress, or delivery against a catalog that a scan built.
 *
 * So every step here goes through HTTP, including library creation and the scan trigger. Reaching into
 * `runtime` to scan would test the scanner while skipping the contract a client actually uses — and the
 * contract is what a release gate is about.
 *
 * Real CBZ files, not database rows: the pages asserted below are bytes that came out of an archive on
 * disk, through the analyzer, into the delivery route.
 */
class XoboroLocalLibraryAcceptanceTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `scans a real tree, navigates it, records progress, and survives a restart`() {
    val libraryRoot = Files.createDirectories(tempDirectory.resolve("library"))
    val series = Files.createDirectories(libraryRoot.resolve("Synthetic Series"))
    // Three items so previous/next has a middle: an item with both neighbours is the only one that can
    // show the walk is ordered rather than merely non-empty.
    listOf("01", "02", "03").forEach { number ->
      writeComicArchive(series.resolve("Synthetic Series $number.cbz"), pageCount = 2)
    }
    val databasePath = tempDirectory.resolve("acceptance.sqlite")

    var mediaItemIds: List<String> = emptyList()
    var libraryId = ""

    // First runtime: claim, register, scan, read.
    XoboroRuntime.open(serverConfig(databasePath, port = 25_610)).use { runtime ->
      testApplication {
        application { xoboroModule(runtime) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.claimAdministrator()

        libraryId =
          client
            .post("$XOBORO_API_PREFIX/libraries") {
              bearerAuth(token)
              jsonBody(
                XoboroLibraryAdministrationRequest(
                  name = "Synthetic library",
                  source =
                    XoboroLibrarySourceRequest(
                      provider = "local",
                      location = libraryRoot.toUri().toString(),
                    ),
                ),
              )
            }.expecting(HttpStatusCode.Created)
            .body<XoboroLibraryResponse>()
            .id

        client
          .post("$XOBORO_API_PREFIX/libraries/$libraryId/scan") { bearerAuth(token) }
          .expecting(HttpStatusCode.Accepted)

        // The scan is a queued task, so the catalog appears asynchronously. Polling the public listing
        // is deliberate: a client has no other way to know, so if this cannot be observed here it
        // cannot be observed by a real reader either.
        // Ordered by sortNumber, not by listing position. previous/next walk the in-series reading
        // order, and the default listing sort is not that order - taking positions from the listing
        // made the middle item actually be the last, so `next` correctly returned 404 and the test was
        // wrong rather than the server.
        mediaItemIds =
          client
            .awaitMediaItems(token, expected = 3)
            .sortedBy(XoboroMediaItemResponse::sortNumber)
            .map { it.id }
        assertEquals(3, mediaItemIds.size)

        client
          .post("$XOBORO_API_PREFIX/libraries/$libraryId/analyze") { bearerAuth(token) }
          .expecting(HttpStatusCode.Accepted)
        val analyzed = client.awaitAnalyzedPages(token, mediaItemIds[1])
        assertEquals(2, analyzed, "the middle item should report the two pages its archive holds")

        // Delivery: real bytes out of a real archive.
        val page =
          client.get("$XOBORO_API_PREFIX/media-items/${mediaItemIds[1]}/pages/1") {
            bearerAuth(token)
          }
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.body<ByteArray>().isNotEmpty(), "page 1 should deliver bytes")

        // Navigation across three items, from the middle outwards.
        val previous =
          client
            .get("$XOBORO_API_PREFIX/media-items/${mediaItemIds[1]}/previous") { bearerAuth(token) }
            .expecting(HttpStatusCode.OK)
            .body<XoboroMediaItemResponse>()
        val next =
          client
            .get("$XOBORO_API_PREFIX/media-items/${mediaItemIds[1]}/next") { bearerAuth(token) }
            .expecting(HttpStatusCode.OK)
            .body<XoboroMediaItemResponse>()
        assertEquals(mediaItemIds[0], previous.id)
        assertEquals(mediaItemIds[2], next.id)

        // The ends have no neighbour, and that is a 404 rather than a wrap-around: a reader at the last
        // item must not silently loop back to the first.
        assertEquals(
          HttpStatusCode.NotFound,
          client.get("$XOBORO_API_PREFIX/media-items/${mediaItemIds[0]}/previous") {
            bearerAuth(token)
          }.status,
        )
        assertEquals(
          HttpStatusCode.NotFound,
          client.get("$XOBORO_API_PREFIX/media-items/${mediaItemIds[2]}/next") {
            bearerAuth(token)
          }.status,
        )

        // Progress, then the conflict contract.
        client
          .putProgress(token, mediaItemIds[1], page = 2, modifiedAtMillis = 2_000)
          .expecting(HttpStatusCode.OK)
        val stale =
          client.putProgress(token, mediaItemIds[1], page = 1, modifiedAtMillis = 1_000)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        // An older write is refused with a code the client can branch on, not swallowed: two devices
        // syncing out of order must not silently rewind the reader's place.
        assertEquals("stale_progress", stale.body<XoboroApiError>().code)
      }
    }

    // Second runtime, same database file: everything above has to still be there.
    XoboroRuntime.open(serverConfig(databasePath, port = 25_611)).use { runtime ->
      testApplication {
        application { xoboroModule(runtime) }
        val client = createClient { install(ContentNegotiation) { json() } }
        // Setup is already claimed, so this signs in rather than claiming again - itself an assertion
        // that the claim survived.
        val token = client.signIn()

        val items = client.awaitMediaItems(token, expected = 3)
        // Two separate properties. The same identifiers must exist - a restart must not re-derive them,
        // or every client bookmark breaks. And the reading order must be the same, which is a different
        // claim from the listing returning them in the same sequence: the listing's default sort is not
        // the reading order, so comparing raw listing order would assert something nobody promised.
        assertEquals(
          mediaItemIds.toSet(),
          items.map { it.id }.toSet(),
          "identifiers must be stable across a restart",
        )
        assertEquals(
          mediaItemIds,
          items.sortedBy(XoboroMediaItemResponse::sortNumber).map { it.id },
          "reading order must be stable across a restart",
        )

        val restored =
          items.single { it.id == mediaItemIds[1] }.progress
            ?: error("read progress should survive a restart")
        assertEquals(2, restored.page)

        // A rescan of an unchanged tree must not disturb the catalog. This is the deletion-safety
        // property stated positively: nothing is removed or re-created just because a scan ran again.
        client
          .post("$XOBORO_API_PREFIX/libraries/$libraryId/scan") { bearerAuth(token) }
          .expecting(HttpStatusCode.Accepted)
        val rescanned = client.awaitMediaItems(token, expected = 3)
        assertEquals(mediaItemIds.toSet(), rescanned.map { it.id }.toSet())
        assertEquals(2, rescanned.single { it.id == mediaItemIds[1] }.progress?.page)
      }
    }
  }

  @Test
  fun `keeps a vanished file recoverable instead of deleting it outright`() {
    val libraryRoot = Files.createDirectories(tempDirectory.resolve("deletion"))
    val series = Files.createDirectories(libraryRoot.resolve("Synthetic Series"))
    val vanishing = series.resolve("Synthetic Series 02.cbz")
    listOf(series.resolve("Synthetic Series 01.cbz"), vanishing).forEach {
      writeComicArchive(it, pageCount = 1)
    }
    val databasePath = tempDirectory.resolve("deletion.sqlite")

    XoboroRuntime.open(serverConfig(databasePath, port = 25_612)).use { runtime ->
      testApplication {
        application { xoboroModule(runtime) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.claimAdministrator()
        val libraryId =
          client
            .post("$XOBORO_API_PREFIX/libraries") {
              bearerAuth(token)
              jsonBody(
                XoboroLibraryAdministrationRequest(
                  name = "Synthetic library",
                  source =
                    XoboroLibrarySourceRequest(
                      provider = "local",
                      location = libraryRoot.toUri().toString(),
                    ),
                ),
              )
            }.expecting(HttpStatusCode.Created)
            .body<XoboroLibraryResponse>()
            .id

        client
          .post("$XOBORO_API_PREFIX/libraries/$libraryId/scan") { bearerAuth(token) }
          .expecting(HttpStatusCode.Accepted)
        val before = client.awaitMediaItems(token, expected = 2)
        assertEquals(2, before.size)

        // The file disappears the way a real one does: someone moved it, a mount dropped, a sync tool
        // reorganised the folder. The catalog must not treat that as a permanent deletion on sight.
        Files.delete(vanishing)
        client
          .post("$XOBORO_API_PREFIX/libraries/$libraryId/scan") { bearerAuth(token) }
          .expecting(HttpStatusCode.Accepted)
        val after = client.awaitMediaItems(token, expected = 1)

        assertEquals(1, after.size, "the vanished item should leave the default listing")
        // Still recoverable, not gone: it is listed as trashed until an operator empties the trash.
        // A scan against a temporarily unavailable mount must not destroy reading history.
        // `trashed=true` on the listing, not a sub-resource: the listing selects between live and
        // trashed entries and never returns both.
        val trashed =
          client
            .get("$XOBORO_API_PREFIX/media-items?trashed=true&size=50") { bearerAuth(token) }
            .expecting(HttpStatusCode.OK)
            .body<XoboroPageResponse<XoboroMediaItemResponse>>()
        assertEquals(1, trashed.items.size, "the vanished item should be recoverable from the trash")
        assertEquals(
          before.map { it.id }.toSet() - after.map { it.id }.toSet(),
          trashed.items.map { it.id }.toSet(),
        )
      }
    }
  }

  private fun serverConfig(
    databasePath: Path,
    port: Int,
  ): ServerConfig =
    ServerConfig(
      port = port,
      databasePath = databasePath,
      workerCount = 1,
      // Fast polling: the assertions wait for queued work, and a production interval would make this
      // test spend most of its time asleep.
      taskPollMillis = 10,
      taskFailurePollMillis = 10,
      taskLeaseMillis = 1_000,
      shutdownTimeoutMillis = 2_000,
    )

  /**
   * Writes a real CBZ whose pages are decodable images.
   *
   * Not a zip of arbitrary bytes: the analyzer reads dimensions, so a page has to be an image or the
   * item never reaches READY and the delivery assertion would be testing an error path instead.
   */
  private fun writeComicArchive(
    path: Path,
    pageCount: Int,
  ) {
    val image = java.awt.image.BufferedImage(4, 6, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val bytes =
      java.io.ByteArrayOutputStream().use { output ->
        check(javax.imageio.ImageIO.write(image, "jpeg", output))
        output.toByteArray()
      }
    ZipOutputStream(Files.newOutputStream(path)).use { archive ->
      (1..pageCount).forEach { number ->
        archive.putNextEntry(ZipEntry("page-%02d.jpg".format(number)))
        archive.write(bytes)
        archive.closeEntry()
      }
    }
  }

  private suspend fun io.ktor.client.HttpClient.claimAdministrator(): String =
    post("$XOBORO_API_PREFIX/setup") {
      jsonBody(
        SetupRequest(
          email = ADMIN_EMAIL,
          password = ADMIN_PASSWORD,
          transport = SessionTransport.BEARER,
        ),
      )
    }.expecting(HttpStatusCode.Created)
      .body<SessionResponse>()
      .accessToken
      .let(::requireNotNull)

  private suspend fun io.ktor.client.HttpClient.signIn(): String =
    post("$XOBORO_API_PREFIX/session") {
      jsonBody(
        io.xoboro.server.api.LoginRequest(
          email = ADMIN_EMAIL,
          password = ADMIN_PASSWORD,
          transport = SessionTransport.BEARER,
        ),
      )
    }.expecting(HttpStatusCode.OK)
      .body<SessionResponse>()
      .accessToken
      .let(::requireNotNull)

  private suspend fun io.ktor.client.HttpClient.putProgress(
    token: String,
    mediaItemId: String,
    page: Int,
    modifiedAtMillis: Long,
  ): HttpResponse =
    put("$XOBORO_API_PREFIX/media-items/$mediaItemId/progress") {
      bearerAuth(token)
      jsonBody(
        XoboroMediaProgressRequest(
          page = page,
          locator = JsonObject(mapOf("page" to JsonPrimitive(page))),
          deviceId = "synthetic-device",
          deviceName = "Synthetic device",
          modifiedAtMillis = modifiedAtMillis,
        ),
      )
    }

  /**
   * Polls the listing until it holds [expected] items.
   *
   * Polling, not a fixed sleep: a sleep long enough to be reliable on a slow machine wastes that time on
   * every run, and one short enough to be quick is a flake waiting to happen.
   */
  private suspend fun io.ktor.client.HttpClient.awaitMediaItems(
    token: String,
    expected: Int,
  ): List<XoboroMediaItemResponse> {
    repeat(POLL_ATTEMPTS) {
      val items =
        get("$XOBORO_API_PREFIX/media-items?size=50") { bearerAuth(token) }
          .expecting(HttpStatusCode.OK)
          .body<XoboroPageResponse<XoboroMediaItemResponse>>()
          .items
      if (items.size == expected) return items
      Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    error("media item count did not reach $expected within ${POLL_ATTEMPTS * POLL_INTERVAL_MILLIS} ms")
  }

  private suspend fun io.ktor.client.HttpClient.awaitAnalyzedPages(
    token: String,
    mediaItemId: String,
  ): Int {
    repeat(POLL_ATTEMPTS) {
      val pages =
        get("$XOBORO_API_PREFIX/media-items/$mediaItemId/pages") { bearerAuth(token) }
          .expecting(HttpStatusCode.OK)
          .body<List<JsonObject>>()
      if (pages.isNotEmpty()) return pages.size
      Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    error("media item $mediaItemId was never analyzed")
  }

  private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: Any) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    setBody(body)
  }

  private fun HttpResponse.expecting(status: HttpStatusCode): HttpResponse {
    assertEquals(status, this.status, "unexpected status for ${call.request.url.encodedPath}")
    return this
  }

  private companion object {
    const val ADMIN_EMAIL = "acceptance-admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-acceptance-password"
    const val POLL_ATTEMPTS = 200
    const val POLL_INTERVAL_MILLIS = 50L
  }
}
