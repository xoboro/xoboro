package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import kotlinx.serialization.Serializable

fun Route.xoboroNativeUserAdminRoutes(users: UserLifecycle) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      route("/users") {
        get {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondUserAdministrationForbidden()
            return@get
          }
          call.respond(users.findAll().map(User::toNativeUserAdministrationResponse))
        }
        post {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondUserAdministrationForbidden()
            return@post
          }
          val request = call.receive<XoboroUserCreationRequest>()
          val created =
            try {
              users.createUser(
                email = request.email,
                rawPassword = request.password,
                roles = request.roles.toNativeUserRoles(),
                sharedLibraryIds = request.sharedLibraryIds.toNativeLibraryIds(),
                sharesAllLibraries = request.sharesAllLibraries,
                restrictions = request.restrictions.toDomain(),
              )
            } catch (_: UserEmailAlreadyExistsException) {
              call.respond(
                HttpStatusCode.Conflict,
                XoboroApiError(
                  "user_email_already_exists",
                  "A user with this email already exists",
                ),
              )
              return@post
            } catch (_: IllegalArgumentException) {
              call.respondInvalidUserRequest()
              return@post
            }
          call.respond(HttpStatusCode.Created, created.toNativeUserAdministrationResponse())
        }
        put("/{userId}") {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondUserAdministrationForbidden()
            return@put
          }
          val userId = UserId(call.requiredParameter("userId"))
          val existing = users.findByIdOrNull(userId)
          if (existing == null) {
            call.respondNativeNotFound("user_not_found", "User was not found")
            return@put
          }
          val request = call.receive<XoboroUserUpdateRequest>()
          // Parsed before the last-administrator check so an unknown role is reported as a bad
          // request instead of escaping the handler as 500 through the status pages.
          val updatedRoles =
            try {
              request.roles.toNativeUserRoles()
            } catch (_: IllegalArgumentException) {
              call.respondInvalidUserRequest()
              return@put
            }
          if (
            existing.isAdmin &&
            UserRole.ADMIN !in updatedRoles &&
            users.findAll().count(User::isAdmin) == 1
          ) {
            call.respondLastAdministratorProtected()
            return@put
          }
          val updated =
            try {
              users.updateUser(
                existing.copy(
                  roles = updatedRoles,
                  sharedLibraryIds = request.sharedLibraryIds.toNativeLibraryIds(),
                  sharesAllLibraries = request.sharesAllLibraries,
                  restrictions = request.restrictions.toDomain(),
                ),
              )
            } catch (_: IllegalArgumentException) {
              call.respondInvalidUserRequest()
              return@put
            }
          call.respond(updated.toNativeUserAdministrationResponse())
        }
        put("/{userId}/password") {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondUserAdministrationForbidden()
            return@put
          }
          val userId = UserId(call.requiredParameter("userId"))
          if (users.findByIdOrNull(userId) == null) {
            call.respondNativeNotFound("user_not_found", "User was not found")
            return@put
          }
          val request = call.receive<XoboroAdministratorPasswordUpdateRequest>()
          try {
            users.updatePassword(userId, request.newPassword)
          } catch (_: IllegalArgumentException) {
            call.respondInvalidUserRequest()
            return@put
          }
          call.respond(HttpStatusCode.NoContent)
        }
        delete("/{userId}") {
          val caller = call.nativeUser()
          if (!caller.isAdmin) {
            call.respondUserAdministrationForbidden()
            return@delete
          }
          val userId = UserId(call.requiredParameter("userId"))
          if (userId == caller.id) {
            call.respond(
              HttpStatusCode.Conflict,
              XoboroApiError(
                "cannot_delete_own_account",
                "Administrators cannot delete their own account",
              ),
            )
            return@delete
          }
          val existing = users.findByIdOrNull(userId)
          if (existing == null) {
            call.respondNativeNotFound("user_not_found", "User was not found")
            return@delete
          }
          if (existing.isAdmin && users.findAll().count(User::isAdmin) == 1) {
            call.respondLastAdministratorProtected()
            return@delete
          }
          check(users.deleteUser(userId))
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
  }
}

// Unknown values are rejected rather than dropped. The compatibility surface discards them,
// which on a privilege-granting endpoint means a mistyped role silently creates a user with
// fewer rights than the administrator asked for - or none at all - with a 200 response.
private fun List<String>.toNativeUserRoles(): Set<UserRole> =
  mapTo(linkedSetOf()) { value ->
    UserRole.entries.firstOrNull { it.name == value }
      ?: throw IllegalArgumentException("Unknown user role: $value")
  }

private fun List<String>.toNativeLibraryIds(): Set<LibraryId> =
  mapTo(linkedSetOf()) { value ->
    runCatching { LibraryId(value) }
      .getOrElse { throw IllegalArgumentException("Invalid library identifier: $value") }
  }

private fun XoboroContentRestrictionsRequest.toDomain(): ContentRestrictions =
  ContentRestrictions(
    ageRestriction =
      ageRestriction?.let {
        AgeRestriction(
          age = it.age,
          mode = RestrictionMode.valueOf(it.mode),
        )
      },
    labelsAllow = labelsAllow.toSet(),
    labelsExclude = labelsExclude.toSet(),
  )

private fun User.toNativeUserAdministrationResponse(): XoboroUserAdministrationResponse =
  XoboroUserAdministrationResponse(
    id = id.value,
    email = email,
    roles = roles.map { it.name }.sorted(),
    sharedLibraryIds = sharedLibraryIds.map { it.value }.sorted(),
    sharesAllLibraries = sharesAllLibraries,
    restrictions =
      XoboroContentRestrictionsResponse(
        ageRestriction =
          restrictions.ageRestriction?.let {
            XoboroAgeRestrictionResponse(it.age, it.mode.name)
          },
        labelsAllow = restrictions.labelsAllow.sorted(),
        labelsExclude = restrictions.labelsExclude.sorted(),
      ),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

private suspend fun ApplicationCall.respondUserAdministrationForbidden() {
  respond(
    HttpStatusCode.Forbidden,
    XoboroApiError(
      "user_administration_forbidden",
      "User administration permission is required",
    ),
  )
}

private suspend fun ApplicationCall.respondLastAdministratorProtected() {
  respond(
    HttpStatusCode.Conflict,
    XoboroApiError(
      "last_administrator_protected",
      "The last administrator cannot be deleted or demoted",
    ),
  )
}

private suspend fun ApplicationCall.respondInvalidUserRequest() {
  respond(
    HttpStatusCode.BadRequest,
    XoboroApiError("invalid_request", "User request is invalid"),
  )
}

@Serializable
data class XoboroUserCreationRequest(
  val email: String,
  val password: String,
  val roles: List<String> =
    listOf(
      UserRole.FILE_DOWNLOAD.name,
      UserRole.PAGE_STREAMING.name,
    ),
  val sharedLibraryIds: List<String> = emptyList(),
  val sharesAllLibraries: Boolean = true,
  val restrictions: XoboroContentRestrictionsRequest = XoboroContentRestrictionsRequest(),
)

@Serializable
data class XoboroUserUpdateRequest(
  val roles: List<String>,
  val sharedLibraryIds: List<String>,
  val sharesAllLibraries: Boolean,
  val restrictions: XoboroContentRestrictionsRequest,
)

@Serializable
data class XoboroAdministratorPasswordUpdateRequest(
  val newPassword: String,
)

@Serializable
data class XoboroContentRestrictionsRequest(
  val ageRestriction: XoboroAgeRestrictionRequest? = null,
  val labelsAllow: List<String> = emptyList(),
  val labelsExclude: List<String> = emptyList(),
)

@Serializable
data class XoboroAgeRestrictionRequest(
  val age: Int,
  val mode: String,
)

@Serializable
data class XoboroUserAdministrationResponse(
  val id: String,
  val email: String,
  val roles: List<String>,
  val sharedLibraryIds: List<String>,
  val sharesAllLibraries: Boolean,
  val restrictions: XoboroContentRestrictionsResponse,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
)

@Serializable
data class XoboroContentRestrictionsResponse(
  val ageRestriction: XoboroAgeRestrictionResponse?,
  val labelsAllow: List<String>,
  val labelsExclude: List<String>,
)

@Serializable
data class XoboroAgeRestrictionResponse(
  val age: Int,
  val mode: String,
)
