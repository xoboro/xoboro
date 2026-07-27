package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.UserPasswordCredential
import io.ktor.server.auth.basic
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.AuthenticationRequestDetails
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.User

fun Application.installKomgaBasicAuthentication(
  users: UserLifecycle,
  apiKeys: ApiKeyLifecycle? = null,
  authenticationActivities: AuthenticationActivityLifecycle? = null,
) {
  install(Authentication) {
    basic(KOMGA_BASIC_AUTHENTICATION) {
      realm = KOMGA_BASIC_REALM
      validate { credentials ->
        val principal = credentials.toPrincipalOrNull(users)
        if (principal == null) {
          authenticationActivities?.recordFailure(
            source = AUTHENTICATION_SOURCE_PASSWORD,
            details = authenticationRequestDetails(),
            error = BAD_CREDENTIALS_ERROR,
            user = users.findByEmailIgnoreCaseOrNull(credentials.name),
            email = credentials.name,
          )
        } else {
          authenticationActivities?.recordSuccess(
            user = principal.user,
            source = AUTHENTICATION_SOURCE_PASSWORD,
            details = authenticationRequestDetails(),
          )
        }
        principal
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
              context.challenge(
                KOMGA_API_KEY_AUTHENTICATION,
                AuthenticationFailedCause.InvalidCredentials,
              ) { challenge, call ->
                call.respond(HttpStatusCode.Unauthorized)
                challenge.complete()
              }
            } else {
              authenticationActivities?.recordSuccess(
                user = principal.user,
                source = AUTHENTICATION_SOURCE_API_KEY,
                details = context.call.authenticationRequestDetails(),
                apiKey = principal.apiKey,
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
  }
}

private fun ApplicationCall.authenticationRequestDetails(): AuthenticationRequestDetails =
  AuthenticationRequestDetails(
    ip = request.local.remoteHost,
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
const val AUTHENTICATION_SOURCE_API_KEY: String = "ApiKey"
const val AUTHENTICATION_SOURCE_PASSWORD: String = "Password"
private const val BAD_CREDENTIALS_ERROR: String = "Bad credentials"
