package io.xoboro.server

import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Serves the built web UI from a directory.
 *
 * A directory rather than the server jar's resources, so the Gradle build gains no
 * dependency on npm and the server does not need Node to compile. A container image
 * copies `web/dist` into it; a developer points [XOBORO_WEB_PATH_KEY] at their own
 * build output.
 *
 * Registered **after** every API route and guarded by [RESERVED_PREFIXES] rather
 * than trusting matcher precedence. A single wildcard is all it takes for an
 * unknown `/api/...` path to answer with the application shell instead of the
 * native error envelope, and that failure looks like a working UI — the client
 * receives `200` and HTML where it expected a coded JSON error.
 */
internal fun Route.xoboroWebAssetRoutes(
  directory: Path,
  reservedPrefixes: List<String> = RESERVED_PREFIXES,
) {
  val root = directory.toAbsolutePath().normalize()
  val index = root.resolve(INDEX_FILE)

  get("/{path...}") {
    val segments = call.parameters.getAll("path").orEmpty().filter(String::isNotEmpty)
    val requestPath = "/" + segments.joinToString("/")

    // Left unanswered on purpose. Declining to respond lets the request fall
    // through to a plain 404, which the global StatusPages handler then renders as
    // the native error envelope for an API path - the same answer the caller would
    // get if this route did not exist at all.
    //
    // Matched on segment boundaries, the same way `SecurityHeaders.startsWithPathSegment`
    // does. A raw `startsWith` also reserved paths the server has no route for - `/ready`
    // shadowed a SPA route at `/readers` - and needed a second `"/api/"`-style entry per
    // prefix to express "the prefix itself, too". One rule covers both.
    if (reservedPrefixes.any { requestPath == it || requestPath.startsWith("$it/") }) return@get

    // A decoded segment can hold a character no filesystem accepts - a NUL byte from
    // `%00` is the reachable case - and `resolve` throws for it. That is a path which
    // does not exist, so it answers 404 here rather than reaching the global handler
    // as a 500 with a logged stack trace per request.
    val requested =
      if (segments.isEmpty()) {
        index
      } else {
        try {
          root.resolve(segments.joinToString("/")).normalize()
        } catch (_: InvalidPathException) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
      }

    // A request whose *lexical* path leaves the served directory is refused rather
    // than quietly answered with the shell: `..` in a path is not a route this
    // application has, so treating it as one would hide an attempt to read the
    // filesystem behind an ordinary-looking page.
    //
    // Lexical, not resolved: `normalize` does not follow links, so a symlink inside
    // the served tree pointing outward still passes. That is deliberate rather than
    // overlooked - the served tree is a build product (`COPY web/dist`) which
    // contains no links, and planting one requires write access to the directory the
    // server reads, which is already a lost position. `toRealPath` would also cost a
    // stat per request and would break a root that is itself a symlink, which works
    // today.
    if (!requested.startsWith(root)) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }

    val file = if (Files.isRegularFile(requested)) requested else index
    if (!Files.isRegularFile(file)) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }

    // Built asset names carry a content hash, so they can be cached indefinitely.
    // The shell must not be: caching it serves the previous deployment's HTML,
    // which then loads asset names that no longer exist.
    call.response.cacheControl(
      if (file == index) {
        CacheControl.NoCache(null)
      } else {
        CacheControl.MaxAge(maxAgeSeconds = IMMUTABLE_MAX_AGE_SECONDS, mustRevalidate = false)
      },
    )
    // respondFile derives the content type from the extension, which is why the
    // fallback returns index.html itself rather than its bytes with a guessed type.
    call.respondFile(file.toFile())
  }
}

/** Whether [directory] holds a built UI worth serving. */
internal fun isServableWebDirectory(directory: Path): Boolean =
  Files.isRegularFile(directory.toAbsolutePath().normalize().resolve(INDEX_FILE))

internal const val XOBORO_WEB_PATH_KEY = "XOBORO_WEB_PATH"

private const val INDEX_FILE = "index.html"
private const val IMMUTABLE_MAX_AGE_SECONDS = 31_536_000

/**
 * Path prefixes the UI must never answer for.
 *
 * These are the server's own surfaces, and the list has to name every one of them
 * that is registered at the same level as the wildcard. Being incomplete is this
 * list's whole failure mode, and it has been incomplete twice: first `/koreader` and
 * `/actuator`, then `/sse`, `/oauth2/authorization`, `/login/oauth2/code` and
 * `/v3/api-docs`. An unmatched path under any of them answered `200` and HTML, so a
 * KOReader device syncing against a mistyped path, or a Komga client reconnecting to
 * the event stream, received the application shell where it expected a protocol
 * response — and read it as its own bug.
 *
 * `/kobo` is belt-and-braces: `komgaKoboRoutes` installs its own catch-all, so an
 * unmatched `/kobo/...` path is answered by a real handler (`401`) rather than
 * reaching this route at all. It is listed because that is an implementation detail
 * of another module rather than a guarantee this one can rely on.
 *
 * `/login/oauth2/code` is the full registered path on purpose. `/login` belongs to
 * the SPA, and reserving it would break the sign-in route.
 *
 * Two tests keep this honest. One probes every entry through the assembled
 * application; the other walks Ktor's routing tree and asserts each registered path
 * is covered here, so a surface added anywhere in the server without an entry fails
 * instead of being shadowed silently. That second test is what the first one could
 * not do: a probe list maintained by hand omits exactly what this list omits.
 */
private val RESERVED_PREFIXES =
  listOf(
    "/api",
    "/opds",
    "/sse",
    "/oauth2/authorization",
    "/login/oauth2/code",
    "/v3/api-docs",
    "/health",
    "/ready",
    "/metrics",
    "/kobo",
    "/koreader",
    "/actuator",
  )

/** The reserved list, for the test that pins every entry as probed. */
internal fun reservedPrefixesForTest(): List<String> = RESERVED_PREFIXES
