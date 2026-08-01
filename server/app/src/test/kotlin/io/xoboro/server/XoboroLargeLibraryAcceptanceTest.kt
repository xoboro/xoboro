package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.runTestApplication
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XoboroLibraryAdministrationRequest
import io.xoboro.server.api.XoboroLibraryResponse
import io.xoboro.server.api.XoboroLibrarySourceRequest
import io.xoboro.server.api.XoboroMediaItemResponse
import io.xoboro.server.api.XoboroPageResponse
import io.xoboro.server.api.XoboroSeriesResponse
import io.xoboro.server.api.XoboroTaskCountsResponse
import io.xoboro.server.perf.SyntheticLibraryGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * The `Local-library end-to-end acceptance` gate at a size where a scan is a real workload:
 * [SERIES_COUNT] series holding [BOOKS_PER_SERIES] comics each, plus one deliberately awkward
 * series, generated on disk and driven through the native API only.
 *
 * `XoboroLocalLibraryAcceptanceTest` is the same walk over three files, and it is the right shape for
 * the contract it covers - previous/next, progress, restart, the trash. This exists because three
 * files cannot fail four particular ways:
 *
 * 1. **Counts.** A scan that loses or duplicates a fraction of a tree still looks perfect on a tree
 *    with no fractions. Here the exact set of catalogue titles is compared against the exact set of
 *    file names the generator wrote.
 * 2. **Reading order.** Every other catalogue fixture in this suite numbers its files with zero
 *    padding, where lexicographic and natural ordering are the same order, so nothing currently tells
 *    the two apart. [UNPADDED_VOLUME_NUMBERS] crosses two digit boundaries so they disagree.
 * 3. **Paging.** Duplicated and skipped rows across page boundaries need enough boundaries to land on,
 *    and enough rows sharing a sort key that the order within the key has to be pinned by something.
 *    The default media-item order is the *series* title, so at eight books per series every page here
 *    lies inside one tie group.
 * 4. **Incremental cost.** Whether re-scanning an unchanged tree does per-item work is invisible when
 *    there are three items and the whole scan is a millisecond either way.
 *
 * Where this makes a claim about cost it asserts an invariant, never a duration: "no catalogue row was
 * rewritten", "no file content was read". `docs/performance.md` records three runs of one metric on
 * one machine spreading threefold, so a millisecond threshold here would be noise with an opinion -
 * and a flaky gate gets disabled, which costs more than it ever caught. Phase durations are printed
 * for the record and nothing branches on them.
 *
 * Tagged `largeLibrary` and therefore excluded from the default `test`/`check` tasks, alongside the
 * `performance` tag: see `server/app/build.gradle.kts`. A separate tag rather than reusing
 * `performance` because that task is a measurement harness which reports numbers and is documented as
 * never running in CI, while this is a pass/fail release gate; folding them together would make one of
 * the two lie about what it is. Run it with `./gradlew :server:app:largeLibraryAcceptance`.
 */
@Tag("largeLibrary")
class XoboroLargeLibraryAcceptanceTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `scans a large tree to READY, orders volumes naturally, pages every item once, and rescans without reading content`() {
    val clock = TimeSource.Monotonic.markNow()
    var previousPhaseEnd = Duration.ZERO
    fun reportPhase(name: String) {
      val elapsed = clock.elapsedNow()
      println("xoboro.large-library.$name.wall_ms=${(elapsed - previousPhaseEnd).inWholeMilliseconds}")
      previousPhaseEnd = elapsed
    }

    val libraryRoot = Files.createDirectories(tempDirectory.resolve("large-library"))
    val bulk =
      SyntheticLibraryGenerator.generate(
        root = libraryRoot,
        seriesCount = SERIES_COUNT,
        booksPerSeries = BOOKS_PER_SERIES,
        // No one-shots: a one-shot becomes a series of its own, so including them would make the
        // expected series count depend on a library setting this test does not otherwise exercise.
        oneShotCount = 0,
      )
    val unpaddedVolumeNames =
      SyntheticLibraryGenerator.generateUnpaddedVolumeSeries(
        root = libraryRoot,
        seriesName = UNPADDED_SERIES_NAME,
        volumeNumbers = UNPADDED_VOLUME_NUMBERS,
      )
    val expectedTitles = (bulk.bookNames + unpaddedVolumeNames).toSet()
    // The census below counts distinct titles, so two files sharing a name would make it under-count
    // and let the count assertion pass against a catalogue that had lost an item. Checked rather than
    // assumed of the generator.
    assertEquals(
      bulk.bookNames.size + unpaddedVolumeNames.size,
      expectedTitles.size,
      "the generated fixture must not contain two books with the same name",
    )
    // And the files really are on disk. Without this a generator that quietly wrote nothing would
    // leave every assertion below comparing an empty catalogue against an empty expectation.
    val archives = libraryRoot.archives()
    assertEquals(
      expectedTitles.size,
      archives.size,
      "the generator must have written one archive per expected title",
    )
    val expectedItemCount = expectedTitles.size
    val expectedSeriesCount = SERIES_COUNT + 1
    val expectedPageCount = (expectedItemCount + PAGE_SIZE - 1) / PAGE_SIZE
    println(
      "xoboro.large-library.fixture: series=$expectedSeriesCount items=$expectedItemCount " +
        "bytes=${archives.sumOf(Files::size)} pages_of_$PAGE_SIZE=$expectedPageCount",
    )
    reportPhase("generate")

    XoboroRuntime.open(serverConfig(tempDirectory.resolve("large-acceptance.sqlite"))).use { runtime ->
      // `runTestApplication` under a scope of this test's own, rather than `testApplication`.
      // `testApplication` runs its body through `runTest` with a hardcoded 60-second whole-test
      // timeout - it passes the value explicitly, so `kotlinx.coroutines.test.default_timeout` does
      // not reach it - and a scan of thousands of archives takes longer than that on this machine
      // alone. It failed with `UncompletedCoroutinesError` before this change. The suspend variant
      // is the same set-up without the cap, and this test's own poll deadlines bound the run.
      runBlocking {
        runTestApplication {
          application { xoboroModule(runtime) }
          val client = createClient { install(ContentNegotiation) { json() } }
          val token = client.claimAdministrator()

          val libraryId =
            client
              .post("$XOBORO_API_PREFIX/libraries") {
                bearerAuth(token)
                jsonBody(
                  XoboroLibraryAdministrationRequest(
                    name = "Synthetic large library",
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

          // An empty task queue is the completion signal, and it is a sound one here for two reasons
          // that have to hold together. The `POST /scan` above enqueues its task before it answers
          // `202`, so the queue is already non-empty when this starts polling - it cannot mistake "not
          // started yet" for "finished". And reconciliation enqueues every analysis inside the scan
          // task's own execution, before that task is marked complete, so there is no window in which
          // the scan is done and the analyses it caused are not yet queued.
          //
          // Deliberately not "wait until the catalogue holds the expected number of items". That wait
          // would have to compare against the same number the assertions below check, and would spin
          // until it matched - so those assertions could then only restate what the wait had already
          // established, and a scan that lost half the tree would be reported as a timeout in the
          // fixture rather than as a wrong count in the catalogue. The first version of this test made
          // that mistake; removing the count from the wait is what lets the count be asserted.
          val drained = client.awaitQueueDrained(token)
          assertEquals(
            0L,
            drained.dead,
            "no task may fail permanently while scanning and analyzing $expectedItemCount items",
          )
          reportPhase("first_scan_and_analysis")

          // ---- Counts and status, over the whole catalogue ----
          val census = client.censusMediaItems(token, PAGE_SIZE)
          reportPhase("census")
          assertEquals(
            expectedItemCount.toLong(),
            census.totalItems,
            "the catalogue must report exactly the items the generator wrote",
          )
          assertEquals(
            expectedItemCount,
            census.items.size,
            "paging the whole catalogue must yield exactly totalItems rows",
          )
          assertEquals(expectedPageCount, census.totalPages, "totalPages must follow from totalItems")
          // Two failure modes, told apart. A duplicate makes the id count exceed the distinct count; a
          // gap leaves a generated title absent. Reporting only the sizes would conflate them.
          //
          // Both were confirmed to fail against an offset that shifts by one on alternate pages, which
          // duplicates and skips rows while leaving the row *count* right. But one thing they do **not**
          // catch, tested: deleting the `tieBreaker` from `JooqCatalogReadRepository.order` - so that the
          // default media-item order is the series title alone, with eight rows sharing every value -
          // left this walk passing. SQLite returns ties in the same sequence for the same query plan, so
          // an unstable order does not in fact destabilise across `LIMIT`/`OFFSET` pages here. The
          // tie-breaker's absence would surface under a different plan, not under this assertion; do not
          // read a green run as evidence that the ordering is fully pinned.
          assertEquals(
            emptyList(),
            census.items
              .groupBy(XoboroMediaItemResponse::id)
              .filterValues { it.size > 1 }
              .keys
              .toList(),
            "no media item may appear on more than one page",
          )
          assertEquals(
            emptySet(),
            expectedTitles - census.items.mapTo(mutableSetOf()) { it.title },
            "every generated book must be reachable by paging the catalogue",
          )
          assertEquals(
            emptyList(),
            census.items
              .filterNot { it.media.status == "READY" }
              .map { "${it.title}=${it.media.status}" },
            "every media item must reach READY",
          )
          assertEquals(
            emptyList(),
            census.items.filter { it.media.pageCount < 1 }.map(XoboroMediaItemResponse::title),
            "a READY item must report the pages its archive holds",
          )
          assertEquals(
            expectedSeriesCount.toLong(),
            client.totalSeries(token),
            "one series per generated directory",
          )

          // ---- Reading order at a scale where lexicographic and natural ordering disagree ----
          val unpaddedSeriesId =
            census.items
              .filter { it.seriesTitle == UNPADDED_SERIES_NAME }
              .map(XoboroMediaItemResponse::seriesId)
              .distinct()
              .single()
          val ordered = client.mediaItemsInSeries(token, unpaddedSeriesId)
          // The fixture discriminates only if the two orders really differ. Without this the assertion
          // below would also be satisfied by a scanner that sorted file names as plain strings, which is
          // the defect it exists to catch.
          assertNotEquals(
            unpaddedVolumeNames,
            unpaddedVolumeNames.sorted(),
            "the unpadded fixture must order differently by nature than by string, or it proves nothing",
          )
          assertEquals(
            unpaddedVolumeNames,
            ordered.map(XoboroMediaItemResponse::title),
            "in-series reading order must follow volume numbers, not their string forms",
          )
          // The numbering behind that order, not only the sequence it produced: a listing sorted
          // correctly over wrong numbers would still satisfy the assertion above on this fixture.
          assertEquals(
            (1..unpaddedVolumeNames.size).map(Int::toString),
            ordered.map(XoboroMediaItemResponse::number),
          )
          assertEquals(
            (1..unpaddedVolumeNames.size).map(Int::toFloat),
            ordered.map(XoboroMediaItemResponse::sortNumber),
          )

          // ---- Delivery, from an archive the scan found rather than a row a fixture seeded ----
          val deliveredPage =
            client
              .get("$XOBORO_API_PREFIX/media-items/${ordered.last().id}/pages/1") { bearerAuth(token) }
              .expecting(HttpStatusCode.OK)
              .body<ByteArray>()
          assertTrue(deliveredPage.isNotEmpty(), "page 1 of the last volume should deliver bytes")
          reportPhase("order_and_delivery")

          // ---- An unchanged rescan must not rewrite the catalogue ----
          // `updatedAtMillis` per item is the observable form of that claim: reconciliation writes a book
          // row only when its staged candidate differs from it, so an unchanged tree must leave every one
          // of these thousands of timestamps alone. This is the property behind the unchanged-scan
          // acceleration, asserted as an invariant rather than as a duration.
          val timestampsBefore = census.items.associate { it.id to it.updatedAtMillis }
          client.rescanAndSettle(token, libraryId)
          reportPhase("unchanged_rescan")
          val afterUnchanged = client.censusMediaItems(token, PAGE_SIZE)
          assertEquals(
            timestampsBefore,
            afterUnchanged.items.associate { it.id to it.updatedAtMillis },
            "an unchanged rescan must not rewrite a single catalogue row",
          )

          // ---- ...and must not read file content ----
          // Every archive's bytes are replaced with bytes that are not an archive, while its size and
          // modification time are preserved. Reconciliation decides "unchanged" from a file's name, size
          // and modification time, so this tree is still unchanged by every measure it takes - and it
          // must therefore neither read the content itself nor queue an analysis that would. Had it done
          // either, the analyzer would have failed on all of these corrupted archives and they would have
          // left READY. The wait inside `rescanAndSettle` returns only once every queued task has run, so
          // "still READY afterwards" is a statement about work that finished, not work still to come.
          //
          // What this does not prove: that no other part of the scan opened a file for any other reason.
          // It proves the catalogue was not re-derived from content, which is the expensive part.
          libraryRoot.corruptEveryArchiveInPlace()
          client.rescanAndSettle(token, libraryId)
          reportPhase("corrupted_content_rescan")
          val afterCorruption = client.censusMediaItems(token, PAGE_SIZE)
          assertEquals(
            emptyList(),
            afterCorruption.items
              .filterNot { it.media.status == "READY" }
              .map { "${it.title}=${it.media.status}" },
            "an unchanged rescan must not re-read content: nothing may be re-analyzed",
          )
          assertEquals(
            census.items.associate { it.id to it.media.pageCount },
            afterCorruption.items.associate { it.id to it.media.pageCount },
            "page counts must survive a rescan that had no reason to look inside the files",
          )
        }
      }
    }
  }

  /** Every archive on disk, so the expected-title set is never checked against only itself. */
  private fun Path.archives(): List<Path> {
    val archives = mutableListOf<Path>()
    Files.walk(this).use { paths ->
      paths.forEach { path ->
        if (path.fileName.toString().endsWith(ARCHIVE_EXTENSION)) archives.add(path)
      }
    }
    return archives
  }

  /**
   * Replaces every archive's bytes with bytes that are not an archive, preserving its size and
   * modification time.
   *
   * Both are preserved deliberately: they are two of the fields reconciliation compares, so changing
   * either would make the tree genuinely changed and a rescan would be right to re-analyze it. The
   * point is a tree unchanged by every measure the scanner takes, whose content would fail loudly if
   * anything looked at it.
   */
  private fun Path.corruptEveryArchiveInPlace() {
    archives().forEach { archive ->
      val size = Files.size(archive).toInt()
      val modifiedAt = Files.getLastModifiedTime(archive)
      Files.write(archive, ByteArray(size) { NON_ARCHIVE_BYTE })
      Files.setLastModifiedTime(archive, modifiedAt)
    }
  }

  /**
   * Triggers a scan and returns once every task it queued has run.
   *
   * Sound for the same two reasons the first scan's wait is (see the call site): the request enqueues
   * its task before answering `202`, so an empty queue cannot mean "not started"; and analyses are
   * enqueued inside the scan task, before it completes, so an empty queue cannot mean "scan done,
   * analyses pending".
   */
  private suspend fun HttpClient.rescanAndSettle(
    token: String,
    libraryId: String,
  ) {
    post("$XOBORO_API_PREFIX/libraries/$libraryId/scan") { bearerAuth(token) }
      .expecting(HttpStatusCode.Accepted)
    awaitQueueDrained(token)
  }

  private suspend fun HttpClient.totalSeries(token: String): Long =
    get("$XOBORO_API_PREFIX/series?page=0&size=1") { bearerAuth(token) }
      .expecting(HttpStatusCode.OK)
      .body<XoboroPageResponse<XoboroSeriesResponse>>()
      .totalItems

  private suspend fun HttpClient.mediaItemsInSeries(
    token: String,
    seriesId: String,
  ): List<XoboroMediaItemResponse> =
    get("$XOBORO_API_PREFIX/series/$seriesId/media-items?page=0&size=$PAGE_SIZE") { bearerAuth(token) }
      .expecting(HttpStatusCode.OK)
      .body<XoboroPageResponse<XoboroMediaItemResponse>>()
      .items

  /**
   * Walks every page of the media-item listing and returns what it saw.
   *
   * The traversal carries the page-envelope assertions itself, because a boundary defect is a property
   * of the walk rather than of any one response: every page before the last is full, `page` echoes what
   * was asked for, `totalItems` does not move underneath the walk, and `hasNext` goes false exactly
   * once, on the last page. A `size` deliberately not a divisor of the catalogue leaves a partial final
   * page, which is where an off-by-one in the offset surfaces.
   */
  private suspend fun HttpClient.censusMediaItems(
    token: String,
    pageSize: Int,
  ): Census {
    val items = mutableListOf<XoboroMediaItemResponse>()
    var page = 0
    var totalItems = -1L
    var totalPages = -1
    while (true) {
      val envelope =
        get("$XOBORO_API_PREFIX/media-items?page=$page&size=$pageSize") { bearerAuth(token) }
          .expecting(HttpStatusCode.OK)
          .body<XoboroPageResponse<XoboroMediaItemResponse>>()
      assertEquals(page, envelope.page, "the envelope must echo the requested page")
      assertEquals(pageSize, envelope.size, "the envelope must echo the requested size")
      assertEquals(page > 0, envelope.hasPrevious)
      if (page == 0) {
        totalItems = envelope.totalItems
        totalPages = envelope.totalPages
      } else {
        // These two are the weakest assertions in the file, and they are here for a different reason
        // from the rest: they guard against the catalogue changing underneath a walk, which is why the
        // walk only runs after the task queue has drained. Neither has been seen to fail - a mutation
        // that moved the total mid-walk would have to be a concurrency change rather than a line edit -
        // so read them as documentation of the precondition, not as verified checks.
        assertEquals(totalItems, envelope.totalItems, "totalItems must not move while paging")
        assertEquals(totalPages, envelope.totalPages, "totalPages must not move while paging")
      }
      items.addAll(envelope.items)
      page += 1
      if (!envelope.hasNext) break
      assertEquals(
        pageSize,
        envelope.items.size,
        "page ${page - 1} reported a next page while returning fewer rows than asked for",
      )
      check(page <= totalPages) { "hasNext stayed true past the reported page count of $totalPages" }
    }
    assertEquals(totalPages, page, "hasNext must go false exactly on the last page")
    return Census(items = items, totalItems = totalItems, totalPages = totalPages)
  }

  private data class Census(
    val items: List<XoboroMediaItemResponse>,
    val totalItems: Long,
    val totalPages: Int,
  )

  /**
   * Polls until no task is pending or running, so the catalogue is settled and nothing is writing.
   *
   * A `5xx` here is waited through rather than failed on. Background scan and analysis writes can
   * transiently collide with a read - `docs/performance.md` records the same race in the performance
   * harness - and this loop's job is to wait for a state, not to assert one. Every assertion in the
   * test proper runs after the queue has drained and demands a `200`.
   */
  private suspend fun HttpClient.awaitQueueDrained(token: String): XoboroTaskCountsResponse {
    var counts = XoboroTaskCountsResponse(pending = -1, running = -1, dead = -1)
    repeat(SCAN_POLL_ATTEMPTS) {
      val response = get("$XOBORO_API_PREFIX/tasks") { bearerAuth(token) }
      if (response.status == HttpStatusCode.OK) {
        counts = response.body()
        if (counts.pending + counts.running == 0L) return counts
      }
      delay(POLL_INTERVAL_MILLIS.milliseconds)
    }
    error(
      "the task queue still held ${counts.pending} pending and ${counts.running} running tasks after " +
        "${SCAN_POLL_ATTEMPTS * POLL_INTERVAL_MILLIS} ms",
    )
  }

  private suspend fun HttpClient.claimAdministrator(): String =
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

  private fun serverConfig(databasePath: Path): ServerConfig =
    ServerConfig(
      port = 25_613,
      databasePath = databasePath,
      // The production default on a typical host (`availableProcessors` clamped to 1..4). A single
      // worker would understate how long a scan of this size takes and would never exercise several
      // analyses writing at once, which is a property of a large library rather than of a small one.
      workerCount = 4,
      taskPollMillis = 25,
      taskFailurePollMillis = 25,
      taskLeaseMillis = 60_000,
      shutdownTimeoutMillis = 10_000,
    )

  private fun HttpRequestBuilder.jsonBody(body: Any) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    setBody(body)
  }

  private fun HttpResponse.expecting(status: HttpStatusCode): HttpResponse {
    assertEquals(status, this.status, "unexpected status for ${call.request.url.encodedPath}")
    return this
  }

  private companion object {
    /**
     * Hundreds of series and thousands of items: enough that a whole-catalogue walk crosses dozens of
     * page boundaries, that every page of the default ordering sits inside a tie group, and that a
     * rescan doing per-item work would be doing thousands of writes rather than three.
     */
    const val SERIES_COUNT = 600
    const val BOOKS_PER_SERIES = 8
    const val UNPADDED_SERIES_NAME = "Unpadded Volumes"

    /**
     * Volume numbers crossing the 9/10 **and** 99/100 boundaries.
     *
     * Two boundaries rather than one: a comparator that pads names to a fixed width is wrong in the
     * same way as one that does not sort numerically at all, and a set crossing only 9/10 cannot tell
     * the two apart. Ten volumes because the numbering it produces, 1..10, has an answer in the middle
     * - an ordering assertion over two rows names the same row under several different orderings.
     */
    val UNPADDED_VOLUME_NUMBERS = listOf(1, 2, 9, 10, 11, 20, 99, 100, 101, 110)

    /**
     * Page size for the whole-catalogue walk. Not a divisor of the item count and not the maximum, so
     * the walk ends on a partial page and has a few dozen boundaries to get wrong.
     */
    const val PAGE_SIZE = 97

    const val ADMIN_EMAIL = "large-acceptance-admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-large-acceptance-password"
    const val POLL_INTERVAL_MILLIS = 50L
    const val ARCHIVE_EXTENSION = ".cbz"

    /** Long enough for thousands of analyses on a slow machine; a deadline, not an expected duration. */
    const val SCAN_POLL_ATTEMPTS = 12_000

    /** Not a zip local-file-header signature, so an archive filled with it cannot be opened. */
    const val NON_ARCHIVE_BYTE: Byte = 0x7A
  }
}
