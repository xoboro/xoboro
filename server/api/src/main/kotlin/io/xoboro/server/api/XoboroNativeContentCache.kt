package io.xoboro.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.util.date.GMTDate
import io.xoboro.core.application.MediaContentStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal suspend fun ApplicationCall.respondNativeCachedContent(
  stream: MediaContentStream,
  lastModifiedMillis: Long,
) {
  val body = stream.readNativeCachedBody()
  response.header(HttpHeaders.CacheControl, NATIVE_PRIVATE_REVALIDATE)
  response.header(HttpHeaders.ETag, body.entityTag)
  response.header(
    HttpHeaders.LastModified,
    GMTDate(lastModifiedMillis.toHttpSecond()).toHttpDate(),
  )
  if (isNativeContentNotModified(body.entityTag, lastModifiedMillis)) {
    respond(HttpStatusCode.NotModified)
  } else {
    respondBytes(body.bytes, stream.mediaType.toNativeContentType())
  }
}

internal fun String.toNativeContentType(): ContentType =
  runCatching { ContentType.parse(this) }
    .getOrDefault(ContentType.Application.OctetStream)

private data class NativeCachedBody(
  val bytes: ByteArray,
  val entityTag: String,
)

private fun MediaContentStream.readNativeCachedBody(): NativeCachedBody {
  val initialCapacity =
    contentLength
      ?.coerceIn(0, MAXIMUM_EAGER_ALLOCATION.toLong())
      ?.toInt()
      ?: CONTENT_BUFFER_SIZE
  val output = ByteArrayOutputStream(initialCapacity)
  val buffer = ByteArray(CONTENT_BUFFER_SIZE)
  while (true) {
    val read = read(buffer)
    if (read < 0) break
    if (read > 0) output.write(buffer, 0, read)
  }
  val bytes = output.toByteArray()
  val digest =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
  return NativeCachedBody(bytes = bytes, entityTag = "\"${digest.take(ENTITY_TAG_HEX_LENGTH)}\"")
}

private fun ApplicationCall.isNativeContentNotModified(
  entityTag: String,
  lastModifiedMillis: Long,
): Boolean {
  val requestedTags = request.headers[HttpHeaders.IfNoneMatch]
  if (requestedTags != null) {
    return requestedTags
      .split(',')
      .map(String::trim)
      .any { candidate -> candidate == "*" || candidate.removePrefix("W/").trim() == entityTag }
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
private const val MAXIMUM_EAGER_ALLOCATION = 1_024 * 1_024
private const val NATIVE_PRIVATE_REVALIDATE = "max-age=0, must-revalidate, private"
