package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.AuthenticationActivity
import io.xoboro.core.domain.AuthenticationActivityPage
import io.xoboro.core.domain.AuthenticationActivityPageRequest
import io.xoboro.core.domain.AuthenticationActivitySortField
import io.xoboro.core.domain.SortDirection
import io.xoboro.core.domain.UserId
import java.time.Instant
import kotlinx.serialization.Serializable

fun Route.komgaAuthenticationActivityRoutes(
  users: UserLifecycle,
  activities: AuthenticationActivityLifecycle,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    route("/api/v2/users") {
      get("/me/authentication-activity") {
        val request = call.toActivityPageRequestOrNull()
        if (request == null) {
          call.respondError(HttpStatusCode.BadRequest, "Invalid pagination or sort parameters")
          return@get
        }
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        call.respond(activities.findAllByUser(principal.user, request).toDto())
      }
      get("/authentication-activity") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        if (!principal.user.isAdmin) {
          call.respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
          return@get
        }
        val request = call.toActivityPageRequestOrNull()
        if (request == null) {
          call.respondError(HttpStatusCode.BadRequest, "Invalid pagination or sort parameters")
          return@get
        }
        call.respond(activities.findAll(request).toDto())
      }
      get("/{id}/authentication-activity/latest") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val requestedUserId = UserId(requireNotNull(call.parameters["id"]))
        if (!principal.user.isAdmin && principal.user.id != requestedUserId) {
          call.respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
          return@get
        }
        val requestedUser = users.findByIdOrNull(requestedUserId)
        if (requestedUser == null) {
          call.respondError(HttpStatusCode.NotFound, HttpStatusCode.NotFound.description)
          return@get
        }
        val apiKeyId = call.request.queryParameters["apikey_id"]?.let(::ApiKeyId)
        val activity = activities.findMostRecentByUser(requestedUser, apiKeyId)
        if (activity == null) {
          call.respondError(HttpStatusCode.NotFound, HttpStatusCode.NotFound.description)
          return@get
        }
        call.respond(activity.toDto())
      }
    }
  }
}

@Serializable
data class AuthenticationActivityDto(
  val userId: String? = null,
  val email: String? = null,
  val apiKeyId: String? = null,
  val apiKeyComment: String? = null,
  val ip: String? = null,
  val userAgent: String? = null,
  val success: Boolean,
  val error: String? = null,
  val dateTime: String,
  val source: String? = null,
)

@Serializable
data class AuthenticationActivityPageDto(
  val content: List<AuthenticationActivityDto>,
  val pageable: PageableDto,
  val totalPages: Int,
  val totalElements: Long,
  val last: Boolean,
  val size: Int,
  val number: Int,
  val sort: SortDto,
  val numberOfElements: Int,
  val first: Boolean,
  val empty: Boolean,
)

@Serializable
data class PageableDto(
  val pageNumber: Int,
  val pageSize: Int,
  val sort: SortDto,
  val offset: Long,
  val paged: Boolean = true,
  val unpaged: Boolean = false,
)

@Serializable
data class SortDto(
  val empty: Boolean = false,
  val sorted: Boolean = true,
  val unsorted: Boolean = false,
)

private fun io.ktor.server.application.ApplicationCall.toActivityPageRequestOrNull():
  AuthenticationActivityPageRequest? {
  val rawPage = request.queryParameters["page"]
  val page = rawPage?.toIntOrNull() ?: if (rawPage == null) 0 else return null
  val rawSize = request.queryParameters["size"]
  val size = rawSize?.toIntOrNull() ?: if (rawSize == null) 20 else return null
  val rawUnpaged = request.queryParameters["unpaged"]
  val unpaged =
    rawUnpaged?.toBooleanStrictOrNull()
      ?: if (rawUnpaged == null) false else return null
  val rawSort = request.queryParameters.getAll("sort")?.firstOrNull()
  val (sortField, direction) =
    if (rawSort == null) {
      AuthenticationActivitySortField.DATE_TIME to SortDirection.DESCENDING
    } else {
      val parts = rawSort.split(',')
      val field = parts.firstOrNull()?.toSortFieldOrNull() ?: return null
      val parsedDirection =
        when (parts.getOrNull(1)?.lowercase()) {
          null, "asc" -> SortDirection.ASCENDING
          "desc" -> SortDirection.DESCENDING
          else -> return null
        }
      field to parsedDirection
    }
  return runCatching {
    AuthenticationActivityPageRequest(
      pageNumber = page,
      pageSize = size,
      unpaged = unpaged,
      sortField = sortField,
      sortDirection = direction,
    )
  }.getOrNull()
}

private fun String.toSortFieldOrNull(): AuthenticationActivitySortField? =
  when (this) {
    "dateTime" -> AuthenticationActivitySortField.DATE_TIME
    "email" -> AuthenticationActivitySortField.EMAIL
    "success" -> AuthenticationActivitySortField.SUCCESS
    "ip" -> AuthenticationActivitySortField.IP
    "error" -> AuthenticationActivitySortField.ERROR
    "userId" -> AuthenticationActivitySortField.USER_ID
    "userAgent" -> AuthenticationActivitySortField.USER_AGENT
    else -> null
  }

private fun AuthenticationActivity.toDto(): AuthenticationActivityDto =
  AuthenticationActivityDto(
    userId = userId?.value,
    email = email,
    apiKeyId = apiKeyId?.value,
    apiKeyComment = apiKeyComment,
    ip = ip,
    userAgent = userAgent,
    success = success,
    error = error,
    dateTime = Instant.ofEpochMilli(dateTimeMillis).toString(),
    source = source,
  )

private fun AuthenticationActivityPage.toDto(): AuthenticationActivityPageDto {
  val effectivePageNumber = if (request.unpaged) 0 else request.pageNumber
  val effectivePageSize =
    if (request.unpaged) {
      maxOf(totalElements.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), 20)
    } else {
      request.pageSize
    }
  val totalPages =
    if (totalElements == 0L) {
      0
    } else {
      ((totalElements - 1) / effectivePageSize + 1).toInt()
    }
  val sort = SortDto()
  return AuthenticationActivityPageDto(
    content = content.map(AuthenticationActivity::toDto),
    pageable =
      PageableDto(
        pageNumber = effectivePageNumber,
        pageSize = effectivePageSize,
        sort = sort,
        offset = if (request.unpaged) 0 else request.offset,
      ),
    totalPages = totalPages,
    totalElements = totalElements,
    last = request.unpaged || effectivePageNumber + 1 >= totalPages,
    size = effectivePageSize,
    number = effectivePageNumber,
    sort = sort,
    numberOfElements = content.size,
    first = effectivePageNumber == 0,
    empty = content.isEmpty(),
  )
}
