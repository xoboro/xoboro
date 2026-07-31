package io.xoboro.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.testing.testApplication
import io.xoboro.server.api.XOBORO_API_PREFIX
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Keeps `openapi/xoboro-native-v1.yaml` honest by comparing it with the routing tree the server
 * actually builds.
 *
 * A hand-maintained OpenAPI description drifts - that is its normal failure mode, and it drifts
 * silently, because nothing consumes it at build time. So the description is not trusted here: this
 * test walks Ktor's routing tree from a real [XoboroRuntime] and asserts the two sets of
 * (method, path) pairs are equal. Adding a route without documenting it fails, and documenting an
 * operation that does not exist fails too.
 *
 * Two details the walk has to account for:
 * - `Route.sse {}` installs its own handler and registers **no** [HttpMethodRouteSelector], so the
 *   event stream is invisible to a method-selector walk. It is a real `GET`, so it is added back
 *   explicitly rather than being quietly excluded from both sides - an SSE route that disappeared
 *   would otherwise keep passing.
 * - Ktor renders a tailcard as `{...}` and folds authentication and rate-limit scopes into the
 *   rendered path. Both are normalised away, since neither is part of the URL a client calls.
 */
class XoboroNativeOpenApiContractTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `documents exactly the native routes the server registers`() {
    val documented = documentedOperations()
    val registered = registeredOperations()

    val undocumented = (registered - documented).sorted()
    val phantom = (documented - registered).sorted()

    assertTrue(
      undocumented.isEmpty(),
      "Routes with no entry in $SPEC_RESOURCE:\n${undocumented.joinToString("\n")}",
    )
    assertTrue(
      phantom.isEmpty(),
      "Entries in $SPEC_RESOURCE with no matching route:\n${phantom.joinToString("\n")}",
    )
  }

  @Test
  fun `serves the description and the browser documentation`() {
    val runtime = openRuntime("openapi-served.sqlite")
    testApplication {
      application { xoboroModule(runtime) }

      val spec = client.get(SPEC_PATH)
      assertEquals(HttpStatusCode.OK, spec.status)
      assertTrue(
        spec.bodyAsText().startsWith("# OpenAPI description of the Xoboro native API."),
        "the served description should be the committed file, verbatim",
      )

      // The dependency exists to render this. If the UI stops being mounted the description is
      // still correct but no longer discoverable, which is the failure worth catching.
      assertEquals(HttpStatusCode.OK, client.get(DOCUMENTATION_PATH).status)
    }
  }

  /**
   * Reads the `paths:` section without a YAML parser. Adding a YAML dependency to a single test
   * would be a poor trade, and the file's own indentation is fixed by the generator that writes it.
   */
  private fun documentedOperations(): Set<String> {
    val text =
      requireNotNull(javaClass.classLoader.getResourceAsStream(SPEC_RESOURCE)) {
        "$SPEC_RESOURCE is missing from the runtime classpath"
      }.use { it.readBytes().decodeToString() }
    val operations = mutableSetOf<String>()
    var path: String? = null
    var insidePaths = false
    text.lineSequence().forEach { line ->
      when {
        line == "paths:" -> insidePaths = true
        insidePaths && line.isNotEmpty() && !line.startsWith(" ") -> insidePaths = false
        insidePaths && PATH_LINE.matches(line) -> path = line.trim().removeSuffix(":")
        insidePaths && METHOD_LINE.matches(line) ->
          operations += "${line.trim().removeSuffix(":").uppercase()} ${requireNotNull(path)}"
      }
    }
    require(operations.isNotEmpty()) { "no operations parsed from $SPEC_RESOURCE" }
    return operations
  }

  private fun registeredOperations(): Set<String> {
    val runtime = openRuntime("openapi-contract.sqlite")
    val operations = mutableSetOf<String>()
    testApplication {
      application {
        xoboroModule(runtime)
        collectNativeOperations(plugin(RoutingRoot), operations)
      }
      client.get("/health")
    }
    return operations + "GET $XOBORO_API_PREFIX/events"
  }

  private fun collectNativeOperations(
    node: RoutingNode,
    into: MutableSet<String>,
  ) {
    val selector = node.selector
    if (selector is HttpMethodRouteSelector) {
      val path = node.parent?.toString()?.toClientPath()
      if (path != null && path.startsWith(XOBORO_API_PREFIX)) {
        into += "${selector.method.value} $path"
      }
    }
    node.children.forEach { collectNativeOperations(it, into) }
  }

  private fun String.toClientPath(): String {
    val withoutScopes = RATE_LIMIT_SCOPE.replace(AUTHENTICATE_SCOPE.replace(this, ""), "")
    return withoutScopes.replace("{...}", "{resource}").removeSuffix("/")
  }

  private fun openRuntime(databaseName: String): XoboroRuntime =
    XoboroRuntime.open(
      ServerConfig(
        port = 25_701,
        databasePath = temporaryDirectory.resolve(databaseName),
        workerCount = 1,
        taskPollMillis = 50,
        taskFailurePollMillis = 50,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 2_000,
        // Named explicitly, and named as absent. `ServerConfig` defaults this to the
        // relative `web`, which resolves against the process working directory - so
        // whether this runtime registers the asset wildcard at all would otherwise
        // depend on where Gradle happened to start the test from. The assertion above
        // only collects paths under the native prefix, so the wildcard could not
        // reach it either way; this removes the environmental dependency rather than
        // leaving it resting on that filter.
        webDirectory = temporaryDirectory.resolve("no-web-directory"),
      ),
    )

  private companion object {
    const val SPEC_RESOURCE = "openapi/xoboro-native-v1.yaml"
    const val SPEC_PATH = "$XOBORO_API_PREFIX/openapi.yaml"
    const val DOCUMENTATION_PATH = "$XOBORO_API_PREFIX/docs"
    val PATH_LINE = Regex("^ {2}/\\S*:$")
    val METHOD_LINE = Regex("^ {4}(get|post|put|patch|delete):$")
    val AUTHENTICATE_SCOPE = Regex("/\\(authenticate[^)]*\\)")
    val RATE_LIMIT_SCOPE = Regex("/\\(RateLimit[^)]*\\)")
  }
}
