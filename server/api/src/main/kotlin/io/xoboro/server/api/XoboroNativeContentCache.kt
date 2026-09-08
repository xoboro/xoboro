package io.xoboro.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.util.date.GMTDate
import io.xoboro.core.application.MediaContentStream
import java.security.MessageDigest

internal suspend fun ApplicationCall.respondNativeNotModified(
  entityTag: String,
  lastModifiedMillis: Long,
  cacheControl: String = NATIVE_PRIVATE_REVALIDATE,
): Boolean {
  response.header(HttpHeaders.CacheControl, cacheControl)
  response.header(HttpHeaders.ETag, entityTag)
  response.header(
    HttpHeaders.LastModified,
    GMTDate(lastModifiedMillis.toHttpSecond()).toHttpDate(),
  )
  if (!isNativeContentNotModified(entityTag, lastModifiedMillis)) return false
  respond(HttpStatusCode.NotModified)
  return true
}

internal suspend fun ApplicationCall.respondNativeContent(stream: MediaContentStream) {
  respondOutputStream(
    contentType = stream.mediaType.toNativeContentType(),
    status = HttpStatusCode.OK,
    contentLength = stream.contentLength,
) {
    try {
      val buffer = ByteArray(CONTENT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        if (read > 0) write(buffer, 0, read)
      }
    } finally {
      // respondOutputStream can run this writer after the route handler has returned. The writer,
      // not the handler, therefore owns the stream; closing it around the call produced a declared
      // Content-Length with an empty body under the real Ktor engine.
      stream.close()
    }
  }
}

internal fun nativeMetadataEntityTag(vararg components: Any?): String {
  val digest = MessageDigest.getInstance("SHA-256")
  components.forEach { component ->
    digest.update(component.toString().encodeToByteArray())
    digest.update(0)
  }
  val value =
    digest.digest().joinToString(separator = "") { byte ->
      "%02x".format(byte.toInt() and 0xff)
    }
  return "W/\"${value.take(ENTITY_TAG_HEX_LENGTH)}\""
}

internal fun String.toNativeContentType(): ContentType =
  runCatching { ContentType.parse(this) }
    .getOrDefault(ContentType.Application.OctetStream)

private fun ApplicationCall.isNativeContentNotModified(
  entityTag: String,
  lastModifiedMillis: Long,
): Boolean {
  val requestedTags = request.headers[HttpHeaders.IfNoneMatch]
  if (requestedTags != null) {
    val normalizedEntityTag = entityTag.removePrefix("W/").trim()
    return requestedTags
      .split(',')
      .map(String::trim)
      .any { candidate ->
        candidate == "*" || candidate.removePrefix("W/").trim() == normalizedEntityTag
      }
  }
  val modifiedSince =
    request.headers[HttpHeaders.IfModifiedSince]
      ?.let { value -> runCatching { value.fromHttpToGmtDate().timestamp }.getOrNull() }
      ?: return false
  return modifiedSince >= lastModifiedMillis.toHttpSecond()
}

internal fun Long.toHttpSecond(): Long = this - Math.floorMod(this, 1_000L)

private const val ENTITY_TAG_HEX_LENGTH = 32
private const val CONTENT_BUFFER_SIZE = 8 * 1_024
private const val NATIVE_PRIVATE_REVALIDATE = "max-age=0, must-revalidate, private"

/**
 * What a cover may be reused for without asking again.
 *
 * `max-age=0` made every cover on a grid a conditional request, so returning to a screen of a hundred
 * of them cost a hundred round trips to be told nothing had changed. A cover's URL is not
 * content-addressed - `/series/{id}/artwork` answers whatever is selected now - so it cannot be cached
 * indefinitely either; a re-scan or an upload would go unseen.
 *
 * Five minutes is picked against how a cover actually changes. Nothing changes one except a scan, an
 * upload or a selection, all of which publish an artwork event the reader is already subscribed to, so
 * the screen learns immediately and this window only bounds how long a client that missed the event
 * stays wrong. `must-revalidate` keeps a stale copy from being served past it.
 */
internal const val NATIVE_ARTWORK_CACHE_CONTROL = "max-age=300, must-revalidate, private"
