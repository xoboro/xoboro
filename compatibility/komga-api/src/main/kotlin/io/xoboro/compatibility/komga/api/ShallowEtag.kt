package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.http.content.HttpStatusCodeContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.header

fun Application.installKomgaShallowEtag() {
  sendPipeline.intercept(ApplicationSendPipeline.ContentEncoding) {
    val call = context
    val content = subject as? OutgoingContent.ByteArrayContent ?: return@intercept
    val servletPath = call.request.path().komgaServletPathOrNull() ?: return@intercept
    if (call.request.httpMethod != HttpMethod.Get || servletPath.isKomgaEtagExcluded()) {
      return@intercept
    }
    val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
    if (status.value !in 200..299) return@intercept
    if (
      content.headers[HttpHeaders.ETag] != null ||
      call.response.headers[HttpHeaders.ETag] != null
    ) {
      return@intercept
    }
    val cacheControl =
      content.headers[HttpHeaders.CacheControl]
        ?: call.response.headers[HttpHeaders.CacheControl]
    if (cacheControl.hasNoStoreDirective()) return@intercept

    val body = content.bytes().komgaCachedBody()
    if (cacheControl == null) {
      call.response.header(HttpHeaders.CacheControl, KOMGA_PRIVATE_REVALIDATE)
    }
    call.response.header(HttpHeaders.ETag, body.entityTag)
    if (call.matchesKomgaEntityTag(body.entityTag)) {
      call.response.status(HttpStatusCode.NotModified)
      proceedWith(HttpStatusCodeContent(HttpStatusCode.NotModified))
    }
  }
}

internal fun String.komgaServletPathOrNull(): String? =
  KOMGA_ETAG_PREFIXES
    .map(::indexOf)
    .filter { it >= 0 }
    .minOrNull()
    ?.let(::substring)

internal fun String.isKomgaEtagExcluded(): Boolean =
  KOMGA_ETAG_EXCLUSIONS.any { it.matches(this) }

private fun String?.hasNoStoreDirective(): Boolean =
  this
    ?.split(',')
    ?.any { it.trim().substringBefore('=').equals("no-store", ignoreCase = true) }
    ?: false

private val KOMGA_ETAG_PREFIXES = listOf("/api/", "/opds/", "/kobo/")
private val KOMGA_ETAG_EXCLUSIONS =
  listOf(
    Regex("^/api/v1/books/[^/]+/file(?:/.*)?$"),
    Regex("^/opds/v1\\.2/books/[^/]+/file(?:/.*)?$"),
    Regex("^/api/v1/readlists/[^/]+/file(?:/.*)?$"),
    Regex("^/api/v1/series/[^/]+/file(?:/.*)?$"),
    Regex("^/kobo/[^/]+/v1/books/[^/]+/file(?:/.*)?$"),
  )
