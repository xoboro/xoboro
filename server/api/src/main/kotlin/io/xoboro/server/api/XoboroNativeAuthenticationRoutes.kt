package io.xoboro.server.api

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.AuthenticationRequestDetails
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.ServerAlreadyClaimedException
import io.xoboro.core.domain.User
import kotlinx.serialization.Serializable

/**
 * [activities] is optional so a deployment - or a route test - can mount the session surface without an
 * activity store. It is a recording concern, not an authentication one: a failure to write an audit row
 * must never be what stops a valid login, which is also why every call here is a side effect on a path
 * that has already decided the outcome.
 */
fun Route.xoboroNativeAuthenticationRoutes(
  users: UserLifecycle,
  sessions: UserSessionLifecycle,
  activities: AuthenticationActivityLifecycle? = null,
) {
  route(XOBORO_API_PREFIX) {
    get("/setup") {
      call.respond(SetupStatusResponse(claimed = users.isClaimed()))
    }
    post("/setup") {
      val request = call.receive<SetupRequest>()
      if (request.transport == SessionTransport.COOKIE && !call.hasTrustedMutationOrigin()) {
        call.respondCsrfRejected()
        return@post
      }
      val user =
        try {
          users.claimInitialAdministrator(request.email, request.password)
        } catch (_: ServerAlreadyClaimedException) {
          call.respond(
            HttpStatusCode.Conflict,
            XoboroApiError("server_already_claimed", "Server setup is already complete"),
          )
          return@post
        } catch (_: IllegalArgumentException) {
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_request", "Email and password must be valid"),
          )
          return@post
        }
      activities?.recordSuccess(
        user = user,
        source = XOBORO_NATIVE_AUTHENTICATION_SOURCE,
        details = call.nativeAuthenticationDetails(),
      )
      call.respondSession(user, request.transport, sessions, HttpStatusCode.Created)
    }
    rateLimit(XOBORO_LOGIN_RATE_LIMIT) {
      post("/session") {
        val request = call.receive<LoginRequest>()
        if (request.transport == SessionTransport.COOKIE && !call.hasTrustedMutationOrigin()) {
          // Recorded, and recorded as a failure: a cross-site attempt to open a session is exactly
          // the event an administrator reading this log is looking for, and it is invisible in the
          // response because the browser is the one being told no.
          activities?.recordFailure(
            source = XOBORO_NATIVE_AUTHENTICATION_SOURCE,
            details = call.nativeAuthenticationDetails(),
            error = CrossSiteRequestRejectedException.CODE,
            email = request.email,
          )
          call.respondCsrfRejected()
          return@post
        }
        val user = users.authenticate(request.email, request.password)
        if (user == null) {
          // The submitted email is recorded even though no account matched it. That is the whole
          // value of a failure row - "someone is trying this address" - and it is already what the
          // Komga-compatible surface records.
          activities?.recordFailure(
            source = XOBORO_NATIVE_AUTHENTICATION_SOURCE,
            details = call.nativeAuthenticationDetails(),
            error = "invalid_credentials",
            email = request.email,
          )
          call.respondNativeError(
            HttpStatusCode.Unauthorized,
            "invalid_credentials",
            "Invalid email or password",
          )
          return@post
        }
        activities?.recordSuccess(
          user = user,
          source = XOBORO_NATIVE_AUTHENTICATION_SOURCE,
          details = call.nativeAuthenticationDetails(),
        )
        call.respondSession(user, request.transport, sessions)
      }
    }
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      get("/session") {
        call.respond(SessionResponse(requireNotNull(call.principal<XoboroPrincipal>()).user.toDto()))
      }
      delete("/session") {
        val principal = requireNotNull(call.principal<XoboroPrincipal>())
        sessions.invalidate(principal.plainToken)
        call.expireXoboroSessionCookie()
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}

/**
 * The source name every native session event carries.
 *
 * Distinct from the Komga-compatible surface's `"Password"` on purpose: the two are different entry
 * points with different CSRF and transport rules, and collapsing them into one name would make an
 * administrator unable to tell which door was used.
 */
const val XOBORO_NATIVE_AUTHENTICATION_SOURCE: String = "XoboroSession"

private fun ApplicationCall.nativeAuthenticationDetails(): AuthenticationRequestDetails =
  AuthenticationRequestDetails(
    ip = request.origin.remoteHost,
    userAgent = request.header(HttpHeaders.UserAgent),
  )

private suspend fun ApplicationCall.respondSession(
  user: User,
  transport: SessionTransport,
  sessions: UserSessionLifecycle,
  status: HttpStatusCode = HttpStatusCode.OK,
) {
  val created = sessions.create(user)
  if (transport == SessionTransport.COOKIE) {
    appendXoboroSessionCookie(created.plainToken)
  }
  respond(
    status,
    SessionResponse(
      user = user.toDto(),
      accessToken = created.plainToken.takeIf { transport == SessionTransport.BEARER },
    ),
  )
}

private fun ApplicationCall.appendXoboroSessionCookie(token: String) {
  response.cookies.append(
    Cookie(
      name = XOBORO_SESSION_COOKIE,
      value = token,
      path = "/",
      httpOnly = true,
      secure = request.origin.scheme == "https",
      extensions = mapOf("SameSite" to "Strict"),
    ),
  )
}

private fun ApplicationCall.expireXoboroSessionCookie() {
  response.cookies.append(
    Cookie(
      name = XOBORO_SESSION_COOKIE,
      value = "",
      path = "/",
      maxAge = 0,
      httpOnly = true,
      secure = request.origin.scheme == "https",
      extensions = mapOf("SameSite" to "Strict"),
    ),
  )
}

private fun User.toDto(): UserResponse =
  UserResponse(
    id = id.value,
    email = email,
    roles = roles.map { it.name }.sorted(),
  )

@Serializable
enum class SessionTransport {
  COOKIE,
  BEARER,
}

@Serializable
data class SetupRequest(
  val email: String,
  val password: String,
  val transport: SessionTransport = SessionTransport.COOKIE,
)

@Serializable
data class LoginRequest(
  val email: String,
  val password: String,
  val transport: SessionTransport = SessionTransport.COOKIE,
)

@Serializable
data class SetupStatusResponse(
  val claimed: Boolean,
)

@Serializable
data class UserResponse(
  val id: String,
  val email: String,
  val roles: List<String>,
)

@Serializable
data class SessionResponse(
  val user: UserResponse,
  val accessToken: String? = null,
)

@Serializable
data class XoboroApiError(
  val code: String,
  val message: String,
)
