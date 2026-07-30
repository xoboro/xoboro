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
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.sources.local.LocalSourceInventory
import io.xoboro.server.sources.local.LocalSourceMediaAccess
import io.xoboro.server.xoboroModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
 * Native API calls retry transient 5xx responses a few times (see [retryingTransientFailures]):
 * a rarely observed `SQLITE_BUSY_SNAPSHOT` from background scheduler/worker threads racing a
 * request-triggered cache refresh can otherwise fail a single sample. That is a pre-existing
 * concurrency detail of the read path, reported here rather than fixed, and on the rare run where
 * it fires it may inflate that one sample's latency by the retry backoff.
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

      val rescanElapsed = measureTime { scanner.scan(library, deep = false) }
      report.recordMillis("unchanged_rescan.wall", scannedBookCount, rescanElapsed.inWholeMilliseconds.toDouble())
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
          taskPollMillis = 50,
          taskFailurePollMillis = 50,
          taskLeaseMillis = 1_000,
          shutdownTimeoutMillis = 2_000,
        ),
      )
    try {
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

        val topSeries =
          retryingTransientFailures {
            client.get("$XOBORO_API_PREFIX/series?page=0&size=1&sort=mediaItemCount,desc") {
              bearerAuth(token)
            }
          }.body<XoboroPageResponse<XoboroSeriesResponse>>()
            .items
            .single()

        report.recordLatency(
          "api.series_listing",
          scannedBookCount,
          measureRepeated {
            retryingTransientFailures {
              client.get("$XOBORO_API_PREFIX/series?page=0&size=20") { bearerAuth(token) }
            }
          },
        )

        report.recordLatency(
          "api.books_in_series",
          topSeries.mediaItemCount.toLong(),
          measureRepeated {
            retryingTransientFailures {
              client.get("$XOBORO_API_PREFIX/series/${topSeries.id}/media-items?page=0&size=20") {
                bearerAuth(token)
              }
            }
          },
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

        report.recordLatency(
          "api.keep_reading_feed",
          scannedBookCount,
          measureRepeated {
            retryingTransientFailures {
              client.get("$XOBORO_API_PREFIX/media-items?keepReading=true&page=0&size=20") {
                bearerAuth(token)
              }
            }
          },
        )
      }
    } finally {
      runtime.close()
    }
  }

  private suspend fun measureRepeated(request: suspend () -> HttpResponse): LatencyStats {
    repeat(WARMUP_REQUESTS) { request() }
    val samples =
      (1..MEASURED_REQUESTS).map {
        val mark = TimeSource.Monotonic.markNow()
        request()
        mark.elapsedNow().inWholeNanoseconds
      }
    return LatencyStats.of(samples)
  }

  /**
   * Retries a request a few times on a 5xx response or a thrown exception. Exists to smooth over
   * a rarely observed transient `SQLITE_BUSY_SNAPSHOT` (see the class doc); it is not a
   * production fix, only a harness-level tolerance for a known-rare race between a request's
   * read-triggered cache refresh and background scheduler/worker writes.
   */
  private suspend fun retryingTransientFailures(request: suspend () -> HttpResponse): HttpResponse {
    var lastFailure: Throwable? = null
    repeat(TRANSIENT_RETRY_ATTEMPTS) { attempt ->
      try {
        val response = request()
        if (response.status.value < 500) return response
        lastFailure = IllegalStateException("Transient server error: HTTP ${response.status}")
      } catch (failure: Exception) {
        lastFailure = failure
      }
      if (attempt < TRANSIENT_RETRY_ATTEMPTS - 1) delay(TRANSIENT_RETRY_BACKOFF_MILLIS)
    }
    throw requireNotNull(lastFailure) { "Retry loop exited without recording a failure" }
  }

  private fun intProperty(
    name: String,
    default: Int,
  ): Int = System.getProperty(name)?.toIntOrNull() ?: default

  private companion object {
    const val DEFAULT_SERIES_COUNT = 20
    const val DEFAULT_BOOKS_PER_SERIES = 5
    const val DEFAULT_ONE_SHOT_COUNT = 5
    const val WARMUP_REQUESTS = 5
    const val MEASURED_REQUESTS = 50
    const val KEEP_READING_SAMPLE_SIZE = 200
    const val KEEP_READING_SEED_STRIDE = 5
    const val TRANSIENT_RETRY_ATTEMPTS = 3
    const val TRANSIENT_RETRY_BACKOFF_MILLIS = 50L
  }
}
