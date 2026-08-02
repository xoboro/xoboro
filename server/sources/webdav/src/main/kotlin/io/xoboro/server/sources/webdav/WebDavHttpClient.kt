package io.xoboro.server.sources.webdav

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/** The outcome of a conditional `GET`: either a fresh body was written to the requested file, or the cached copy is still fresh. */
sealed interface WebDavGetOutcome {
  data class Fresh(
    val etag: String?,
    val lastModifiedHttpDate: String?,
    val contentLength: Long?,
  ) : WebDavGetOutcome

  data object NotModified : WebDavGetOutcome
}

/**
 * The only place `java.net.http.HttpClient` is used by this module. No other class builds a
 * request directly, which keeps "credentials go in the `Authorization` header and nowhere else"
 * and "every failure message is safe to log" true by construction rather than by convention.
 */
class WebDavHttpClient(
  private val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(30))
      .build(),
) {
  /** `PROPFIND` with the given [depth] (0 or 1), returning every `<response>` entry resolved to an absolute URL. */
  fun propfind(
    url: String,
    credentials: WebDavCredentials?,
    depth: Int,
  ): List<WebDavResource> {
    require(depth == 0 || depth == 1) { "WebDAV PROPFIND depth must be 0 or 1" }
    val requestUri = URI(url)
    val request =
      requestBuilder(requestUri, credentials)
        .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY, StandardCharsets.UTF_8))
        .header("Content-Type", "text/xml; charset=utf-8")
        .header("Depth", depth.toString())
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofByteArray())
    if (response.statusCode() != 207) throw failureFor("PROPFIND", url, response.statusCode())
    return WebDavMultiStatusParser.parse(response.body(), requestUri)
  }

  /**
   * `GET`s [url] into [destination] unless [ifNoneMatch] or [ifModifiedSince] make the server
   * answer `304 Not Modified`. A `404` returns `null` rather than throwing, since "the item is
   * gone" is an expected, checkable outcome for a caller deciding whether to keep a cache entry -
   * every other non-2xx/304/404 status is a failure.
   */
  fun fetchToFile(
    url: String,
    credentials: WebDavCredentials?,
    destination: Path,
    ifNoneMatch: String?,
    ifModifiedSince: String?,
  ): WebDavGetOutcome? {
    val request =
      requestBuilder(URI(url), credentials)
        .GET()
        .applyConditional(ifNoneMatch, ifModifiedSince)
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofFile(destination))
    return when (response.statusCode()) {
      200 ->
        WebDavGetOutcome.Fresh(
          etag = response.headers().firstValue("ETag").orElse(null)?.trim('"'),
          lastModifiedHttpDate = response.headers().firstValue("Last-Modified").orElse(null),
          contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L).takeIf { it >= 0 },
        )
      304 -> WebDavGetOutcome.NotModified
      404 -> null
      else -> throw failureFor("GET", url, response.statusCode())
    }
  }

  /**
   * `GET`s [url] fully into memory, refusing to read past `maximumBytes + 1` so a server cannot
   * force an unbounded allocation. Returns `null` for a `404`, mirroring [fetchToFile].
   */
  fun fetchBounded(
    url: String,
    credentials: WebDavCredentials?,
    maximumBytes: Int,
  ): ByteArray? {
    require(maximumBytes > 0) { "WebDAV byte limit must be positive" }
    val request = requestBuilder(URI(url), credentials).GET().build()
    val response = send(request, HttpResponse.BodyHandlers.ofInputStream())
    return when (response.statusCode()) {
      200 ->
        response.body().use { input ->
          input.readNBytes(maximumBytes + 1).also {
            require(it.size <= maximumBytes) { "WebDAV response exceeded the byte limit" }
          }
        }
      404 -> {
        response.body().close()
        null
      }
      else -> {
        response.body().close()
        throw failureFor("GET", url, response.statusCode())
      }
    }
  }

  private fun requestBuilder(
    uri: URI,
    credentials: WebDavCredentials?,
  ): HttpRequest.Builder {
    val builder = HttpRequest.newBuilder(uri)
    if (credentials != null) {
      val token =
        Base64.getEncoder().encodeToString("${credentials.username}:${credentials.password}".toByteArray(StandardCharsets.UTF_8))
      builder.header("Authorization", "Basic $token")
    }
    return builder
  }

  private fun HttpRequest.Builder.applyConditional(
    ifNoneMatch: String?,
    ifModifiedSince: String?,
  ): HttpRequest.Builder {
    if (ifNoneMatch != null) header("If-None-Match", "\"$ifNoneMatch\"")
    if (ifModifiedSince != null) header("If-Modified-Since", ifModifiedSince)
    return this
  }

  private fun <T> send(
    request: HttpRequest,
    handler: HttpResponse.BodyHandler<T>,
  ): HttpResponse<T> =
    try {
      httpClient.send(request, handler)
    } catch (failure: IOException) {
      throw failureFor(request.method(), request.uri().toString(), -1, failure)
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      throw failureFor(request.method(), request.uri().toString(), -1, failure)
    }

  private fun failureFor(
    method: String,
    url: String,
    status: Int,
    cause: Throwable? = null,
  ): WebDavRequestFailedException =
    if (status == 401) WebDavAuthenticationException(method, url) else WebDavRequestFailedException(method, url, status, cause)

  private companion object {
    const val PROPFIND_BODY =
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
        "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/><D:getetag/>" +
        "</D:prop></D:propfind>"
  }
}
