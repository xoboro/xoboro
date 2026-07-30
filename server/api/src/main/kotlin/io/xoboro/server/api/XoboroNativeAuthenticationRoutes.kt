package io.xoboro.server.api

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.ServerAlreadyClaimedException
import io.xoboro.core.domain.User
import kotlinx.serialization.Serializable

fun Route.xoboroNativeAuthenticationRoutes(
  users: UserLifecycle,
  sessions: UserSessionLifecycle,
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
      call.respondSession(user, request.transport, sessions, HttpStatusCode.Created)
    }
    rateLimit(XOBORO_LOGIN_RATE_LIMIT) {
      post("/session") {
        val request = call.receive<LoginRequest>()
        if (request.transport == SessionTransport.COOKIE && !call.hasTrustedMutationOrigin()) {
          call.respondCsrfRejected()
          return@post
        }
        val user = users.authenticate(request.email, request.password)
        if (user == null) {
          call.respondNativeError(
            HttpStatusCode.Unauthorized,
            "invalid_credentials",
            "Invalid email or password",
          )
          return@post
        }
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
