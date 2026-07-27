package io.xoboro.compatibility.komga.api

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserPasswordCredential
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.User

fun Application.installKomgaBasicAuthentication(users: UserLifecycle) {
  install(Authentication) {
    basic(KOMGA_BASIC_AUTHENTICATION) {
      realm = KOMGA_BASIC_REALM
      validate { credentials ->
        credentials.toPrincipalOrNull(users)
      }
    }
  }
}

fun Route.komgaAuthenticatedUserRoutes() {
  authenticate(KOMGA_BASIC_AUTHENTICATION) {
    route("/api/v2/users") {
      get("/me") {
        call.respond(requireNotNull(call.principal<KomgaPrincipal>()).user.toDto())
      }
    }
  }
}

data class KomgaPrincipal(
  val user: User,
)

private fun UserPasswordCredential.toPrincipalOrNull(users: UserLifecycle): KomgaPrincipal? =
  users.authenticate(
    email = name,
    rawPassword = password,
  )?.let(::KomgaPrincipal)

const val KOMGA_BASIC_AUTHENTICATION: String = "komga-basic"
const val KOMGA_BASIC_REALM: String = "Realm"
