package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.domain.ClientSetting
import kotlinx.serialization.Serializable

fun Route.komgaClientSettingsRoutes(settings: ClientSettingsLifecycle) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    optional = true,
  ) {
    get("/api/v1/client-settings/global/list") {
      val onlyUnauthorized = call.principal<KomgaPrincipal>() == null
      call.respond(settings.findGlobal(onlyUnauthorized).toDto())
    }
  }

  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    get("/api/v1/client-settings/user/list") {
      val user = requireNotNull(call.principal<KomgaPrincipal>()).user
      call.respond(settings.findForUser(user.id).toDto())
    }
    patch("/api/v1/client-settings/global") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      if (!principal.user.isAdmin) {
        call.respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        return@patch
      }
      val request = call.receive<Map<String, ClientSettingGlobalUpdateDto>>()
      val violations = request.globalValidationViolations()
      if (violations.isNotEmpty()) {
        call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(violations))
        return@patch
      }
      settings.saveGlobal(
        request.mapValues { (_, update) ->
          ClientSetting(update.value, update.allowUnauthorized)
        },
      )
      call.respond(HttpStatusCode.NoContent)
    }
    patch("/api/v1/client-settings/user") {
      val user = requireNotNull(call.principal<KomgaPrincipal>()).user
      val request = call.receive<Map<String, ClientSettingUserUpdateDto>>()
      val violations = request.userValidationViolations()
      if (violations.isNotEmpty()) {
        call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(violations))
        return@patch
      }
      settings.saveForUser(
        user.id,
        request.mapValues { (_, update) -> ClientSetting(update.value) },
      )
      call.respond(HttpStatusCode.NoContent)
    }
    delete("/api/v1/client-settings/global") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      if (!principal.user.isAdmin) {
        call.respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        return@delete
      }
      val keys = call.receive<Set<String>>()
      val violations = keys.keyViolations()
      if (violations.isNotEmpty()) {
        call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(violations))
        return@delete
      }
      settings.deleteGlobal(keys)
      call.respond(HttpStatusCode.NoContent)
    }
    delete("/api/v1/client-settings/user") {
      val user = requireNotNull(call.principal<KomgaPrincipal>()).user
      val keys = call.receive<Set<String>>()
      val violations = keys.keyViolations()
      if (violations.isNotEmpty()) {
        call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(violations))
        return@delete
      }
      settings.deleteForUser(user.id, keys)
      call.respond(HttpStatusCode.NoContent)
    }
  }
}

@Serializable
data class ClientSettingDto(
  val value: String,
  val allowUnauthorized: Boolean? = null,
)

@Serializable
data class ClientSettingGlobalUpdateDto(
  val value: String,
  val allowUnauthorized: Boolean,
)

@Serializable
data class ClientSettingUserUpdateDto(
  val value: String,
)

private fun Map<String, ClientSettingGlobalUpdateDto>.globalValidationViolations():
  List<ViolationDto> =
  keys.keyViolations() +
    entries
      .filter { (_, setting) -> setting.value.isBlank() }
      .map { (key) -> ViolationDto("$key.value", "must not be blank") }

private fun Map<String, ClientSettingUserUpdateDto>.userValidationViolations():
  List<ViolationDto> =
  keys.keyViolations() +
    entries
      .filter { (_, setting) -> setting.value.isBlank() }
      .map { (key) -> ViolationDto("$key.value", "must not be blank") }

private fun Set<String>.keyViolations(): List<ViolationDto> =
  filterNot(ClientSettingsLifecycle.KEY_PATTERN::matches)
    .map { key -> ViolationDto(key, "must match the client setting key pattern") }

private fun Map<String, ClientSetting>.toDto(): Map<String, ClientSettingDto> =
  mapValues { (_, setting) ->
    ClientSettingDto(
      value = setting.value,
      allowUnauthorized = setting.allowUnauthorized,
    )
  }
