package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.ServerAlreadyClaimedException
import io.xoboro.core.domain.User
import kotlinx.serialization.Serializable

fun Route.komgaClaimRoutes(users: UserLifecycle) {
  route("/api/v1/claim") {
    get {
      call.respond(ClaimStatusDto(isClaimed = users.isClaimed()))
    }
    post {
      val email = call.request.header(EMAIL_HEADER)
      val password = call.request.header(PASSWORD_HEADER)
      val violations = credentialViolations(email, password)
      if (violations.isNotEmpty()) {
        call.respond(
          status = HttpStatusCode.BadRequest,
          message = ValidationErrorResponse(violations),
        )
        return@post
      }

      try {
        val claimed =
          users.claimInitialAdministrator(
            email = requireNotNull(email),
            rawPassword = requireNotNull(password),
          )
        call.respond(claimed.toDto())
      } catch (_: ServerAlreadyClaimedException) {
        call.respond(
          status = HttpStatusCode.BadRequest,
          message =
            KomgaErrorResponse(
              status = HttpStatusCode.BadRequest.value,
              error = HttpStatusCode.BadRequest.description,
              message = "This server has already been claimed",
              path = "/api/v1/claim",
            ),
        )
      }
    }
  }
}

@Serializable
data class ClaimStatusDto(
  val isClaimed: Boolean,
)

@Serializable
data class UserDto(
  val id: String,
  val email: String,
  val roles: Set<String>,
  val sharedAllLibraries: Boolean,
  val sharedLibrariesIds: Set<String>,
  val labelsAllow: Set<String>,
  val labelsExclude: Set<String>,
  val ageRestriction: AgeRestrictionDto? = null,
)

@Serializable
data class AgeRestrictionDto(
  val age: Int,
  val restriction: RestrictionMode,
)

@Serializable
data class ValidationErrorResponse(
  val violations: List<ViolationDto> = emptyList(),
)

@Serializable
data class ViolationDto(
  val fieldName: String? = null,
  val message: String? = null,
)

@Serializable
data class KomgaErrorResponse(
  val status: Int,
  val error: String,
  val message: String,
  val path: String,
)

private fun User.toDto(): UserDto =
  UserDto(
    id = id.value,
    email = email,
    roles = roles.mapTo(linkedSetOf("USER")) { it.name },
    sharedAllLibraries = sharesAllLibraries,
    sharedLibrariesIds = sharedLibraryIds.mapTo(linkedSetOf()) { it.value },
    labelsAllow = restrictions.labelsAllow,
    labelsExclude = restrictions.labelsExclude,
    ageRestriction =
      restrictions.ageRestriction?.let {
        AgeRestrictionDto(
          age = it.age,
          restriction = it.mode,
        )
      },
  )

private fun credentialViolations(
  email: String?,
  password: String?,
): List<ViolationDto> =
  buildList {
    when {
      email == null ->
        add(ViolationDto(fieldName = EMAIL_HEADER, message = "Required request header is missing"))
      !EMAIL_PATTERN.matches(email) ->
        add(ViolationDto(fieldName = "claimServer.email", message = "must be a well-formed email address"))
    }
    when {
      password == null ->
        add(ViolationDto(fieldName = PASSWORD_HEADER, message = "Required request header is missing"))
      password.isBlank() ->
        add(ViolationDto(fieldName = "claimServer.password", message = "must not be blank"))
    }
  }

private val EMAIL_PATTERN = Regex(".+@.+\\..+")
private const val EMAIL_HEADER = "X-Komga-Email"
private const val PASSWORD_HEADER = "X-Komga-Password"
