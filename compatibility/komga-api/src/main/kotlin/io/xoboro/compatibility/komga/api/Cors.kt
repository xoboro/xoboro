package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondBytes

fun Application.installKomgaCors(allowedOrigins: Set<String>) {
  if (allowedOrigins.isEmpty()) return
  intercept(ApplicationCallPipeline.Call) {
    val call = context
    if (call.request.path().komgaFramePolicyOrNull() == null) {
      return@intercept proceed()
    }
    val origin =
      call.request.headers[HttpHeaders.Origin]
        ?: return@intercept proceed()
    val requestedMethod = call.request.headers[HttpHeaders.AccessControlRequestMethod]
    val preflight = call.request.httpMethod == HttpMethod.Options && requestedMethod != null
    val actualMethod = if (preflight) requestedMethod.uppercase() else call.request.httpMethod.value
    if (origin !in allowedOrigins || actualMethod !in KOMGA_CORS_METHODS) {
      call.respondKomgaInvalidCors()
      return@intercept
    }

    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
    call.response.headers.append(
      HttpHeaders.AccessControlExposeHeaders,
      KOMGA_CORS_EXPOSED_HEADERS,
    )
    call.response.headers.append(HttpHeaders.AccessControlAllowCredentials, "true")
    if (!preflight) {
      proceed()
      return@intercept
    }

    call.response.headers.append(
      HttpHeaders.AccessControlAllowMethods,
      KOMGA_CORS_METHODS.joinToString(","),
    )
    call.request.headers[HttpHeaders.AccessControlRequestHeaders]?.let { requested ->
      call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, requested)
    }
    call.response.headers.append(HttpHeaders.AccessControlMaxAge, KOMGA_CORS_MAX_AGE_SECONDS)
    call.respondBytes(ByteArray(0), status = HttpStatusCode.OK)
  }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondKomgaInvalidCors() {
  respondBytes(
    "Invalid CORS request".encodeToByteArray(),
    status = HttpStatusCode.Forbidden,
  )
}

private const val KOMGA_CORS_EXPOSED_HEADERS = "Content-Disposition, X-Auth-Token"
private const val KOMGA_CORS_MAX_AGE_SECONDS = "1800"
private val KOMGA_CORS_METHODS =
  listOf(
    "GET",
    "HEAD",
    "POST",
    "PUT",
    "PATCH",
    "DELETE",
    "OPTIONS",
    "TRACE",
  )
