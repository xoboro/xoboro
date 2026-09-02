package io.xoboro.server.api

import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitConfig
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.xoboro.core.application.RememberMeTokenService
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.User
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

fun AuthenticationConfig.configureXoboroNativeAuthentication(
  sessions: UserSessionLifecycle,
  rememberMe: RememberMeTokenService? = null,
) {
  bearer(XOBORO_BEARER_AUTHENTICATION) {
    realm = XOBORO_AUTHENTICATION_REALM
    authenticate { credential ->
      sessions
        .authenticate(credential.token)
        ?.let { XoboroPrincipal(it, credential.token, SessionTransport.BEARER) }
    }
  }
  provider(XOBORO_COOKIE_AUTHENTICATION) {
    authenticate { context ->
      val sessionToken = context.call.request.cookies[XOBORO_SESSION_COOKIE]
      val sessionUser = sessionToken?.let(sessions::authenticate)
      if (sessionUser != null) {
        context.principal(
          XOBORO_COOKIE_AUTHENTICATION,
          XoboroPrincipal(sessionUser, requireNotNull(sessionToken), SessionTransport.COOKIE),
        )
      } else {
        val rememberedToken = context.call.request.cookies[XOBORO_REMEMBER_ME_COOKIE]
        val rememberedUser = rememberedToken?.let { rememberMe?.authenticate(it) }
        val restored =
          rememberedUser?.let { user ->
            sessions.create(user)?.also { created ->
              context.call.appendXoboroSessionCookie(created.plainToken)
            }
          }
        if (rememberedToken != null && rememberedUser == null) {
          context.call.expireXoboroRememberMeCookie()
        }
        when {
          restored != null ->
            context.principal(
              XOBORO_COOKIE_AUTHENTICATION,
              XoboroPrincipal(rememberedUser, restored.plainToken, SessionTransport.COOKIE),
            )
          sessionToken == null && rememberedToken == null ->
            context.error(
              XOBORO_COOKIE_AUTHENTICATION,
              AuthenticationFailedCause.NoCredentials,
            )
          else ->
            context.error(
              XOBORO_COOKIE_AUTHENTICATION,
              AuthenticationFailedCause.InvalidCredentials,
            )
        }
      }
    }
  }
}

fun RateLimitConfig.configureXoboroNativeRateLimits(
  loginLimit: Int = DEFAULT_LOGIN_LIMIT,
  refillPeriod: Duration = DEFAULT_LOGIN_REFILL_PERIOD,
) {
  require(loginLimit > 0) { "Native login rate limit must be positive" }
  require(refillPeriod.isPositive()) { "Native login refill period must be positive" }
  register(XOBORO_LOGIN_RATE_LIMIT) {
    requestKey { call -> call.request.origin.remoteHost }
    rateLimiter(limit = loginLimit, refillPeriod = refillPeriod)
  }
}

data class XoboroPrincipal(
  val user: User,
  val plainToken: String,
  val transport: SessionTransport,
)

const val XOBORO_API_PREFIX: String = "/api/xoboro/v1"
const val XOBORO_SESSION_COOKIE: String = "XOBORO-SESSION"
const val XOBORO_REMEMBER_ME_COOKIE: String = "XOBORO-REMEMBER-ME"
const val XOBORO_BEARER_AUTHENTICATION: String = "xoboro-bearer"
const val XOBORO_COOKIE_AUTHENTICATION: String = "xoboro-cookie"
internal val XOBORO_LOGIN_RATE_LIMIT = RateLimitName("xoboro-login")
private const val XOBORO_AUTHENTICATION_REALM = "Xoboro"
private const val DEFAULT_LOGIN_LIMIT = 10
private val DEFAULT_LOGIN_REFILL_PERIOD = 1.minutes
