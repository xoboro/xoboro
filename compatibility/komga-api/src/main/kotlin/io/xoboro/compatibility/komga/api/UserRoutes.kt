package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyCommentAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

fun Route.komgaAuthenticatedUserRoutes(
  users: UserLifecycle,
  libraries: LibraryRepository,
  apiKeys: ApiKeyLifecycle? = null,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    route("/api/v2/users") {
      get("/me") {
        call.respond(call.komgaPrincipal().user.toDto())
      }
      patch("/me/password") {
        val principal = call.komgaPrincipal()
        val request = call.receive<PasswordUpdateDto>()
        if (request.password.isBlank()) {
          call.respondValidation("password", "must not be blank")
          return@patch
        }
        users.updatePassword(
          id = principal.user.id,
          rawPassword = request.password,
          expireSessions = false,
        )
        call.respond(HttpStatusCode.NoContent)
      }
      apiKeys?.let { lifecycle ->
        get("/me/api-keys") {
          val principal = call.komgaPrincipal()
          call.respond(
            lifecycle
              .findAll(principal.user.id)
              .map { it.toDto(REDACTED_API_KEY) },
          )
        }
        post("/me/api-keys") {
          val principal = call.komgaPrincipal()
          val request = call.receive<ApiKeyRequestDto>()
          if (request.comment.isBlank()) {
            call.respondValidation("comment", "must not be blank")
            return@post
          }
          try {
            val created = lifecycle.create(principal.user.id, request.comment)
            if (created == null) {
              call.respondError(
                HttpStatusCode.ServiceUnavailable,
                "Failed to generate API key",
              )
            } else {
              call.respond(created.apiKey.toDto(created.plainTextKey))
            }
          } catch (_: ApiKeyCommentAlreadyExistsException) {
            call.respondBadRequest(DUPLICATE_API_KEY_COMMENT_CODE)
          }
        }
        delete("/me/api-keys/{keyId}") {
          val principal = call.komgaPrincipal()
          val keyId = ApiKeyId(requireNotNull(call.parameters["keyId"]))
          if (!lifecycle.delete(principal.user.id, keyId)) {
            call.respondNotFound()
            return@delete
          }
          call.respond(HttpStatusCode.NoContent)
        }
      }
      get {
        val principal = call.komgaPrincipal()
        if (!principal.user.isAdmin) {
          call.respondForbidden()
          return@get
        }
        call.respond(users.findAll().map(User::toDto))
      }
      post {
        val principal = call.komgaPrincipal()
        if (!principal.user.isAdmin) {
          call.respondForbidden()
          return@post
        }
        val request = call.receive<UserCreationDto>()
        val validation = request.validationViolations()
        if (validation.isNotEmpty()) {
          call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(validation))
          return@post
        }
        try {
          val created =
            users.createUser(
              email = request.email,
              rawPassword = request.password,
              roles = request.roles.toUserRoles(),
              sharedLibraryIds = request.existingSharedLibraryIds(libraries),
              sharesAllLibraries = request.sharedLibraries?.all != false,
              restrictions = request.toContentRestrictions(),
            )
          call.respond(HttpStatusCode.Created, created.toDto())
        } catch (failure: IllegalArgumentException) {
          val message =
            if (failure is UserEmailAlreadyExistsException) {
              "A user with this email already exists"
            } else {
              failure.message ?: "Invalid user"
            }
          call.respondBadRequest(message)
        }
      }
      delete("/{id}") {
        val principal = call.komgaPrincipal()
        val id = UserId(requireNotNull(call.parameters["id"]))
        if (!principal.user.isAdmin || principal.user.id == id) {
          call.respondForbidden()
          return@delete
        }
        if (!users.deleteUser(id)) {
          call.respondNotFound()
          return@delete
        }
        call.respond(HttpStatusCode.NoContent)
      }
      patch("/{id}") {
        val principal = call.komgaPrincipal()
        val id = UserId(requireNotNull(call.parameters["id"]))
        if (!principal.user.isAdmin || principal.user.id == id) {
          call.respondForbidden()
          return@patch
        }
        val existing = users.findByIdOrNull(id)
        if (existing == null) {
          call.respondNotFound()
          return@patch
        }
        try {
          val patch = call.receive<JsonObject>()
          users.updateUser(existing.applyPatch(patch, libraries))
          call.respond(HttpStatusCode.NoContent)
        } catch (failure: IllegalArgumentException) {
          call.respondBadRequest(failure.message ?: "Invalid user update")
        }
      }
      patch("/{id}/password") {
        val principal = call.komgaPrincipal()
        val id = UserId(requireNotNull(call.parameters["id"]))
        if (!principal.user.isAdmin && principal.user.id != id) {
          call.respondForbidden()
          return@patch
        }
        if (users.findByIdOrNull(id) == null) {
          call.respondNotFound()
          return@patch
        }
        val request = call.receive<PasswordUpdateDto>()
        if (request.password.isBlank()) {
          call.respondValidation("password", "must not be blank")
          return@patch
        }
        users.updatePassword(
          id = id,
          rawPassword = request.password,
          expireSessions = id != principal.user.id,
        )
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}

@Serializable
data class UserCreationDto(
  val email: String,
  val password: String,
  val roles: List<String> = emptyList(),
  val ageRestriction: AgeRestrictionUpdateDto? = null,
  val labelsAllow: Set<String>? = null,
  val labelsExclude: Set<String>? = null,
  val sharedLibraries: SharedLibrariesUpdateDto? = null,
)

@Serializable
data class PasswordUpdateDto(
  val password: String,
)

@Serializable
data class ApiKeyRequestDto(
  val comment: String,
)

@Serializable
data class ApiKeyDto(
  val id: String,
  val userId: String,
  val key: String,
  val comment: String,
  val createdDate: String,
  val lastModifiedDate: String,
)

@Serializable
data class AgeRestrictionUpdateDto(
  val age: Int,
  val restriction: AllowExcludeDto,
)

@Serializable
data class SharedLibrariesUpdateDto(
  val all: Boolean,
  val libraryIds: Set<String>,
)

@Serializable
enum class AllowExcludeDto {
  ALLOW_ONLY,
  EXCLUDE,
  NONE,
}

private fun UserCreationDto.validationViolations(): List<ViolationDto> =
  buildList {
    if (!EMAIL_PATTERN.matches(email)) {
      add(ViolationDto("email", "must be a well-formed email address"))
    }
    if (password.isBlank()) {
      add(ViolationDto("password", "must not be blank"))
    }
    if (ageRestriction != null && ageRestriction.age < 0) {
      add(ViolationDto("ageRestriction.age", "must be greater than or equal to 0"))
    }
  }

private fun ApiKey.toDto(keyValue: String): ApiKeyDto =
  ApiKeyDto(
    id = id.value,
    userId = userId.value,
    key = keyValue,
    comment = comment,
    createdDate = Instant.ofEpochMilli(createdAtMillis).toString(),
    lastModifiedDate = Instant.ofEpochMilli(updatedAtMillis).toString(),
  )

private fun UserCreationDto.toContentRestrictions(): ContentRestrictions =
  ContentRestrictions(
    ageRestriction = ageRestriction.toAgeRestrictionOrNull(),
    labelsAllow = labelsAllow.orEmpty(),
    labelsExclude = labelsExclude.orEmpty(),
  )

private fun UserCreationDto.existingSharedLibraryIds(libraries: LibraryRepository): Set<LibraryId> {
  if (sharedLibraries == null || sharedLibraries.all) return emptySet()
  return libraries
    .findAllByIds(sharedLibraries.libraryIds.map(::LibraryId))
    .mapTo(linkedSetOf()) { it.id }
}

private fun User.applyPatch(
  patch: JsonObject,
  libraries: LibraryRepository,
): User {
  val updatedRoles =
    if ("roles" in patch) {
      WIRE_JSON
        .decodeFromJsonElement<Set<String>>(requireNotNull(patch["roles"]))
        .toUserRoles()
    } else {
      roles
    }
  val updatedSharing =
    if ("sharedLibraries" in patch) {
      WIRE_JSON.decodeFromJsonElement<SharedLibrariesUpdateDto>(
        requireNotNull(patch["sharedLibraries"]),
      )
    } else {
      null
    }
  val updatedSharesAll =
    if ("sharedLibraries" in patch) {
      requireNotNull(updatedSharing).all
    } else {
      sharesAllLibraries
    }
  val updatedLibraryIds =
    if (updatedSharing == null || updatedSharing.all) {
      if ("sharedLibraries" in patch) emptySet() else sharedLibraryIds
    } else {
      libraries
        .findAllByIds(updatedSharing.libraryIds.map(::LibraryId))
        .mapTo(linkedSetOf()) { it.id }
    }
  val updatedAge =
    if ("ageRestriction" in patch) {
      patch["ageRestriction"]
        ?.takeUnless { it is JsonNull }
        ?.let { WIRE_JSON.decodeFromJsonElement<AgeRestrictionUpdateDto>(it) }
        .toAgeRestrictionOrNull()
    } else {
      restrictions.ageRestriction
    }
  val updatedLabelsAllow =
    patch.updatedLabels("labelsAllow", restrictions.labelsAllow)
  val updatedLabelsExclude =
    patch.updatedLabels("labelsExclude", restrictions.labelsExclude)
  return copy(
    roles = updatedRoles,
    sharedLibraryIds = updatedLibraryIds,
    sharesAllLibraries = updatedSharesAll,
    restrictions =
      ContentRestrictions(
        ageRestriction = updatedAge,
        labelsAllow = updatedLabelsAllow,
        labelsExclude = updatedLabelsExclude,
      ),
  )
}

private fun JsonObject.updatedLabels(
  key: String,
  existing: Set<String>,
): Set<String> =
  if (key !in this) {
    existing
  } else {
    get(key)
      ?.takeUnless { it is JsonNull }
      ?.let { WIRE_JSON.decodeFromJsonElement<Set<String>>(it) }
      .orEmpty()
  }

private fun AgeRestrictionUpdateDto?.toAgeRestrictionOrNull(): AgeRestriction? =
  when (val value = this) {
    null -> null
    else ->
      when (value.restriction) {
        AllowExcludeDto.NONE -> null
        AllowExcludeDto.ALLOW_ONLY -> AgeRestriction(value.age, RestrictionMode.ALLOW_ONLY)
        AllowExcludeDto.EXCLUDE -> AgeRestriction(value.age, RestrictionMode.EXCLUDE)
      }
  }

private fun Iterable<String>.toUserRoles(): Set<UserRole> =
  mapNotNullTo(linkedSetOf()) { value ->
    UserRole.entries.firstOrNull { it.name == value }
  }

private fun io.ktor.server.application.ApplicationCall.komgaPrincipal(): KomgaPrincipal =
  requireNotNull(principal<KomgaPrincipal>())

private suspend fun io.ktor.server.application.ApplicationCall.respondValidation(
  fieldName: String,
  message: String,
) {
  respond(
    HttpStatusCode.BadRequest,
    ValidationErrorResponse(listOf(ViolationDto(fieldName, message))),
  )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondBadRequest(message: String) {
  respondError(HttpStatusCode.BadRequest, message)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondForbidden() {
  respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondNotFound() {
  respondError(HttpStatusCode.NotFound, HttpStatusCode.NotFound.description)
}

internal suspend fun io.ktor.server.application.ApplicationCall.respondError(
  status: HttpStatusCode,
  message: String,
) {
  respond(
    status,
    KomgaErrorResponse(
      status = status.value,
      error = status.description,
      message = message,
      path = request.path(),
    ),
  )
}

private val WIRE_JSON = Json { ignoreUnknownKeys = true }
private val EMAIL_PATTERN = Regex(".+@.+\\..+")
private const val REDACTED_API_KEY = "******"
private const val DUPLICATE_API_KEY_COMMENT_CODE = "ERR_1034"
