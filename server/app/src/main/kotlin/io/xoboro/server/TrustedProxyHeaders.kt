package io.xoboro.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respond
import java.util.Locale

private val FORWARDED_HEADER_NAMES =
  setOf(
    HttpHeaders.Forwarded,
    HttpHeaders.XForwardedFor,
    HttpHeaders.XForwardedHost,
    "X-Forwarded-Server",
    HttpHeaders.XForwardedProto,
    "X-Forwarded-Protocol",
    HttpHeaders.XForwardedPort,
    "X-Forwarded-By",
    "X-Forwarded-SSL",
    "Front-End-Https",
  )

internal fun Application.installTrustedProxyHeaders(trustedProxyHosts: Set<String>) {
  val normalizedTrustedHosts = trustedProxyHosts.mapTo(linkedSetOf(), ::normalizeProxyHost)
  intercept(ApplicationCallPipeline.Setup) {
    val hasForwardedHeader =
      FORWARDED_HEADER_NAMES.any { header -> context.request.headers[header] != null }
    val physicalPeer = normalizeProxyHost(context.request.local.remoteHost)
    if (hasForwardedHeader && physicalPeer !in normalizedTrustedHosts) {
      context.respond(
        status = HttpStatusCode.BadRequest,
        message =
          ErrorResponse(
            code = "untrusted_forwarded_headers",
            message = "Forwarded headers are accepted only from a trusted proxy",
          ),
      )
      finish()
      return@intercept
    }
  }
  if (normalizedTrustedHosts.isEmpty()) return
  val knownProxies = normalizedTrustedHosts.toList()
  install(ForwardedHeaders) {
    skipKnownProxies(knownProxies)
  }
  install(XForwardedHeaders) {
    skipKnownProxies(knownProxies)
  }
}

private fun normalizeProxyHost(host: String): String =
  host
    .trim()
    .removePrefix("[")
    .removeSuffix("]")
    .lowercase(Locale.ROOT)
