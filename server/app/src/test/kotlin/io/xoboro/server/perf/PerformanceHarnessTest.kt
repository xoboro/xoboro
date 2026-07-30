package io.xoboro.server.perf

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
import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.ServerConfig
import io.xoboro.server.XoboroRuntime
import io.xoboro.server.api.SessionResponse
import io.xoboro.server.api.SessionTransport
import io.xoboro.server.api.SetupRequest
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XoboroMediaItemResponse
import io.xoboro.server.api.XoboroMediaProgressRequest
import io.xoboro.server.api.XoboroPageResponse
import io.xoboro.server.api.XoboroSeriesResponse
import io.xoboro.server.media.AnalyzeBook
import io.xoboro.server.media.ZipMediaAnalyzer
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReconciliationStore
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.sources.local.LocalSourceInventory
import io.xoboro.server.sources.local.LocalSourceMediaAccess
import io.xoboro.server.xoboroModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * Opt-in performance harness. It generates a synthetic large library on disk, drives it through
 * the production scanner and analyzer, and measures native API latency against the resulting
 * database — producing the first real numbers for xoboro's "large-library acceptance" and
 * "profiling" roadmap items.
 *
 * This is a measurement harness, not a benchmark suite with pass/fail thresholds: it reports
 * numbers and asserts only basic functional sanity (the right item counts came back, the server
 * answered). It intentionally does NOT optimize anything it finds slow.
 *
 * Tagged `performance` so the default `test`/`check` tasks (see `server/app/build.gradle.kts`)
 * exclude it; run it explicitly via `:server:app:performanceHarness`.
 *
 * What this does NOT measure: concurrent multi-user load (everything here is single-threaded and
 * sequential), a cold OS page cache (the JVM and filesystem cache are warm from generation),
 * network transport (native HTTP calls run in-process through Ktor's test host), and artwork
 * generation (no thumbnails are produced here).
 *
 * A single run of `cold_scan`/`cold_analyze`/`cold_full_scan` cannot establish a regression or an
 * improvement: repeated runs at the same size on the same machine varied by roughly ±25% (see
 * `README.md`'s Performance harness section for the measured numbers and for how to get a number
 * that can actually support a before/after comparison — a shell loop of separate Gradle
 * invocations, not a change to this file). Those three metrics are deliberately NOT repeated
 * in-process the way `unchanged_rescan`/`api.*` are: "cold" means the first
 * request in a JVM's lifetime, before JIT compilation and class loading have caught up, so looping
 * the scan+analyze sequence inside one JVM would make later iterations systematically faster —
 * not noisier, but biased — which would silently stop measuring a cold start. `unchanged_rescan`
 * has no such problem: a real deployment reruns it warm, every `scanInterval`, so repeating it
 * in-process against the already-open database measures the real, repeatable thing, and is
 * reported as p50 (median)/min/max like the API latency metrics below.
 *
 * `api.first_series_read_after_scan` is a deliberate exception to the steady-state latency
 * metrics: it is a single observation of the first `/series` call after a scan, which absorbs a
 * one-time full-catalog aggregation-cache rebuild (see the retry-policy note below). It is a
 * cold-cache, one-time cost, not steady-state `/series` latency — do not compare it against
 * `api.series_listing`, and do not average it across runs the way `api.series_listing.p50` is
 * meant to be read. It is reported as four separate values (see [PerformanceReport.recordColdRead]),
 * not one: `.successful_attempt` (the isolated duration of the call that actually succeeded),
 * `.harness_wall` (the full retry loop including every failed attempt and backoff sleep, labeled as
 * harness time because it is NOT a server cost), `.attempts`/`.failed_attempts`, and
 * `.last_failure_type`. A single total that folds backoff sleep into a "cold read" number would
 * measure the harness's retry loop, not the server — the same mistake as a polluted max, just in a
 * new shape.
 *
 * Retry policy for native API calls (see [attemptWithRetries]): up to 5 attempts total (1 initial
 * + 4 retries) on a 5xx response or a thrown exception, with a full-jittered backoff (uniformly
 * random between 1 ms and the configured backoff) between attempts — not a fixed delay, because a
 * fixed backoff can stay phase-locked with the competing background writer's own steady poll
 * cadence (observed in practice: a fixed 300 ms backoff against the largest library size here
 * failed all 10 attempts in a row for the first read; jitter let it succeed on attempt 9 of 10).
 * This retry policy exists to smooth over a rarely observed transient
 * `SQLITE_BUSY`/`SQLITE_BUSY_SNAPSHOT` from background scheduler/worker threads racing a
 * request-triggered cache refresh — a pre-existing concurrency detail of the read path, reported
 * here rather than fixed. If all attempts for a call fail, the harness fails outright rather than
 * recording a sample for it. Within [measureRepeated], any measured request that needed more than
 * one attempt is excluded from that endpoint's p50/max — a retried call's latency includes backoff
 * sleep and would misrepresent the read path — and is instead counted separately as
 * `<key>.retried_samples` so it stays visible rather than being silently dropped.
 */
@Tag("performance")
class PerformanceHarnessTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `measures scan, rescan, API latency, and database size for a synthetic library`() {
    val seriesCount = intProperty("xoboro.perf.seriesCount", DEFAULT_SERIES_COUNT)
    val booksPerSeries = intProperty("xoboro.perf.booksPerSeries", DEFAULT_BOOKS_PER_SERIES)
    val oneShotCount = intProperty("xoboro.perf.oneShotCount", DEFAULT_ONE_SHOT_COUNT)

    val generated =
      SyntheticLibraryGenerator.generate(
        root = tempDirectory.resolve("library"),
        seriesCount = seriesCount,
        booksPerSeries = booksPerSeries,
        oneShotCount = oneShotCount,
      )
    val totalBookCount = generated.totalBookCount.toLong()

    val databasePath = tempDirectory.resolve("perf.sqlite")
    val library =
      Library(
        id = LibraryId("perf-library"),
        name = "Synthetic performance library",
        root = SourceLocation("local", generated.root.toUri().toString()),
        settings = LibrarySettings(oneshotsDirectory = SyntheticLibraryGenerator.ONE_SHOTS_DIRECTORY_NAME),
        createdAtMillis = 1L,
      )

    val report = PerformanceReport()
    var scannedBookCount = 0L

    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(library)
      val scanner =
        CatalogScanner(
          inventories = listOf(LocalSourceInventory()),
          reconciliationStore = JooqCatalogReconciliationStore(database),
          currentTimeMillis = System::currentTimeMillis,
        )

      val coldScanElapsed = measureTime { scanner.scan(library, deep = false) }

      val books = JooqBookRepository(database)
      val scannedBooks = books.findAllByLibraryId(library.id)
      scannedBookCount = scannedBooks.size.toLong()
      assertEquals(totalBookCount, scannedBookCount, "scanner should have found every generated book")

      val analyzeBook =
        AnalyzeBook(
          books = books,
          libraries = JooqLibraryRepository(database),
          accesses = listOf(LocalSourceMediaAccess()),
          media = JooqBookMediaRepository(database),
          zipAnalyzer = ZipMediaAnalyzer(),
          currentTimeMillis = System::currentTimeMillis,
        )
      val analyzeElapsed = measureTime { scannedBooks.forEach { analyzeBook.execute(it.id) } }

      report.recordMillis("cold_scan.wall", scannedBookCount, coldScanElapsed.inWholeMilliseconds.toDouble())
      report.recordMillis("cold_analyze.wall", scannedBookCount, analyzeElapsed.inWholeMilliseconds.toDouble())
      report.recordMillis(
        "cold_full_scan.wall",
        scannedBookCount,
        (coldScanElapsed + analyzeElapsed).inWholeMilliseconds.toDouble(),
      )

      // Unlike cold_scan/cold_analyze/cold_full_scan above, an unchanged rescan is NOT a one-time,
      // JVM-cold event: a real deployment reruns this warm, every scanInterval, for as long as the
      // library exists. So repeating it here — against the same already-open database and already
      // JIT-warmed scanner — measures the real thing, unlike looping a "cold" measurement would
      // (see the class doc's note on why the cold metrics are not repeated the same way). Report
      // p50 (median)/min/max, the same shape used for the API latency metrics below.
      val rescanElapsedNanos =
        (1..RESCAN_REPEAT_COUNT).map {
          measureTime { scanner.scan(library, deep = false) }.inWholeNanoseconds
        }
      report.recordRepeatedMillis("unchanged_rescan.wall", scannedBookCount, LatencyStats.of(rescanElapsedNanos))
    }

    val databaseSizeBytes = Files.size(databasePath)
    report.recordBytes("database.file_size", scannedBookCount, databaseSizeBytes)
    report.recordBytes(
      "database.file_size_per_item",
      scannedBookCount,
      if (scannedBookCount > 0) databaseSizeBytes / scannedBookCount else 0L,
    )

    measureApiLatency(databasePath, scannedBookCount, report)

    println("--- xoboro performance harness: machine-readable ---")
    report.toMachineReadableLines().forEach(::println)
    println("--- xoboro performance harness: markdown ---")
    println(report.toMarkdownTable())

    assertTrue(databaseSizeBytes > 0, "database file should be non-empty after a scan")
  }

  /**
   * Waits until the durable task queue has no pending or running work, and records what happened.
   *
   * Polls rather than subscribing: the queue exposes counts, there is no completion signal to wait on,
   * and a poll interval two orders of magnitude below the deadline cannot meaningfully distort the
   * measurement it protects.
   *
   * `DEAD` tasks are deliberately not waited for. A dead task will never run again, so waiting for one
   * would hang until the deadline on any run where something failed permanently - turning an unrelated
   * failure into a timeout in every latency metric.
   */
  private fun drainTaskQueue(
    runtime: XoboroRuntime,
    scannedBookCount: Long,
    report: PerformanceReport,
  ) {
    var counts = runtime.durableTaskQueue.counts()
    // Proportional to the catalog, with a floor. The first version used a flat two minutes and reported
    // 10,296 tasks still queued at 15,050 items - so a flat deadline just moved the defect from
    // "measured while busy" to "reported not drained on every large run", which is the same blindness.
    val timeout =
      maxOf(
        QUEUE_DRAIN_MINIMUM,
        (scannedBookCount * QUEUE_DRAIN_MILLIS_PER_ITEM).milliseconds,
      )
    val elapsed =
      measureTime {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (counts.pending + counts.running > 0 && System.nanoTime() < deadline) {
          Thread.sleep(QUEUE_DRAIN_POLL.inWholeMilliseconds)
          counts = runtime.durableTaskQueue.counts()
        }
      }
    val remaining = counts.pending + counts.running
    report.recordQueueDrain(
      itemCount = scannedBookCount,
      millis = elapsed.inWholeMilliseconds.toDouble(),
      drained = remaining == 0L,
      remainingTasks = remaining,
    )
    if (remaining > 0) {
      // The breakdown, not just the count. "10,296 tasks queued" says the measurement is unusable;
      // "10,296 of them are GENERATE_BOOK_ARTWORK" says why, and whether that is the harness's problem
      // or the server's.
      val byType =
        (runtime.durableTaskQueue as? JooqDurableTaskQueue)
          ?.countsByType()
          ?.entries
          ?.sortedByDescending { it.value }
          ?.joinToString(", ") { "${it.key}=${it.value}" }
          ?: "unavailable"
      println(
        "--- xoboro performance harness: WARNING queue did not drain within $timeout, " +
          "$remaining task(s) still pending or running ($byType); api.* latency below is not " +
          "comparable with a drained run ---",
      )
    }
  }

  private fun measureApiLatency(
    databasePath: Path,
    scannedBookCount: Long,
    report: PerformanceReport,
  ) {
    val runtime =
      XoboroRuntime.open(
        ServerConfig(
          port = 25_601,
          databasePath = databasePath,
          workerCount = 1,
          // Production defaults, not an aggressive test-only interval: the idle worker issues a
          // write (`UPDATE task SET ...`) on every poll even against an empty queue, so a fast
          // poll here would collide with the read-triggered aggregation-cache refresh far more
          // often than a real deployment ever would (see the retry-policy doc above).
          taskPollMillis = ServerConfig.DEFAULT_TASK_POLL_MILLIS,
          taskFailurePollMillis = ServerConfig.DEFAULT_TASK_FAILURE_POLL_MILLIS,
          taskLeaseMillis = 1_000,
          shutdownTimeoutMillis = 2_000,
        ),
      )
    try {
      // Drained before anything is measured. The harness previously started measuring immediately, so
      // at 15,050 items the runtime's own start-up sweeps were plausibly still running and competing
      // for the SQLite write lock during the measurement - which is the recorded explanation for
      // api.series_listing being bimodal there while its `min` stayed at the 3,050-item p50. A latency
      // number taken while background work is running is not a latency number.
      //
      // The outcome is reported rather than asserted. A timeout must not fail the harness - it is a
      // measurement tool, not a gate - but it must be visible, because latency metrics from an
      // undrained run cannot be compared with metrics from a drained one.
      drainTaskQueue(runtime, scannedBookCount, report)
      testApplication {
        application { xoboroModule(runtime) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val setup =
          retryingTransientFailures {
            client.post("$XOBORO_API_PREFIX/setup") {
              header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
              setBody(
                SetupRequest(
                  email = "perf-admin@example.invalid",
                  password = "synthetic-performance-password",
                  transport = SessionTransport.BEARER,
                ),
              )
            }
          }
        assertEquals(HttpStatusCode.Created, setup.status)
        val token = requireNotNull(setup.body<SessionResponse>().accessToken)

        // The first-ever call to /series triggers a one-time full-catalog aggregation-cache
        // rebuild (JooqBookMetadataAggregationRepository.refreshAllDirty) covering every series
        // marked dirty since the scan. That is a real, user-visible cost — "how long does the
        // first browse take after scanning a library" — not an error to retry past and discard.
        // So this call keeps its generously larger retry budget (it can otherwise collide with
        // the idle worker's periodic queue poll and exhaust a steady-state budget), but see
        // recordColdRead below: the successful attempt's own duration, the retry/backoff time, and
        // the failure count/type are reported as three separate numbers, not folded into one. A
        // total that includes jittered backoff sleep across several failed attempts is mostly
        // measuring the harness's own retry loop, not the server — reporting it alone would repeat
        // exactly the "polluted max" mistake this harness was built to avoid. This is a single,
        // one-time observation: it is not averaged, and nothing later in this run should need the
        // same budget, since no other request here dirties a series after the cache is warm.
        val firstSeriesReadOutcome =
          attemptWithRetries(
            attempts = CACHE_WARMUP_RETRY_ATTEMPTS,
            backoffMillis = CACHE_WARMUP_RETRY_BACKOFF_MILLIS,
          ) {
            client.get("$XOBORO_API_PREFIX/series?page=0&size=1&sort=mediaItemCount,desc") {
              bearerAuth(token)
            }
          }
        report.recordColdRead(
          key = "api.first_series_read_after_scan",
          itemCount = scannedBookCount,
          successfulAttemptMillis = firstSeriesReadOutcome.successfulAttemptElapsedNanos / 1_000_000.0,
          harnessWallMillis = firstSeriesReadOutcome.harnessWallElapsedNanos / 1_000_000.0,
          attempts = firstSeriesReadOutcome.attempts,
          lastFailureType = firstSeriesReadOutcome.lastFailureType,
        )
        val topSeries =
          firstSeriesReadOutcome.response
            .body<XoboroPageResponse<XoboroSeriesResponse>>()
            .items
            .single()

        val seriesListing =
          measureRepeated {
            client.get("$XOBORO_API_PREFIX/series?page=0&size=20") { bearerAuth(token) }
          }
        report.recordLatency(
          "api.series_listing",
          scannedBookCount,
          seriesListing.stats,
          seriesListing.retriedSamples,
        )

        // Offset pagination's cost is supposed to grow with the offset, because SQLite still has
        // to walk and discard the skipped rows. Whether that is worth a second pagination mode is
        // an empirical question, so measure the same endpoint at the deepest page that exists and
        // report both. A cursor is only justified if this diverges from the first page.
        val seriesPageCount =
          retryingTransientFailures {
            client.get("$XOBORO_API_PREFIX/series?page=0&size=$DEEP_PAGE_SIZE") { bearerAuth(token) }
          }.body<XoboroPageResponse<XoboroSeriesResponse>>()
            .totalPages
        val lastSeriesListing =
          measureRepeated {
            client.get(
              "$XOBORO_API_PREFIX/series?page=${(seriesPageCount - 1).coerceAtLeast(0)}" +
                "&size=$DEEP_PAGE_SIZE",
            ) { bearerAuth(token) }
          }
        report.recordLatency(
          "api.series_listing_last_page",
          scannedBookCount,
          lastSeriesListing.stats,
          lastSeriesListing.retriedSamples,
        )
        report.recordCount("api.series_listing_last_page.page_index", seriesPageCount - 1)

        // Discriminates what the per-page cost actually is. If it is dominated by the total-count
        // query - which every page pays regardless of depth, over the whole filtered set - then
        // asking for 10x as many rows costs about the same. If it is dominated by fetching and
        // serialising rows, it scales with the page size.
        val wideSeriesListing =
          measureRepeated {
            client.get("$XOBORO_API_PREFIX/series?page=0&size=${DEEP_PAGE_SIZE * 10}") {
              bearerAuth(token)
            }
          }
        report.recordLatency(
          "api.series_listing_wide_page",
          scannedBookCount,
          wideSeriesListing.stats,
          wideSeriesListing.retriedSamples,
        )

        // Third leg of the same question. The wide-page probe rules out row work; this rules in or
        // out "proportional to the set being scanned and counted" by narrowing that set ~30x while
        // returning the same page size. Fast here means the cost tracks the filtered set, not the
        // rows returned, not the offset, and not a fixed per-request overhead.
        val narrowSeriesListing =
          measureRepeated {
            client.get("$XOBORO_API_PREFIX/series?oneShot=true&page=0&size=$DEEP_PAGE_SIZE") {
              bearerAuth(token)
            }
          }
        report.recordLatency(
          "api.series_listing_narrow_filter",
          scannedBookCount,
          narrowSeriesListing.stats,
          narrowSeriesListing.retriedSamples,
        )

        val mediaItemPageCount =
          retryingTransientFailures {
            client.get("$XOBORO_API_PREFIX/media-items?page=0&size=$DEEP_PAGE_SIZE") {
              bearerAuth(token)
            }
          }.body<XoboroPageResponse<XoboroMediaItemResponse>>()
            .totalPages
        val firstMediaItemListing =
          measureRepeated {
            client.get("$XOBORO_API_PREFIX/media-items?page=0&size=$DEEP_PAGE_SIZE") {
              bearerAuth(token)
            }
          }
        report.recordLatency(
          "api.media_item_listing",
          scannedBookCount,
          firstMediaItemListing.stats,
          firstMediaItemListing.retriedSamples,
        )
        val lastMediaItemListing =
          measureRepeated {
            client.get(
              "$XOBORO_API_PREFIX/media-items?page=${(mediaItemPageCount - 1).coerceAtLeast(0)}" +
                "&size=$DEEP_PAGE_SIZE",
            ) { bearerAuth(token) }
          }
        report.recordLatency(
          "api.media_item_listing_last_page",
          scannedBookCount,
          lastMediaItemListing.stats,
          lastMediaItemListing.retriedSamples,
        )
        report.recordCount("api.media_item_listing_last_page.page_index", mediaItemPageCount - 1)

        val booksInSeries =
          measureRepeated {
            client.get("$XOBORO_API_PREFIX/series/${topSeries.id}/media-items?page=0&size=20") {
              bearerAuth(token)
            }
          }
        report.recordLatency(
          "api.books_in_series",
          topSeries.mediaItemCount.toLong(),
          booksInSeries.stats,
          booksInSeries.retriedSamples,
        )

        val sampleBooks =
          retryingTransientFailures {
            client.get("$XOBORO_API_PREFIX/media-items?page=0&size=$KEEP_READING_SAMPLE_SIZE") {
              bearerAuth(token)
            }
          }.body<XoboroPageResponse<XoboroMediaItemResponse>>()
            .items
        sampleBooks.forEachIndexed { index, book ->
          if (index % KEEP_READING_SEED_STRIDE == 0) {
            retryingTransientFailures {
              client.put("$XOBORO_API_PREFIX/media-items/${book.id}/progress") {
                bearerAuth(token)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(
                  XoboroMediaProgressRequest(
                    page = 1,
                    locator = JsonObject(emptyMap()),
                    deviceId = "perf-harness",
                    deviceName = "perf-harness",
                    modifiedAtMillis = System.currentTimeMillis(),
                  ),
                )
              }
            }
          }
        }

        val keepReadingFeed =
          measureRepeated {
            client.get("$XOBORO_API_PREFIX/media-items?keepReading=true&page=0&size=20") {
              bearerAuth(token)
            }
          }
        report.recordLatency(
          "api.keep_reading_feed",
          scannedBookCount,
          keepReadingFeed.stats,
          keepReadingFeed.retriedSamples,
        )
      }
    } finally {
      runtime.close()
    }
  }

  /**
   * Runs [request] [WARMUP_REQUESTS] then [MEASURED_REQUESTS] times, timing each measured call
   * end-to-end (including any retries it needed). A call that succeeded on its first attempt
   * contributes its latency to [MeasuredCall.stats]; a call that needed a retry is excluded from
   * the stats — its latency includes retry backoff and is not representative of the read path —
   * and is counted in [MeasuredCall.retriedSamples] instead. See the class doc for the full retry
   * policy. If every measured call needed a retry, there is no clean latency data and the test
   * fails rather than reporting an empty/misleading stat.
   */
  private suspend fun measureRepeated(request: suspend () -> HttpResponse): MeasuredCall {
    repeat(WARMUP_REQUESTS) { attemptWithRetries(request = request) }
    val cleanSampleNanos = mutableListOf<Long>()
    var retriedSamples = 0
    repeat(MEASURED_REQUESTS) {
      val mark = TimeSource.Monotonic.markNow()
      val outcome = attemptWithRetries(request = request)
      val elapsedNanos = mark.elapsedNow().inWholeNanoseconds
      if (outcome.attempts == 1) {
        cleanSampleNanos += elapsedNanos
      } else {
        retriedSamples += 1
      }
    }
    check(cleanSampleNanos.isNotEmpty()) {
      "All $MEASURED_REQUESTS measured requests needed a retry; no clean latency sample was recorded"
    }
    return MeasuredCall(LatencyStats.of(cleanSampleNanos), retriedSamples)
  }

  /**
   * Retries a request on a 5xx response or a thrown exception (see the class doc for the default
   * policy). Exists to smooth over a rarely observed transient `SQLITE_BUSY`/`SQLITE_BUSY_SNAPSHOT`;
   * it is not a production fix, only a harness-level tolerance for a known-rare race between a
   * request's read-triggered cache refresh and background scheduler/worker writes. [attempts] and
   * [backoffMillis] default to the steady-state policy; callers absorbing a known one-time
   * expensive write (see the `firstSeriesReadOutcome` call site) may override them.
   */
  private suspend fun attemptWithRetries(
    attempts: Int = TRANSIENT_RETRY_ATTEMPTS,
    backoffMillis: Long = TRANSIENT_RETRY_BACKOFF_MILLIS,
    request: suspend () -> HttpResponse,
  ): RetryOutcome {
    val loopMark = TimeSource.Monotonic.markNow()
    var lastFailure: Throwable? = null
    var lastFailureType: String? = null
    repeat(attempts) { attempt ->
      val attemptMark = TimeSource.Monotonic.markNow()
      try {
        val response = request()
        if (response.status.value < 500) {
          return RetryOutcome(
            response = response,
            attempts = attempt + 1,
            successfulAttemptElapsedNanos = attemptMark.elapsedNow().inWholeNanoseconds,
            harnessWallElapsedNanos = loopMark.elapsedNow().inWholeNanoseconds,
            lastFailureType = lastFailureType,
          )
        }
        lastFailureType = "http_${response.status.value}"
        lastFailure = IllegalStateException("Transient server error: HTTP ${response.status}")
      } catch (failure: Exception) {
        lastFailureType = "exception_${failure::class.simpleName}"
        lastFailure = failure
      }
      // Full jitter, not a fixed delay: a fixed backoff can stay phase-locked with the competing
      // background writer's own steady poll cadence, so every retry lands in the same collision
      // window instead of a progressively different one. Observed in practice: a fixed 300ms
      // backoff against the largest library size here failed all 10 attempts in a row.
      if (attempt < attempts - 1) delay(Random.nextLong(1, backoffMillis + 1))
    }
    throw requireNotNull(lastFailure) { "Retry loop exited without recording a failure" }
  }

  /** One-shot convenience over [attemptWithRetries] for setup/seed calls that are not timed. */
  private suspend fun retryingTransientFailures(request: suspend () -> HttpResponse): HttpResponse =
    attemptWithRetries(request = request).response

  private fun intProperty(
    name: String,
    default: Int,
  ): Int = System.getProperty(name)?.toIntOrNull() ?: default

  /** Result of [measureRepeated]: latency stats from clean (non-retried) samples only, plus how many samples were retried. */
  private data class MeasuredCall(
    val stats: LatencyStats,
    val retriedSamples: Int,
  )

  /**
   * Outcome of [attemptWithRetries]. Three distinct timings are kept separate rather than folded
   * into one number, because they answer different questions:
   * - [successfulAttemptElapsedNanos]: how long the one call that actually succeeded took, on its
   *   own — this is the real, isolated server-side cost.
   * - [harnessWallElapsedNanos]: the full wall-clock time of the whole retry loop, including every
   *   failed attempt and every backoff sleep — this is harness time, not server time, and is
   *   reported as such rather than presented as a server latency.
   * - [attempts] and [lastFailureType] describe how many tries it took and what the last failure
   *   looked like (an HTTP status or an exception class), so a reader can tell "fast but blocked
   *   repeatedly" apart from "the call itself is slow" — two different problems with different
   *   fixes.
   */
  private data class RetryOutcome(
    val response: HttpResponse,
    val attempts: Int,
    val successfulAttemptElapsedNanos: Long,
    val harnessWallElapsedNanos: Long,
    val lastFailureType: String?,
  )

  private companion object {
    const val DEFAULT_SERIES_COUNT = 20
    const val DEFAULT_BOOKS_PER_SERIES = 5
    const val DEFAULT_ONE_SHOT_COUNT = 5
    const val RESCAN_REPEAT_COUNT = 5

    /**
     * Floor for the drain deadline, for catalogs small enough that the proportional budget is trivial.
     */
    val QUEUE_DRAIN_MINIMUM = 30.seconds

    /**
     * Per-item drain budget.
     *
     * Calibrated from an observation, not chosen: at 3,050 items the queue drained in 29.9 s, or about
     * 10 ms per item, and a budget three times that leaves room for a slower machine without waiting
     * indefinitely on a genuinely stuck queue.
     */
    const val QUEUE_DRAIN_MILLIS_PER_ITEM = 30L

    /**
     * Poll interval. Two orders of magnitude below the deadline, so the polling itself cannot
     * meaningfully distort the measurement it exists to protect.
     */
    val QUEUE_DRAIN_POLL = 250.milliseconds
    const val WARMUP_REQUESTS = 5
    const val MEASURED_REQUESTS = 50
    /** Page size for the first-page/last-page comparison; matches `api.series_listing`'s. */
    const val DEEP_PAGE_SIZE = 20
    const val KEEP_READING_SAMPLE_SIZE = 200
    const val KEEP_READING_SEED_STRIDE = 5
    const val TRANSIENT_RETRY_ATTEMPTS = 5
    const val TRANSIENT_RETRY_BACKOFF_MILLIS = 150L
    const val CACHE_WARMUP_RETRY_ATTEMPTS = 10
    const val CACHE_WARMUP_RETRY_BACKOFF_MILLIS = 300L
  }
}
