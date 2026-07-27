package io.xoboro.compatibility.komga.api

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.xoboro.core.application.UserSessionLifecycle

fun Route.komgaSessionRoutes(sessions: UserSessionLifecycle) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    get("/api/v1/login/set-cookie") {
      val token = call.issuedSessionTokenOrNull() ?: call.sessionTokenOrNull()
      if (token == null || sessions.authenticate(token) == null) {
        call.respond(HttpStatusCode.Unauthorized)
        return@get
      }
      call.appendSessionCookie(token)
      call.respond(HttpStatusCode.NoContent)
    }
  }

  get("/api/logout") {
    call.expireSession(sessions)
    call.respond(HttpStatusCode.NoContent)
  }
  post("/api/logout") {
    call.expireSession(sessions)
    call.respond(HttpStatusCode.NoContent)
  }
}

private fun io.ktor.server.application.ApplicationCall.expireSession(
  sessions: UserSessionLifecycle,
) {
  sessionTokenOrNull()?.let(sessions::invalidate)
  response.cookies.append(
    Cookie(
      name = KOMGA_SESSION_COOKIE,
      value = "",
      path = "/",
      maxAge = 0,
      httpOnly = true,
      secure = request.local.scheme == "https",
      extensions = mapOf("SameSite" to "Lax"),
    ),
  )
  expireRememberMeCookie()
  if (request.headers.contains(KOMGA_SESSION_HEADER)) {
    response.headers.append(KOMGA_SESSION_HEADER, "")
  }
}
