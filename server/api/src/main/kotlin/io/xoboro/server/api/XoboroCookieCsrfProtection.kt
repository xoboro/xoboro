package io.xoboro.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import java.net.URI
import java.util.Locale

internal val XoboroCookieCsrfProtection =
  createRouteScopedPlugin("XoboroCookieCsrfProtection") {
    on(AuthenticationChecked) { call ->
      val principal = call.principal<XoboroPrincipal>()
      if (
        call.request.httpMethod in UNSAFE_METHODS &&
        principal?.transport == SessionTransport.COOKIE &&
        !call.hasTrustedMutationOrigin()
      ) {
        throw CrossSiteRequestRejectedException()
      }
    }
  }

internal suspend fun ApplicationCall.respondCsrfRejected() {
  respond(
    HttpStatusCode.Forbidden,
    XoboroApiError(
      code = CrossSiteRequestRejectedException.CODE,
      message = CrossSiteRequestRejectedException.MESSAGE,
    ),
  )
}

internal fun ApplicationCall.hasTrustedMutationOrigin(): Boolean {
  val fetchSite = request.header(SEC_FETCH_SITE)?.lowercase(Locale.ROOT)
  if (fetchSite != null && fetchSite != SAME_ORIGIN) return false
  val serializedOrigin =
    request.header(HttpHeaders.Origin)
      ?: return fetchSite == SAME_ORIGIN
  val suppliedOrigin = serializedOrigin.toOriginOrNull() ?: return false
  val requestOrigin =
    RequestOrigin(
      scheme = request.origin.scheme.lowercase(Locale.ROOT),
      host = request.origin.serverHost.lowercase(Locale.ROOT),
      port = request.origin.serverPort,
    )
  return suppliedOrigin == requestOrigin
}

private fun String.toOriginOrNull(): RequestOrigin? =
  runCatching {
    val uri = URI(this)
    val scheme = requireNotNull(uri.scheme).lowercase(Locale.ROOT)
    val host = requireNotNull(uri.host).lowercase(Locale.ROOT)
    require(uri.rawUserInfo == null)
    require(uri.rawQuery == null)
    require(uri.rawFragment == null)
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    RequestOrigin(
      scheme = scheme,
      host = host,
      port = uri.port.takeIf { it >= 0 } ?: scheme.defaultPort(),
    )
  }.getOrNull()

private fun String.defaultPort(): Int =
  when (this) {
    "http" -> 80
    "https" -> 443
    else -> -1
  }

private data class RequestOrigin(
  val scheme: String,
  val host: String,
  val port: Int,
)

class CrossSiteRequestRejectedException :
  RuntimeException(MESSAGE) {
  companion object {
    const val CODE = "cross_site_request_rejected"
    const val MESSAGE = "Cookie-authenticated mutations must originate from this server"
  }
}

private const val SEC_FETCH_SITE = "Sec-Fetch-Site"
private const val SAME_ORIGIN = "same-origin"
private val UNSAFE_METHODS =
  setOf(
    HttpMethod.Post,
    HttpMethod.Put,
    HttpMethod.Patch,
    HttpMethod.Delete,
  )
