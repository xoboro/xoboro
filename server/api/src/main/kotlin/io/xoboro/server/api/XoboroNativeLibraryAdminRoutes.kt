package io.xoboro.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryAvailabilityProbe
import io.xoboro.core.application.LibraryMaintenanceRequester
import io.xoboro.core.application.LibraryScanRequester
import io.xoboro.core.application.TaskEnqueue
import io.xoboro.core.domain.DuplicateLibraryNameException
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRootMissingException
import io.xoboro.core.domain.LibraryRootNotDirectoryException
import io.xoboro.core.domain.OverlappingLibraryRootException
import io.xoboro.core.domain.User

fun Route.xoboroNativeLibraryAdminRoutes(
  libraries: LibraryAdministrationLifecycle,
  scanRequester: LibraryScanRequester,
  maintenanceRequester: LibraryMaintenanceRequester,
  availabilityProbe: LibraryAvailabilityProbe,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      route("/libraries") {
        post {
          val user = call.nativeUser()
          if (!call.requireLibraryAdministrator(user)) return@post
          val request = call.receive<XoboroLibraryAdministrationRequest>()
          val created =
            try {
              libraries.create(
                name = request.name,
                root = request.source.toSourceLocation(),
                settings = request.settings.toLibrarySettings(),
              )
            } catch (failure: IllegalArgumentException) {
              call.respondLibraryValidationFailure(failure)
              return@post
            }
          call.respond(HttpStatusCode.Created, created.toNativeResponse(user))
        }
        put("/{libraryId}") {
          val user = call.nativeUser()
          if (!call.requireLibraryAdministrator(user)) return@put
          val id = LibraryId(call.requiredParameter("libraryId"))
          val request = call.receive<XoboroLibraryAdministrationRequest>()
          val updated =
            try {
              libraries.update(id) { existing ->
                existing.copy(
                  name = request.name,
                  root = request.source.toSourceLocation(),
                  settings = request.settings.toLibrarySettings(),
                )
              }
            } catch (failure: IllegalArgumentException) {
              call.respondLibraryValidationFailure(failure)
              return@put
            }
          if (updated == null) {
            call.respondNativeNotFound("library_not_found", "Library was not found")
          } else {
            call.respond(updated.toNativeResponse(user))
          }
        }
        delete("/{libraryId}") {
          val user = call.nativeUser()
          if (!call.requireLibraryAdministrator(user)) return@delete
          val id = LibraryId(call.requiredParameter("libraryId"))
          val library = libraries.findByIdOrNull(id)
          if (library == null) {
            call.respondNativeNotFound("library_not_found", "Library was not found")
            return@delete
          }
          // Refusing by default protects against deleting a catalog because a mount went
          // missing. The flag is only cleared by a successful scan, so storage that is gone for
          // good would otherwise leave the library undeletable; `force` is the explicit way out.
          val force =
            call.request.queryParameters["force"]?.let { raw ->
              raw.toBooleanStrictOrNull()
                ?: throw XoboroInvalidQueryException("force must be true or false")
            } ?: false
          if (library.unavailableAtMillis != null && !force) {
            call.respond(
              HttpStatusCode.Conflict,
              XoboroApiError(
                "library_unavailable",
                "Library storage is unavailable. Repeat with force=true to delete it anyway.",
              ),
            )
            return@delete
          }
          if (!libraries.delete(id)) {
            call.respondNativeNotFound("library_not_found", "Library was not found")
            return@delete
          }
          call.respond(HttpStatusCode.NoContent)
        }
        post("/{libraryId}/scan") {
          val user = call.nativeUser()
          if (!call.requireLibraryAdministrator(user)) return@post
          val id = LibraryId(call.requiredParameter("libraryId"))
          if (!call.requireExistingLibrary(libraries, id)) return@post
          scanRequester.request(id, deep = false)
          call.respond(HttpStatusCode.Accepted)
        }
        post("/{libraryId}/analyze") {
          val user = call.nativeUser()
          if (!call.requireLibraryAdministrator(user)) return@post
          val id = LibraryId(call.requiredParameter("libraryId"))
          if (!call.requireExistingLibrary(libraries, id)) return@post
          call.respondQueued(maintenanceRequester.analyze(id))
        }
        post("/{libraryId}/metadata-refresh") {
          val user = call.nativeUser()
          if (!call.requireLibraryAdministrator(user)) return@post
          val id = LibraryId(call.requiredParameter("libraryId"))
          if (!call.requireExistingLibrary(libraries, id)) return@post
          call.respondQueued(maintenanceRequester.refreshMetadata(id))
        }
        post("/{libraryId}/empty-trash") {
          val user = call.nativeUser()
          if (!call.requireLibraryAdministrator(user)) return@post
          val id = LibraryId(call.requiredParameter("libraryId"))
          if (!call.requireExistingLibrary(libraries, id)) return@post
          maintenanceRequester.emptyTrash(id)
          call.respond(HttpStatusCode.Accepted)
        }
        // Synchronous, and 200 rather than the 202 the task triggers above return: this is one
        // stat of the library root, and the resulting state is the whole point of the request.
        // Routing it through the durable queue would make an operator poll for an answer the
        // server already has.
        post("/{libraryId}/availability") {
          val user = call.nativeUser()
          if (!call.requireLibraryAdministrator(user)) return@post
          val id = LibraryId(call.requiredParameter("libraryId"))
          val probed = availabilityProbe.probe(id)
          if (probed == null) {
            call.respondNativeNotFound("library_not_found", "Library was not found")
            return@post
          }
          call.respond(probed.toNativeResponse(user))
        }
      }
    }
  }
}

private suspend fun ApplicationCall.requireLibraryAdministrator(user: User): Boolean {
  if (user.isAdmin) return true
  respondNativeError(
    HttpStatusCode.Forbidden,
    "library_administration_forbidden",
    "Library administration requires an administrator",
  )
  return false
}

private suspend fun ApplicationCall.requireExistingLibrary(
  libraries: LibraryAdministrationLifecycle,
  id: LibraryId,
): Boolean {
  if (libraries.findByIdOrNull(id) != null) return true
  respondNativeNotFound("library_not_found", "Library was not found")
  return false
}

private suspend fun ApplicationCall.respondLibraryValidationFailure(
  failure: IllegalArgumentException,
) {
  val (status, code) =
    when (failure) {
      is LibraryRootMissingException -> HttpStatusCode.BadRequest to "library_root_missing"
      is LibraryRootNotDirectoryException ->
        HttpStatusCode.BadRequest to "library_root_not_directory"
      is DuplicateLibraryNameException -> HttpStatusCode.Conflict to "library_name_conflict"
      is OverlappingLibraryRootException -> HttpStatusCode.Conflict to "library_root_overlap"
      else -> HttpStatusCode.BadRequest to "invalid_request"
    }
  respond(
    status,
    XoboroApiError(
      code = code,
      message = failure.message ?: "Library request is invalid",
    ),
  )
}

/**
 * Answers a maintenance request that was queued as a single fan-out task.
 *
 * [TaskEnqueue.ALREADY_RUNNING] joins [TaskEnqueue.QUEUED] as a `202`: both mean the work is under
 * way, and asking twice is not an error. [TaskEnqueue.UNAVAILABLE] is a retryable `503` carrying
 * `Retry-After`, matching how a busy session store is reported - answering `202` would tell an
 * operator their library was being refreshed when nothing had been queued at all.
 */
private suspend fun ApplicationCall.respondQueued(outcome: TaskEnqueue) {
  if (outcome == TaskEnqueue.UNAVAILABLE) {
    response.header(HttpHeaders.RetryAfter, TASK_STORE_RETRY_AFTER_SECONDS.toString())
    respondNativeError(
      HttpStatusCode.ServiceUnavailable,
      "task_store_unavailable",
      "The task store is busy; retry the request",
    )
    return
  }
  respond(HttpStatusCode.Accepted)
}

private const val TASK_STORE_RETRY_AFTER_SECONDS: Int = 30
