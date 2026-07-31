package io.xoboro.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.xoboro.server.api.XOBORO_API_PREFIX
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Pins that serving the web UI does not swallow the API.
 *
 * Exercised through the real production module rather than the route in isolation,
 * because the failure this guards against is a routing-precedence one: the asset
 * route is the only wildcard in the tree, and whether it answers for an unmatched
 * `/api/...` path is a property of the assembled tree, not of the route.
 *
 * The failure mode is also the quiet kind - a client asking for JSON receives
 * `200` and HTML, which looks like a working UI and reads as a client bug.
 */
class XoboroWebAssetApplicationTest {
  @TempDir
  lateinit var tempDirectory: Path

  /**
   * Every reserved prefix, trailing slash trimmed, as probed by the tests below.
   *
   * Kept as its own list rather than read from production so that a prefix removed
   * there fails here too. The invariant being pinned is "no reserved prefix ever
   * answers with the application shell" - for a prefix whose module installs its own
   * catch-all (`/kobo`) a real handler answers first, which satisfies the invariant
   * just as well as the guard does.
   */
  private val RESERVED_PREFIXES_UNDER_TEST =
    listOf("/api", "/opds", "/health", "/ready", "/metrics", "/kobo", "/koreader", "/actuator")

  @Test
  fun `serves the application shell at the root`() {
    val web = webDirectory()

    testApplication {
      application { xoboroModule(openRuntime(web)) }

      val response = client.get("/")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue("xoboro-shell" in response.bodyAsText())
    }
  }

  @Test
  fun `serves a built asset`() {
    val web = webDirectory()

    testApplication {
      application { xoboroModule(openRuntime(web)) }

      val response = client.get("/assets/index-abc.js")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("export const marker = 1", response.bodyAsText())
    }
  }

  @Test
  fun `caches hashed assets but never the shell`() {
    // The shell names asset files that carry a content hash. Caching the shell
    // serves the previous deployment's HTML, which then requests asset names that
    // no longer exist - a blank page that a reload does not fix.
    val web = webDirectory()

    testApplication {
      application { xoboroModule(openRuntime(web)) }

      val shell = client.get("/")
      assertTrue(
        shell.headers[HttpHeaders.CacheControl].orEmpty().contains("no-cache"),
        "shell was cacheable: ${shell.headers[HttpHeaders.CacheControl]}",
      )

      val asset = client.get("/assets/index-abc.js")
      assertTrue(
        asset.headers[HttpHeaders.CacheControl].orEmpty().contains("max-age="),
        "asset was not cacheable: ${asset.headers[HttpHeaders.CacheControl]}",
      )
    }
  }

  @Test
  fun `falls back to the shell for an unknown path`() {
    val web = webDirectory()

    testApplication {
      application { xoboroModule(openRuntime(web)) }

      val response = client.get("/some/deep/link")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue("xoboro-shell" in response.bodyAsText())
    }
  }

  @Test
  fun `does not shadow the native API`() {
    // The asset route is registered last for this reason. An unknown native path
    // must still answer with the native error envelope, not the shell.
    val web = webDirectory()

    testApplication {
      application { xoboroModule(openRuntime(web)) }

      val session = client.get("$XOBORO_API_PREFIX/session")
      assertEquals(HttpStatusCode.Unauthorized, session.status)
      assertTrue("authentication_required" in session.bodyAsText())

      val missing = client.get("$XOBORO_API_PREFIX/nothing-here")
      assertEquals(HttpStatusCode.NotFound, missing.status)
      assertFalse("xoboro-shell" in missing.bodyAsText(), "the shell answered for an API path")
    }
  }

  @Test
  fun `does not shadow any reserved server surface`() {
    // Every probe deliberately names a path with **no** concrete route of its own, so
    // the guard is what answers rather than a real handler winning on precedence. The
    // earlier version of this test probed `/api/v1/libraries` and `/opds/v1.2/catalog`,
    // which have concrete routes and therefore passed whether the prefix was listed or
    // not - it asserted Ktor's matcher, not the guard.
    val web = webDirectory()

    testApplication {
      application { xoboroModule(openRuntime(web)) }

      for (prefix in RESERVED_PREFIXES_UNDER_TEST) {
        val path = "$prefix/unmatched-probe".replace("//", "/")
        val response = client.get(path)
        assertFalse(
          "xoboro-shell" in response.bodyAsText(),
          "the shell answered for $path with ${response.status}",
        )
      }
    }
  }

  @Test
  fun `probes every reserved prefix`() {
    // The list above is the reserved list, not a sample of it. A surface added to
    // XoboroWebAssetRoutes without a probe here would otherwise be shadowed silently,
    // which is the exact failure the reserved list exists to prevent.
    assertEquals(
      reservedPrefixesForTest().map { it.trimEnd('/') }.toSet(),
      RESERVED_PREFIXES_UNDER_TEST.toSet(),
      "the reserved list and the probed prefixes disagree",
    )
  }

  @Test
  fun `answers not found for a path that cannot be a filename`() {
    // A NUL byte decodes to a character no filesystem accepts, so `Path.resolve`
    // throws InvalidPathException. Uncaught it reached the global handler as a 500
    // with a logged stack trace per request - an unauthenticated caller could fill
    // the log. It is a path that does not exist, so it is a 404.
    val web = webDirectory()

    testApplication {
      application { xoboroModule(openRuntime(web)) }

      val response = client.get("/%00")
      assertEquals(HttpStatusCode.NotFound, response.status)
    }
  }

  @Test
  fun `still answers health and readiness`() {
    val web = webDirectory()

    testApplication {
      application { xoboroModule(openRuntime(web)) }

      // Asserting the body, not just the status. Serialisation omits values equal
      // to their default, so this endpoint answered a bare `{}` until
      // HealthResponse was annotated - a probe looking for "status":"UP" saw
      // nothing, and only the healthy case was affected.
      val health = client.get("/health")
      assertEquals(HttpStatusCode.OK, health.status)
      assertEquals("""{"status":"UP","service":"xoboro"}""", health.bodyAsText())

      val ready = client.get("/ready")
      assertEquals(HttpStatusCode.OK, ready.status)
      assertTrue("\"status\":\"UP\"" in ready.bodyAsText(), "ready body was: ${ready.bodyAsText()}")
    }
  }

  @Test
  fun `refuses a path that escapes the served directory`() {
    val secret = tempDirectory.resolve("outside.txt")
    Files.writeString(secret, "not for the web")
    val web = webDirectory()

    testApplication {
      application { xoboroModule(openRuntime(web)) }

      // Encoded so the client does not normalise it away before it is sent, which
      // would test the client rather than the server.
      val response = client.get("/%2e%2e/outside.txt")
      assertFalse("not for the web" in response.bodyAsText(), "traversal escaped the web directory")
    }
  }

  @Test
  fun `starts and serves no UI when no directory is deployed`() {
    // A headless deployment is legitimate. Refusing to start without a UI would
    // make the API unusable for anyone who only wants the API.
    testApplication {
      application { xoboroModule(openRuntime(tempDirectory.resolve("absent-web"))) }

      assertEquals(HttpStatusCode.OK, client.get("/health").status)
      assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }
  }

  @Test
  fun `treats a directory without a shell as not deployed`() {
    // An empty or half-copied directory must not be served: every route would
    // answer 404 from the asset handler, including paths that would otherwise have
    // reached an API route.
    val empty = Files.createDirectories(tempDirectory.resolve("empty-web"))
    assertFalse(isServableWebDirectory(empty))

    val built = webDirectory()
    assertTrue(isServableWebDirectory(built))
  }

  /** A minimal stand-in for `web/dist`, with the same shape a Vite build produces. */
  private fun webDirectory(): Path {
    val root = Files.createDirectories(tempDirectory.resolve("web"))
    Files.createDirectories(root.resolve("assets"))
    Files.writeString(
      root.resolve("index.html"),
      """<!doctype html><html><body><div id="xoboro-shell"></div></body></html>""",
    )
    Files.writeString(root.resolve("assets/index-abc.js"), "export const marker = 1")
    return root
  }

  /**
   * Opens a runtime configured with [web] as its UI directory.
   *
   * Goes through `ServerConfig` and `XoboroRuntime` rather than passing the
   * directory straight to the module, so the wiring under test includes the
   * start-up check that decides whether a directory is worth serving at all.
   */
  private fun openRuntime(web: Path): XoboroRuntime =
    XoboroRuntime.open(
      ServerConfig(
        port = 25_600,
        databasePath = tempDirectory.resolve("web-assets.sqlite"),
        workerCount = 1,
        taskPollMillis = 10,
        taskFailurePollMillis = 10,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
        backupsDirectory = tempDirectory.resolve("backups"),
        webDirectory = web,
      ),
    )
}
