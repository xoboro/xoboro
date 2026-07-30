package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
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
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyCommentAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserRole
import kotlinx.serialization.Serializable

fun Route.xoboroNativeSelfServiceRoutes(
  users: UserLifecycle,
  apiKeys: ApiKeyLifecycle,
) {
  mountXoboroNativeSelfServiceRoutes(users, apiKeys)
}

fun Route.xoboroNativeSelfServiceRoutes(users: UserLifecycle) {
  mountXoboroNativeSelfServiceRoutes(users, null)
}

private fun Route.mountXoboroNativeSelfServiceRoutes(
  users: UserLifecycle,
  apiKeys: ApiKeyLifecycle?,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      route("/me") {
        get {
          call.respond(call.nativeUser().toNativeSelfServiceResponse())
        }
        put("/password") {
          val caller = call.nativeUser()
          val request = call.receive<XoboroOwnPasswordUpdateRequest>()
          val authenticated = users.authenticate(caller.email, request.currentPassword)
          if (authenticated?.id != caller.id) {
            call.respondNativeError(
              HttpStatusCode.Forbidden,
              "invalid_credentials",
              "Current password is invalid",
            )
            return@put
          }
          try {
            users.updatePassword(caller.id, request.newPassword)
          } catch (_: IllegalArgumentException) {
            call.respond(
              HttpStatusCode.BadRequest,
              XoboroApiError("invalid_request", "New password must not be blank"),
            )
            return@put
          }
          call.respond(HttpStatusCode.NoContent)
        }
        apiKeys?.let { lifecycle ->
          route("/api-keys") {
            get {
              val caller = call.nativeUser()
              call.respond(lifecycle.findAll(caller.id).map(ApiKey::toNativeMetadataResponse))
            }
            post {
              val caller = call.nativeUser()
              val request = call.receive<XoboroApiKeyCreationRequest>()
              val scopes =
                request.scopes?.map { name ->
                  UserRole.entries.firstOrNull { it.name == name }
                    ?: throw XoboroInvalidQueryException("Unknown API key scope: $name")
                }?.toSet() ?: emptySet()
              val created =
                try {
                  lifecycle.create(
                    userId = caller.id,
                    comment = request.comment,
                    scopes = scopes,
                    expiresAtMillis = request.expiresAtMillis,
                  )
                } catch (_: ApiKeyCommentAlreadyExistsException) {
                  call.respond(
                    HttpStatusCode.Conflict,
                    XoboroApiError(
                      "duplicate_api_key_comment",
                      "An API key with this comment already exists",
                    ),
                  )
                  return@post
                } catch (failure: IllegalArgumentException) {
                  // The lifecycle validates the comment, the scope subset and the expiry instant.
                  // Reporting its message keeps the three distinguishable; a fixed string here
                  // would tell a caller who sent a past expiry that their comment was blank.
                  call.respond(
                    HttpStatusCode.BadRequest,
                    XoboroApiError(
                      "invalid_request",
                      failure.message ?: "API key request was invalid",
                    ),
                  )
                  return@post
                }
              if (created == null) {
                call.respond(
                  HttpStatusCode.ServiceUnavailable,
                  XoboroApiError(
                    "api_key_generation_failed",
                    "A unique API key could not be generated",
                  ),
                )
                return@post
              }
              call.respond(
                HttpStatusCode.Created,
                XoboroCreatedApiKeyResponse(
                  id = created.apiKey.id.value,
                  comment = created.apiKey.comment,
                  scopes = created.apiKey.scopes.map { it.name }.sorted(),
                  expiresAtMillis = created.apiKey.expiresAtMillis,
                  createdAtMillis = created.apiKey.createdAtMillis,
                  updatedAtMillis = created.apiKey.updatedAtMillis,
                  token = created.plainTextKey,
                ),
              )
            }
            delete("/{apiKeyId}") {
              val caller = call.nativeUser()
              val apiKeyId = ApiKeyId(call.requiredParameter("apiKeyId"))
              if (!lifecycle.delete(caller.id, apiKeyId)) {
                call.respondNativeNotFound("api_key_not_found", "API key was not found")
                return@delete
              }
              call.respond(HttpStatusCode.NoContent)
            }
          }
        }
      }
    }
  }
}

private fun User.toNativeSelfServiceResponse(): XoboroSelfServiceUserResponse =
  XoboroSelfServiceUserResponse(
    id = id.value,
    email = email,
    roles = roles.map { it.name }.sorted(),
    sharedLibraryIds = sharedLibraryIds.map { it.value }.sorted(),
    sharesAllLibraries = sharesAllLibraries,
    restrictions =
      XoboroSelfServiceRestrictionsResponse(
        ageRestriction =
          restrictions.ageRestriction?.let {
            XoboroSelfServiceAgeRestrictionResponse(it.age, it.mode.name)
          },
        labelsAllow = restrictions.labelsAllow.sorted(),
        labelsExclude = restrictions.labelsExclude.sorted(),
      ),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

private fun ApiKey.toNativeMetadataResponse(): XoboroApiKeyMetadataResponse =
  XoboroApiKeyMetadataResponse(
    id = id.value,
    comment = comment,
    scopes = scopes.map { it.name }.sorted(),
    expiresAtMillis = expiresAtMillis,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

@Serializable
data class XoboroOwnPasswordUpdateRequest(
  val currentPassword: String,
  val newPassword: String,
)

/**
 * [scopes] narrows the key to a subset of the caller's roles; omitting it or passing an empty list
 * leaves the key as capable as its owner. [expiresAtMillis] is an absolute instant so that a key's
 * lifetime cannot be extended by reading it later.
 */
@Serializable
data class XoboroApiKeyCreationRequest(
  val comment: String,
  val scopes: List<String>? = null,
  val expiresAtMillis: Long? = null,
)

@Serializable
data class XoboroApiKeyMetadataResponse(
  val id: String,
  val comment: String,
  val scopes: List<String>,
  val expiresAtMillis: Long? = null,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
)

@Serializable
data class XoboroCreatedApiKeyResponse(
  val id: String,
  val comment: String,
  val scopes: List<String>,
  val expiresAtMillis: Long? = null,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val token: String,
)

@Serializable
data class XoboroSelfServiceUserResponse(
  val id: String,
  val email: String,
  val roles: List<String>,
  val sharedLibraryIds: List<String>,
  val sharesAllLibraries: Boolean,
  val restrictions: XoboroSelfServiceRestrictionsResponse,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
)

@Serializable
data class XoboroSelfServiceRestrictionsResponse(
  val ageRestriction: XoboroSelfServiceAgeRestrictionResponse?,
  val labelsAllow: List<String>,
  val labelsExclude: List<String>,
)

@Serializable
data class XoboroSelfServiceAgeRestrictionResponse(
  val age: Int,
  val mode: String,
)
