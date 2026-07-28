package io.xoboro.compatibility.komga.api

import io.ktor.http.Cookie
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.util.AttributeKey
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.UserPasswordCredential
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.AuthenticationRequestDetails
import io.xoboro.core.application.RememberMeTokenService
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserRole
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Application.installKomgaBasicAuthentication(
  users: UserLifecycle,
  apiKeys: ApiKeyLifecycle? = null,
  authenticationActivities: AuthenticationActivityLifecycle? = null,
  sessions: UserSessionLifecycle? = null,
  rememberMe: RememberMeTokenService? = null,
) {
  install(Authentication) {
    provider(KOMGA_BASIC_AUTHENTICATION) {
      authenticate { context ->
        val credentials = context.call.basicCredentialsOrNull()
        val principal = credentials?.toPrincipalOrNull(users)
        when {
          principal != null -> {
            authenticationActivities?.recordSuccess(
              user = principal.user,
              source = AUTHENTICATION_SOURCE_PASSWORD,
              details = context.call.authenticationRequestDetails(),
            )
            sessions?.let { context.call.issueSession(principal.user, it) }
            context.call.issueRememberMeIfRequested(principal.user, rememberMe)
            context.principal(KOMGA_BASIC_AUTHENTICATION, principal)
          }
          else -> {
            credentials?.let {
              authenticationActivities?.recordFailure(
                source = AUTHENTICATION_SOURCE_PASSWORD,
                details = context.call.authenticationRequestDetails(),
                error = BAD_CREDENTIALS_ERROR,
                user = users.findByEmailIgnoreCaseOrNull(it.name),
                email = it.name,
              )
            }
            context.challenge(
              KOMGA_BASIC_AUTHENTICATION,
              if (credentials == null) {
                AuthenticationFailedCause.NoCredentials
              } else {
                AuthenticationFailedCause.InvalidCredentials
              },
            ) { challenge, call ->
              call.respondKomgaAuthenticationChallenge()
              challenge.complete()
            }
          }
        }
      }
    }
    provider(KOMGA_API_KEY_AUTHENTICATION) {
      authenticate { context ->
        val rawToken = context.call.request.header(KOMGA_API_KEY_HEADER)
        when {
          rawToken == null ->
            context.error(KOMGA_API_KEY_AUTHENTICATION, AuthenticationFailedCause.NoCredentials)
          else -> {
            val principal = apiKeys?.authenticate(rawToken)
            if (principal == null) {
              authenticationActivities?.recordFailure(
                source = AUTHENTICATION_SOURCE_API_KEY,
                details = context.call.authenticationRequestDetails(),
                error = BAD_CREDENTIALS_ERROR,
                apiKeyFingerprint = apiKeys?.fingerprint(rawToken),
              )
              context.error(
                KOMGA_API_KEY_AUTHENTICATION,
                AuthenticationFailedCause.InvalidCredentials,
              )
            } else {
              authenticationActivities?.recordSuccess(
                user = principal.user,
                source = AUTHENTICATION_SOURCE_API_KEY,
                details = context.call.authenticationRequestDetails(),
                apiKey = principal.apiKey,
              )
              sessions?.let { context.call.issueSession(principal.user, it) }
              context.call.issueRememberMeIfRequested(
                principal.user,
                rememberMe,
              )
              context.principal(
                KOMGA_API_KEY_AUTHENTICATION,
                KomgaPrincipal(principal.user, principal.apiKey),
              )
            }
          }
        }
      }
    }
    provider(KOMGA_SESSION_AUTHENTICATION) {
      authenticate { context ->
        val rawToken = context.call.sessionTokenOrNull()
        when {
          rawToken == null ->
            context.error(KOMGA_SESSION_AUTHENTICATION, AuthenticationFailedCause.NoCredentials)
          else -> {
            val user = sessions?.authenticate(rawToken)
            if (user == null) {
              context.error(
                KOMGA_SESSION_AUTHENTICATION,
                AuthenticationFailedCause.InvalidCredentials,
              )
            } else {
              context.principal(KOMGA_SESSION_AUTHENTICATION, KomgaPrincipal(user))
            }
          }
        }
      }
    }
    provider(KOMGA_REMEMBER_ME_AUTHENTICATION) {
      authenticate { context ->
        val rawToken = context.call.request.cookies[KOMGA_REMEMBER_ME_COOKIE]
        when {
          rawToken == null ->
            context.error(
              KOMGA_REMEMBER_ME_AUTHENTICATION,
              AuthenticationFailedCause.NoCredentials,
            )
          else -> {
            val user = rememberMe?.authenticate(rawToken)
            if (user == null) {
              context.call.expireRememberMeCookie()
              context.error(
                KOMGA_REMEMBER_ME_AUTHENTICATION,
                AuthenticationFailedCause.InvalidCredentials,
              )
            } else {
              authenticationActivities?.recordSuccess(
                user = user,
                source = AUTHENTICATION_SOURCE_REMEMBER_ME,
                details = context.call.authenticationRequestDetails(),
              )
              sessions?.let { context.call.issueSession(user, it) }
              context.principal(
                KOMGA_REMEMBER_ME_AUTHENTICATION,
                KomgaPrincipal(user),
              )
            }
          }
        }
      }
    }
    provider(KOMGA_KOREADER_AUTHENTICATION) {
      authenticate { context ->
        val rawToken = context.call.request.header(KOMGA_KOREADER_AUTHENTICATION_HEADER)
        val principal = rawToken?.let { apiKeys?.authenticate(it) }
        if (principal == null || UserRole.KOREADER_SYNC !in principal.user.roles) {
          context.challenge(
            KOMGA_KOREADER_AUTHENTICATION,
            AuthenticationFailedCause.InvalidCredentials,
          ) { challenge, call ->
            call.respond(HttpStatusCode.Forbidden)
            challenge.complete()
          }
        } else {
          context.principal(
            KOMGA_KOREADER_AUTHENTICATION,
            KomgaPrincipal(principal.user, principal.apiKey),
          )
        }
      }
    }
  }
}

private fun ApplicationCall.basicCredentialsOrNull(): UserPasswordCredential? {
  val authorization = request.header(HttpHeaders.Authorization) ?: return null
  val parts = authorization.split(' ', limit = 2)
  if (parts.size != 2 || !parts[0].equals("Basic", ignoreCase = true)) return null
  val decoded =
    runCatching {
      String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8)
    }.getOrNull() ?: return null
  val separator = decoded.indexOf(':')
  if (separator < 0) return null
  return UserPasswordCredential(
    name = decoded.substring(0, separator),
    password = decoded.substring(separator + 1),
  )
}

private suspend fun ApplicationCall.respondKomgaAuthenticationChallenge() {
  response.header(HttpHeaders.WWWAuthenticate, """Basic realm="$KOMGA_BASIC_REALM"""")
  if (request.path().contains("/opds/v2/")) {
    val authenticationUrl = opdsUrl("/opds/v2/auth")
    response.header(
      HttpHeaders.Link,
      """<$authenticationUrl>; rel="$OPDS_AUTH_DOCUMENT_REL"; type="$OPDS_AUTH_MEDIA_TYPE"""",
    )
    respondText(
      text = KOMGA_AUTH_JSON.encodeToString(opdsAuthenticationDocument()),
      contentType = ContentType.parse("$OPDS_AUTH_MEDIA_TYPE;charset=UTF-8"),
      status = HttpStatusCode.Unauthorized,
    )
  } else {
    respondError(HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized.description)
  }
}

private fun ApplicationCall.issueRememberMeIfRequested(
  user: User,
  rememberMe: RememberMeTokenService?,
) {
  if (request.queryParameters["remember-me"]?.toBooleanStrictOrNull() != true) return
  if (rememberMe == null) return
  response.cookies.append(
    Cookie(
      name = KOMGA_REMEMBER_ME_COOKIE,
      value = rememberMe.issue(user),
      path = "/",
      maxAge = rememberMe.maxAgeSeconds(),
      httpOnly = true,
      secure = request.origin.scheme == "https",
      extensions = mapOf("SameSite" to "Lax"),
    ),
  )
}

internal fun ApplicationCall.expireRememberMeCookie() {
  response.cookies.append(
    Cookie(
      name = KOMGA_REMEMBER_ME_COOKIE,
      value = "",
      path = "/",
      maxAge = 0,
      httpOnly = true,
      secure = request.origin.scheme == "https",
      extensions = mapOf("SameSite" to "Lax"),
    ),
  )
}

internal fun ApplicationCall.sessionTokenOrNull(): String? =
  if (request.headers.contains(KOMGA_SESSION_HEADER)) {
    request.header(KOMGA_SESSION_HEADER).orEmpty()
  } else {
    request.cookies[KOMGA_SESSION_COOKIE]
  }

internal fun ApplicationCall.issueSession(
  user: User,
  sessions: UserSessionLifecycle,
): String {
  val token = sessions.create(user).plainToken
  attributes.put(ISSUED_SESSION_TOKEN, token)
  if (request.headers.contains(KOMGA_SESSION_HEADER)) {
    response.headers.append(KOMGA_SESSION_HEADER, token)
  } else {
    appendSessionCookie(token)
  }
  return token
}

internal fun ApplicationCall.issuedSessionTokenOrNull(): String? =
  attributes.getOrNull(ISSUED_SESSION_TOKEN)

internal fun ApplicationCall.appendSessionCookie(token: String) {
  response.cookies.append(
    Cookie(
      name = KOMGA_SESSION_COOKIE,
      value = token,
      path = "/",
      httpOnly = true,
      secure = request.origin.scheme == "https",
      extensions = mapOf("SameSite" to "Lax"),
    ),
  )
}

private fun ApplicationCall.authenticationRequestDetails(): AuthenticationRequestDetails =
  AuthenticationRequestDetails(
    ip = request.origin.remoteHost,
    userAgent = request.header(HttpHeaders.UserAgent),
  )

data class KomgaPrincipal(
  val user: User,
  val apiKey: ApiKey? = null,
)

private fun UserPasswordCredential.toPrincipalOrNull(users: UserLifecycle): KomgaPrincipal? =
  users.authenticate(
    email = name,
    rawPassword = password,
  )?.let(::KomgaPrincipal)

const val KOMGA_BASIC_AUTHENTICATION: String = "komga-basic"
const val KOMGA_API_KEY_AUTHENTICATION: String = "komga-api-key"
const val KOMGA_API_KEY_HEADER: String = "X-API-Key"
const val KOMGA_BASIC_REALM: String = "Realm"
const val KOMGA_SESSION_AUTHENTICATION: String = "komga-session"
const val KOMGA_SESSION_COOKIE: String = "KOMGA-SESSION"
const val KOMGA_SESSION_HEADER: String = "X-Auth-Token"
const val KOMGA_REMEMBER_ME_AUTHENTICATION: String = "komga-remember-me"
const val KOMGA_REMEMBER_ME_COOKIE: String = "komga-remember-me"
const val KOMGA_KOREADER_AUTHENTICATION: String = "komga-koreader"
const val KOMGA_KOREADER_AUTHENTICATION_HEADER: String = "X-Auth-User"
const val AUTHENTICATION_SOURCE_API_KEY: String = "ApiKey"
const val AUTHENTICATION_SOURCE_PASSWORD: String = "Password"
const val AUTHENTICATION_SOURCE_REMEMBER_ME: String = "RememberMe"
private const val BAD_CREDENTIALS_ERROR: String = "Bad credentials"
private const val OPDS_AUTH_DOCUMENT_REL: String = "http://opds-spec.org/auth/document"
private val KOMGA_AUTH_JSON =
  Json {
    explicitNulls = false
    encodeDefaults = false
  }
private val ISSUED_SESSION_TOKEN = AttributeKey<String>("xoboro-issued-session-token")
