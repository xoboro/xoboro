package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.date.GMTDate
import java.security.MessageDigest

internal data class KomgaCachedBody(
  val bytes: ByteArray,
  val entityTag: String,
)

internal fun ByteArray.komgaCachedBody(): KomgaCachedBody =
  KomgaCachedBody(
    bytes = this,
    entityTag =
      MessageDigest
        .getInstance("MD5")
        .digest(this)
        .joinToString(prefix = "\"0", postfix = "\"", separator = "") { byte ->
          "%02x".format(byte.toInt() and 0xff)
        },
  )

internal suspend fun ApplicationCall.respondNotModifiedByTimestamp(
  lastModifiedMillis: Long,
): Boolean {
  if (request.headers[HttpHeaders.IfNoneMatch] != null) return false
  val modifiedSince =
    request.headers[HttpHeaders.IfModifiedSince]
      ?.let { value -> runCatching { value.fromHttpToGmtDate().timestamp }.getOrNull() }
      ?: return false
  if (modifiedSince < lastModifiedMillis.toHttpSecond()) return false
  appendKomgaCacheHeaders(lastModifiedMillis)
  respond(HttpStatusCode.NotModified)
  return true
}

internal suspend fun ApplicationCall.respondNotModified(
  body: KomgaCachedBody,
  lastModifiedMillis: Long?,
): Boolean {
  val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
  val matches =
    ifNoneMatch?.let { requested ->
      requested.trim() == "*" ||
        requested
          .split(',')
          .map(String::trim)
          .any { candidate -> candidate.weakEntityTag() == body.entityTag }
    } ?: false
  appendKomgaCacheHeaders(lastModifiedMillis, body.entityTag)
  if (!matches) return false
  respond(HttpStatusCode.NotModified)
  return true
}

internal fun ApplicationCall.appendKomgaCacheHeaders(
  lastModifiedMillis: Long?,
  entityTag: String? = null,
) {
  response.header(HttpHeaders.CacheControl, KOMGA_PRIVATE_REVALIDATE)
  lastModifiedMillis?.let {
    response.header(HttpHeaders.LastModified, GMTDate(it.toHttpSecond()).toHttpDate())
  }
  entityTag?.let { response.header(HttpHeaders.ETag, it) }
}

private fun String.weakEntityTag(): String = removePrefix("W/").trim()

private fun Long.toHttpSecond(): Long = this - Math.floorMod(this, 1_000L)

internal const val KOMGA_PRIVATE_REVALIDATE = "max-age=0, must-revalidate, private"
