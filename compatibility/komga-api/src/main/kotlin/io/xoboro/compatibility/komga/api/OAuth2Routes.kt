package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Cookie
import io.ktor.http.encodeURLQueryComponent
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.AuthenticationRequestDetails
import io.xoboro.core.application.OAuth2LoginException
import io.xoboro.core.application.OAuth2LoginLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

fun Route.komgaOAuth2Routes(
  oauth2: OAuth2LoginLifecycle,
  sessions: UserSessionLifecycle,
  authenticationActivities: AuthenticationActivityLifecycle? = null,
) {
  get("/api/v1/oauth2/providers") {
    call.respond(
      oauth2.providers().map {
        OAuth2ClientDto(
          name = it.name,
          registrationId = it.registrationId,
        )
      },
    )
  }
  get("/oauth2/authorization/{registrationId}") {
    val registrationId =
      call.parameters["registrationId"]
        ?: return@get call.respond(HttpStatusCode.NotFound)
    val callbackPath =
      "${call.contextPrefix()}/login/oauth2/code/$registrationId"
    val callbackUri = call.absoluteUri(callbackPath)
    val launch =
      try {
        oauth2.begin(registrationId, callbackUri)
      } catch (failure: OAuth2LoginException) {
        return@get call.respondError(HttpStatusCode.NotFound, failure.errorCode)
      }
    call.appendOAuth2BindingCookie(launch.browserBinding)
    call.respondRedirect(launch.redirectUri)
  }
  get("/login/oauth2/code/{registrationId}") {
    val registrationId =
      call.parameters["registrationId"]
        ?: return@get call.oauth2Failure(OAuth2LoginLifecycle.UNKNOWN_REGISTRATION)
    call.request.queryParameters["error"]?.let {
      call.expireOAuth2BindingCookie()
      authenticationActivities?.recordFailure(
        source = oauth2.authenticationSource(registrationId),
        details = call.authenticationRequestDetails(),
        error = it,
      )
      return@get call.oauth2Failure(it)
    }
    val state = call.request.queryParameters["state"].orEmpty()
    val code = call.request.queryParameters["code"].orEmpty()
    val browserBinding = call.request.cookies[OAUTH2_BINDING_COOKIE].orEmpty()
    call.expireOAuth2BindingCookie()
    try {
      val user = oauth2.complete(registrationId, state, code, browserBinding)
      authenticationActivities?.recordSuccess(
        user = user,
        source = oauth2.authenticationSource(registrationId),
        details = call.authenticationRequestDetails(),
      )
      call.issueSession(user, sessions)
      call.respondRedirect("${call.contextPrefix()}/?server_redirect=Y")
    } catch (failure: OAuth2LoginException) {
      authenticationActivities?.recordFailure(
        source = oauth2.authenticationSource(registrationId),
        details = call.authenticationRequestDetails(),
        error = failure.errorCode,
      )
      call.oauth2Failure(failure.errorCode)
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      call.application.environment.log.warn("OAuth2 login failed", failure)
      authenticationActivities?.recordFailure(
        source = oauth2.authenticationSource(registrationId),
        details = call.authenticationRequestDetails(),
        error = "oauth2_login_failed",
      )
      call.oauth2Failure("oauth2_login_failed")
    }
  }
}

@Serializable
data class OAuth2ClientDto(
  val name: String,
  val registrationId: String,
)

private fun io.ktor.server.application.ApplicationCall.contextPrefix(): String {
  val marker =
    when {
      request.path().contains("/oauth2/authorization/") -> "/oauth2/authorization/"
      request.path().contains("/login/oauth2/code/") -> "/login/oauth2/code/"
      else -> return ""
    }
  return request.path().substringBefore(marker).trimEnd('/')
}

private fun io.ktor.server.application.ApplicationCall.absoluteUri(path: String): String {
  val scheme = request.local.scheme
  val host = request.local.serverHost
  val formattedHost =
    if (':' in host && !host.startsWith('[')) "[$host]" else host
  val port = request.local.serverPort
  val authority =
    if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) {
      formattedHost
    } else {
      "$formattedHost:$port"
    }
  return "$scheme://$authority$path"
}

private suspend fun io.ktor.server.application.ApplicationCall.oauth2Failure(error: String) {
  val encoded = error.encodeURLQueryComponent()
  respondRedirect("${contextPrefix()}/login?server_redirect=Y&error=$encoded")
}

private fun OAuth2LoginLifecycle.authenticationSource(registrationId: String): String =
  providers()
    .firstOrNull { it.registrationId == registrationId }
    ?.let { "OAuth2:${it.name}" }
    ?: "OAuth2:$registrationId"

private fun io.ktor.server.application.ApplicationCall.authenticationRequestDetails():
  AuthenticationRequestDetails =
  AuthenticationRequestDetails(
    ip = request.local.remoteHost,
    userAgent = request.header(HttpHeaders.UserAgent),
  )

private fun io.ktor.server.application.ApplicationCall.appendOAuth2BindingCookie(value: String) {
  response.cookies.append(
    Cookie(
      name = OAUTH2_BINDING_COOKIE,
      value = value,
      path = "${contextPrefix()}/login/oauth2/code",
      maxAge = OAUTH2_BINDING_MAX_AGE_SECONDS,
      httpOnly = true,
      secure = request.local.scheme == "https",
      extensions = mapOf("SameSite" to "Lax"),
    ),
  )
}

private fun io.ktor.server.application.ApplicationCall.expireOAuth2BindingCookie() {
  response.cookies.append(
    Cookie(
      name = OAUTH2_BINDING_COOKIE,
      value = "",
      path = "${contextPrefix()}/login/oauth2/code",
      maxAge = 0,
      httpOnly = true,
      secure = request.local.scheme == "https",
      extensions = mapOf("SameSite" to "Lax"),
    ),
  )
}

internal const val OAUTH2_BINDING_COOKIE = "XOBORO-OAUTH2"
private const val OAUTH2_BINDING_MAX_AGE_SECONDS = 10 * 60
