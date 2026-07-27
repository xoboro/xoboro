package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.path

fun Application.installKomgaSecurityHeaders() {
  intercept(ApplicationCallPipeline.Call) {
    val call = context
    val framePolicy =
      call.request.path().komgaFramePolicyOrNull()
        ?: return@intercept proceed()
    call.response.headers.append(KOMGA_CONTENT_TYPE_OPTIONS, "nosniff")
    call.response.headers.append(KOMGA_XSS_PROTECTION, "0")
    call.response.headers.append(KOMGA_FRAME_OPTIONS, framePolicy)
    KOMGA_CORS_VARY_HEADERS.forEach { value ->
      call.response.headers.append(HttpHeaders.Vary, value)
    }
    proceed()
  }
}

internal fun String.komgaFramePolicyOrNull(): String? {
  val servletPath = komgaSecurityServletPathOrNull() ?: return null
  return if (KOMGA_DENY_FRAME_PREFIXES.any(servletPath::startsWithPathSegment)) {
    "DENY"
  } else {
    "SAMEORIGIN"
  }
}

private fun String.komgaSecurityServletPathOrNull(): String? =
  KOMGA_SECURITY_PREFIXES
    .mapNotNull { prefix ->
      indexOf(prefix)
        .takeIf { it >= 0 }
        ?.takeIf { index ->
          val suffixIndex = index + prefix.length
          suffixIndex == length || get(suffixIndex) == '/'
        }
    }.minOrNull()
    ?.let(::substring)

private fun String.startsWithPathSegment(prefix: String): Boolean =
  this == prefix || startsWith("$prefix/")

private const val KOMGA_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
private const val KOMGA_XSS_PROTECTION = "X-XSS-Protection"
private const val KOMGA_FRAME_OPTIONS = "X-Frame-Options"
private val KOMGA_CORS_VARY_HEADERS =
  listOf(
    "Origin",
    "Access-Control-Request-Method",
    "Access-Control-Request-Headers",
  )
private val KOMGA_DENY_FRAME_PREFIXES = listOf("/kobo", "/koreader")
private val KOMGA_SECURITY_PREFIXES =
  listOf(
    "/api",
    "/opds",
    "/sse",
    "/oauth2/authorization",
    "/login/oauth2/code",
    "/actuator",
    *KOMGA_DENY_FRAME_PREFIXES.toTypedArray(),
  )
