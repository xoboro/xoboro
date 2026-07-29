package io.xoboro.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import io.ktor.server.application.ApplicationCall
import io.xoboro.core.application.MediaContentStream
import java.io.OutputStream

internal sealed interface NativeDownloadRange {
  data object Full : NativeDownloadRange

  data class Partial(
    val first: Long,
    val last: Long,
  ) : NativeDownloadRange {
    val length: Long = last - first + 1
  }

  data object Unsatisfiable : NativeDownloadRange
}

internal fun ApplicationCall.isNativeDownloadNotModified(entityTag: String): Boolean {
  val requestedTags = request.headers[HttpHeaders.IfNoneMatch] ?: return false
  return requestedTags
    .split(',')
    .map(String::trim)
    .any { candidate ->
      candidate == "*" || nativeWeakEntityTagsMatch(candidate, entityTag)
    }
}

internal fun resolveNativeDownloadRange(
  rangeHeader: String?,
  ifRangeHeader: String?,
  entityTag: String,
  lastModifiedMillis: Long,
  totalLength: Long,
): NativeDownloadRange {
  if (rangeHeader == null) return NativeDownloadRange.Full
  if (
    ifRangeHeader != null &&
    !nativeIfRangeMatches(ifRangeHeader, entityTag, lastModifiedMillis)
  ) {
    return NativeDownloadRange.Full
  }
  if (!rangeHeader.startsWith("bytes=")) return NativeDownloadRange.Full
  val value = rangeHeader.removePrefix("bytes=")
  if (value.isEmpty() || ',' in value) return NativeDownloadRange.Full
  val parts = value.split('-', limit = 2)
  if (parts.size != 2) return NativeDownloadRange.Full
  val firstText = parts[0].trim()
  val lastText = parts[1].trim()
  if (firstText.isEmpty()) {
    val suffixLength = lastText.toLongOrNull() ?: return NativeDownloadRange.Full
    if (suffixLength <= 0) return NativeDownloadRange.Full
    if (totalLength <= 0) return NativeDownloadRange.Unsatisfiable
    val first = (totalLength - suffixLength).coerceAtLeast(0)
    return NativeDownloadRange.Partial(first, totalLength - 1)
  }
  val first = firstText.toLongOrNull() ?: return NativeDownloadRange.Full
  if (first < 0) return NativeDownloadRange.Full
  val requestedLast =
    if (lastText.isEmpty()) {
      null
    } else {
      lastText.toLongOrNull() ?: return NativeDownloadRange.Full
    }
  if (requestedLast != null && requestedLast < first) return NativeDownloadRange.Full
  if (first >= totalLength) return NativeDownloadRange.Unsatisfiable
  val last = requestedLast?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
  return NativeDownloadRange.Partial(first, last)
}

internal fun nativeDownloadContentDisposition(fileName: String): String {
  val asciiName =
    fileName.map { character ->
      if (character.code in 0x20..0x7e && character != '"' && character != '\\') {
        character
      } else {
        '_'
      }
    }.joinToString("")
  val encodedName =
    fileName.encodeToByteArray().joinToString("") { byte ->
      val value = byte.toInt() and 0xff
      if (value.isRfc5987Unreserved()) {
        value.toChar().toString()
      } else {
        "%${value.toUpperHex()}"
      }
    }
  return "attachment; filename=\"$asciiName\"; filename*=UTF-8''$encodedName"
}

internal fun MediaContentStream.skipNativeDownloadBytes(byteCount: Long) {
  check(skip(byteCount) == byteCount) {
    "Book stream ended before the requested range"
  }
}

internal fun MediaContentStream.writeNativeDownload(
  output: OutputStream,
  contentLength: Long,
) {
  val buffer = ByteArray(NATIVE_DOWNLOAD_BUFFER_SIZE)
  var remaining = contentLength
  while (remaining > 0) {
    val maximum = remaining.coerceAtMost(buffer.size.toLong()).toInt()
    val read = read(buffer, length = maximum)
    if (read < 0) break
    if (read > 0) {
      output.write(buffer, 0, read)
      remaining -= read
    }
  }
}

private fun nativeIfRangeMatches(
  headerValue: String,
  entityTag: String,
  lastModifiedMillis: Long,
): Boolean {
  if (nativeWeakEntityTagsMatch(headerValue, entityTag)) return true
  val headerTimestamp =
    runCatching { headerValue.fromHttpToGmtDate().timestamp }
      .getOrNull()
      ?: return false
  return headerTimestamp.toHttpSecond() == lastModifiedMillis.toHttpSecond()
}

private fun nativeWeakEntityTagsMatch(
  first: String,
  second: String,
): Boolean = first.nativeWeakEntityTagValue() == second.nativeWeakEntityTagValue()

private fun String.nativeWeakEntityTagValue(): String = trim().removePrefix("W/").trim()

private fun Int.isRfc5987Unreserved(): Boolean =
  this in 'a'.code..'z'.code ||
    this in 'A'.code..'Z'.code ||
    this in '0'.code..'9'.code ||
    toChar() in RFC_5987_UNRESERVED

private fun Int.toUpperHex(): String =
  HEX_DIGITS[(this ushr 4) and 0xf].toString() + HEX_DIGITS[this and 0xf]

private const val NATIVE_DOWNLOAD_BUFFER_SIZE = 8 * 1_024
private const val RFC_5987_UNRESERVED = "-._~"
private const val HEX_DIGITS = "0123456789ABCDEF"
