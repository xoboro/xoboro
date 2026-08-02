package io.xoboro.server.sources.webdav

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import java.net.ServerSocket
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real, in-process WebDAV server backed by a synthetic [FixtureDirectory] tree, so the adapter
 * under test parses an actual `207 Multi-Status` body produced by an actual PROPFIND round-trip
 * rather than a hand-mocked HTTP client.
 */
class FakeWebDavServer(
  private val tree: FixtureDirectory,
  private val credentials: WebDavCredentials? = null,
  /** Relative slash-joined paths (e.g. `"Alpha/Missing"`) whose PROPFIND always answers `500`, to exercise a mid-walk failure deterministically. */
  private val failingPropfindPaths: Set<String> = emptySet(),
) : AutoCloseable {
  private val basePath = "/dav"
  val port: Int = findFreePort()
  val baseUrl: String = "http://127.0.0.1:$port$basePath"

  private val getRequestCounts = ConcurrentHashMap<String, AtomicInteger>()

  private val server =
    embeddedServer(Netty, port = port, module = { module() }).also { it.start(wait = false) }

  fun getRequestCount(path: String): Int = getRequestCounts[path]?.get() ?: 0

  override fun close() {
    server.stop(gracePeriodMillis = 0, timeoutMillis = 200)
  }

  private fun Application.module() {
    intercept(ApplicationCallPipeline.Call) {
      val request = call.request
      val rawPath = request.uri.substringBefore('?')
      if (!rawPath.startsWith(basePath)) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return@intercept
      }
      if (credentials != null && !authorized(request.header("Authorization"))) {
        call.response.header("WWW-Authenticate", "Basic realm=\"WebDAV\"")
        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
        return@intercept
      }
      val decodedPath = URI("http://fixture$rawPath").path
      val relativeSegments =
        decodedPath.removePrefix(basePath).trim('/').split('/').filter(String::isNotEmpty)
      when (request.httpMethod.value) {
        "PROPFIND" -> handlePropfind(call, relativeSegments, request.header("Depth") ?: "0")
        "GET" ->
          handleGet(
            call,
            rawPath,
            relativeSegments,
            request.header("If-None-Match"),
            request.header("If-Modified-Since"),
          )
        else -> call.respondText("unsupported method", status = HttpStatusCode.MethodNotAllowed)
      }
    }
  }

  private fun authorized(header: String?): Boolean {
    val expected = credentials ?: return true
    val prefix = "Basic "
    if (header == null || !header.startsWith(prefix)) return false
    val decoded = runCatching { String(Base64.getDecoder().decode(header.removePrefix(prefix))) }.getOrNull()
    return decoded == "${expected.username}:${expected.password}"
  }

  private suspend fun handlePropfind(
    call: ApplicationCall,
    segments: List<String>,
    depth: String,
  ) {
    if (segments.joinToString("/") in failingPropfindPaths) {
      call.respondText("synthetic failure", status = HttpStatusCode.InternalServerError)
      return
    }
    val node = tree.navigate(segments)
    if (node == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val selfHref = hrefFor(segments)
    val entries = mutableListOf(responseXml(selfHref, node))
    if (depth == "1" && node is FixtureDirectory) {
      node.children.forEach { child ->
        entries += responseXml(hrefFor(segments + child.name), child)
      }
    }
    val body =
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<D:multistatus xmlns:D=\"DAV:\">${entries.joinToString("")}</D:multistatus>"
    call.respondText(body, ContentType.parse("text/xml"), HttpStatusCode(207, "Multi-Status"))
  }

  private suspend fun handleGet(
    call: ApplicationCall,
    rawPath: String,
    segments: List<String>,
    ifNoneMatch: String?,
    ifModifiedSince: String?,
  ) {
    val node = tree.navigate(segments)
    if (node !is FixtureFile) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val quotedEtag = node.etag?.let { "\"$it\"" }
    if ((quotedEtag != null && ifNoneMatch == quotedEtag) ||
      (node.lastModifiedHttpDate != null && ifModifiedSince == node.lastModifiedHttpDate)
    ) {
      call.respondText("", status = HttpStatusCode.NotModified)
      return
    }
    // Only a body-serving 200 counts as a real fetch - a 304 revalidation is the cheap round-trip
    // caching is supposed to leave in place, not the "second GET" a cache hit is meant to avoid.
    getRequestCounts.computeIfAbsent(rawPath) { AtomicInteger() }.incrementAndGet()
    quotedEtag?.let { call.response.header("ETag", it) }
    node.lastModifiedHttpDate?.let { call.response.header("Last-Modified", it) }
    call.respondBytes(node.bytes, ContentType.Application.OctetStream, HttpStatusCode.OK)
  }

  private fun responseXml(
    href: String,
    node: FixtureNode,
  ): String {
    val propertiesXml = StringBuilder()
    propertiesXml.append(if (node is FixtureDirectory) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>")
    if (node is FixtureFile) {
      if (!node.omitContentLength) propertiesXml.append("<D:getcontentlength>${node.bytes.size}</D:getcontentlength>")
      node.lastModifiedHttpDate?.let { propertiesXml.append("<D:getlastmodified>$it</D:getlastmodified>") }
      node.etag?.let { propertiesXml.append("<D:getetag>&quot;$it&quot;</D:getetag>") }
    }
    return "<D:response><D:href>${href.xmlEscape()}</D:href>" +
      "<D:propstat><D:prop>$propertiesXml</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>" +
      "</D:response>"
  }

  private fun hrefFor(segments: List<String>): String {
    val path = (basePath.trim('/').split('/') + segments).joinToString("/")
    return URI(null, null, "/$path", null).rawPath
  }

  private fun String.xmlEscape(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private fun FixtureDirectory.navigate(segments: List<String>): FixtureNode? {
    var current: FixtureNode = this
    for (segment in segments) {
      val directory = current as? FixtureDirectory ?: return null
      current = directory.children.firstOrNull { it.name == segment } ?: return null
    }
    return current
  }

  companion object {
    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
  }
}
