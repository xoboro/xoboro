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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.application.NullableSettingUpdate
import io.xoboro.core.application.ServerSettingsLifecycle
import io.xoboro.core.application.ServerSettingsSnapshot
import io.xoboro.core.application.ServerSettingsUpdate
import io.xoboro.core.application.SettingMultiSource
import io.xoboro.core.application.ThumbnailSize
import io.xoboro.core.domain.AuthenticationActivity
import io.xoboro.core.domain.AuthenticationActivityPage
import io.xoboro.core.domain.AuthenticationActivityPageRequest
import io.xoboro.core.domain.AuthenticationActivitySortField
import io.xoboro.core.domain.ClientSetting
import io.xoboro.core.domain.HistoricalEvent
import io.xoboro.core.domain.HistoricalEventPage
import io.xoboro.core.domain.HistoricalEventPageRequest
import io.xoboro.core.domain.HistoricalEventRepository
import io.xoboro.core.domain.HistoricalEventSortField
import io.xoboro.core.domain.SortDirection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

fun Route.xoboroNativeOpsRoutes(
  serverSettings: ServerSettingsLifecycle,
  clientSettings: ClientSettingsLifecycle,
  authenticationActivities: AuthenticationActivityLifecycle,
  history: HistoricalEventRepository,
) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      get("/server-settings") {
        val caller = call.nativeUser()
        if (!caller.isAdmin) {
          call.respondServerSettingsForbidden()
          return@get
        }
        call.respond(serverSettings.snapshot().toNativeResponse())
      }
      put("/server-settings") {
        val caller = call.nativeUser()
        if (!caller.isAdmin) {
          call.respondServerSettingsForbidden()
          return@put
        }
        val update =
          runCatching {
            call.receive<JsonObject>().toServerSettingsUpdate()
          }.getOrElse { failure ->
            call.respondInvalidOpsRequest(failure)
            return@put
          }
        runCatching {
          serverSettings.update(update)
        }.getOrElse { failure ->
          call.respondInvalidOpsRequest(failure)
          return@put
        }
        call.respond(HttpStatusCode.NoContent)
      }
      get("/client-settings") {
        val caller = call.nativeUser()
        val effective =
          clientSettings.findGlobal(onlyUnauthorized = false) +
            clientSettings.findForUser(caller.id)
        call.respond(effective.mapValues { (_, setting) -> setting.toNativeResponse() })
      }
      put("/client-settings") {
        val caller = call.nativeUser()
        runCatching {
          val request = call.receive<Map<String, XoboroClientSettingWriteRequest>>()
          clientSettings.saveForUser(
            caller.id,
            request.mapValues { (_, setting) -> ClientSetting(setting.value) },
          )
        }.getOrElse { failure ->
          call.respondInvalidOpsRequest(failure)
          return@put
        }
        call.respond(HttpStatusCode.NoContent)
      }
      get("/client-settings/global") {
        val caller = call.nativeUser()
        if (!caller.isAdmin) {
          call.respondClientSettingsForbidden()
          return@get
        }
        call.respond(
          clientSettings
            .findGlobal(onlyUnauthorized = false)
            .mapValues { (_, setting) -> setting.toNativeResponse() },
        )
      }
      put("/client-settings/global") {
        val caller = call.nativeUser()
        if (!caller.isAdmin) {
          call.respondClientSettingsForbidden()
          return@put
        }
        runCatching {
          val request = call.receive<Map<String, XoboroClientSettingGlobalWriteRequest>>()
          clientSettings.saveGlobal(
            request.mapValues { (_, setting) ->
              ClientSetting(setting.value, setting.allowUnauthorized)
            },
          )
        }.getOrElse { failure ->
          call.respondInvalidOpsRequest(failure)
          return@put
        }
        call.respond(HttpStatusCode.NoContent)
      }
      delete("/client-settings/global") {
        val caller = call.nativeUser()
        if (!caller.isAdmin) {
          call.respondClientSettingsForbidden()
          return@delete
        }
        runCatching {
          clientSettings.deleteGlobal(call.receive<Set<String>>())
        }.getOrElse { failure ->
          call.respondInvalidOpsRequest(failure)
          return@delete
        }
        call.respond(HttpStatusCode.NoContent)
      }
      get("/authentication-activity") {
        val caller = call.nativeUser()
        if (!caller.isAdmin) {
          call.respondAuthenticationActivityForbidden()
          return@get
        }
        call.respond(
          authenticationActivities
            .findAll(call.authenticationActivityPageRequest())
            .toNativePage(),
        )
      }
      get("/me/authentication-activity") {
        val caller = call.nativeUser()
        call.respond(
          authenticationActivities
            .findAllByUser(caller, call.authenticationActivityPageRequest())
            .toNativePage(),
        )
      }
      get("/history") {
        val caller = call.nativeUser()
        if (!caller.isAdmin) {
          call.respondHistoryForbidden()
          return@get
        }
        call.respond(history.findAll(call.historicalEventPageRequest()).toNativePage())
      }
    }
  }
}

private fun JsonObject.toServerSettingsUpdate(): ServerSettingsUpdate =
  ServerSettingsUpdate(
    deleteEmptyCollections = optionalValue("deleteEmptyCollections"),
    deleteEmptyReadLists = optionalValue("deleteEmptyReadLists"),
    rememberMeDurationDays = optionalValue("rememberMeDurationDays"),
    renewRememberMeKey = optionalValue("renewRememberMeKey"),
    thumbnailSize =
      optionalValue<String>("thumbnailSize")?.let { value ->
        ThumbnailSize.entries.firstOrNull { it.name == value }
          ?: throw IllegalArgumentException("Unknown thumbnail size: $value")
      },
    taskPoolSize = optionalValue("taskPoolSize"),
    serverPort = nullableUpdate("serverPort"),
    serverContextPath = nullableUpdate("serverContextPath"),
    koboProxy = optionalValue("koboProxy"),
    koboPort = nullableUpdate("koboPort"),
    kepubifyPath = nullableUpdate("kepubifyPath"),
  )

private inline fun <reified T> JsonObject.optionalValue(key: String): T? {
  val element = this[key] ?: return null
  if (element is JsonNull) return null
  return Json.decodeFromJsonElement(element)
}

private inline fun <reified T> JsonObject.nullableUpdate(key: String): NullableSettingUpdate<T> =
  NullableSettingUpdate(
    isSet = key in this,
    value = optionalValue(key),
  )

private fun ServerSettingsSnapshot.toNativeResponse(): XoboroServerSettingsResponse =
  XoboroServerSettingsResponse(
    deleteEmptyCollections = deleteEmptyCollections,
    deleteEmptyReadLists = deleteEmptyReadLists,
    rememberMeDurationDays = rememberMeDurationDays,
    thumbnailSize = XoboroThumbnailSizeResponse.valueOf(thumbnailSize.name),
    taskPoolSize = taskPoolSize,
    serverPort = serverPort.toNativeResponse(),
    serverContextPath = serverContextPath.toNativeResponse(),
    koboProxy = koboProxy,
    koboPort = koboPort,
    kepubifyPath = kepubifyPath.toNativeResponse(),
  )

private fun <T> SettingMultiSource<T>.toNativeResponse(): XoboroSettingMultiSourceResponse<T> =
  XoboroSettingMultiSourceResponse(
    configurationSource = configurationSource,
    databaseSource = databaseSource,
    effectiveValue = effectiveValue,
  )

private fun ClientSetting.toNativeResponse(): XoboroClientSettingResponse =
  XoboroClientSettingResponse(
    value = value,
    allowUnauthorized = allowUnauthorized,
  )

private fun ApplicationCall.authenticationActivityPageRequest(): AuthenticationActivityPageRequest {
  val page = optionalPageInteger("page") ?: 0
  val size = optionalPageInteger("size") ?: DEFAULT_OPS_PAGE_SIZE
  validatePage(page, size)
  val (sortField, direction) =
    request.queryParameters["sort"]?.toAuthenticationActivitySort()
      ?: (AuthenticationActivitySortField.DATE_TIME to SortDirection.DESCENDING)
  return AuthenticationActivityPageRequest(
    pageNumber = page,
    pageSize = size,
    sortField = sortField,
    sortDirection = direction,
  )
}

private fun ApplicationCall.historicalEventPageRequest(): HistoricalEventPageRequest {
  val page = optionalPageInteger("page") ?: 0
  val size = optionalPageInteger("size") ?: DEFAULT_OPS_PAGE_SIZE
  validatePage(page, size)
  val (sortField, direction) =
    request.queryParameters["sort"]?.toHistoricalEventSort()
      ?: (HistoricalEventSortField.TIMESTAMP to SortDirection.DESCENDING)
  return HistoricalEventPageRequest(
    page = page,
    size = size,
    sortField = sortField,
    direction = direction,
  )
}

private fun ApplicationCall.optionalPageInteger(name: String): Int? =
  request.queryParameters[name]?.let {
    it.toIntOrNull() ?: throw XoboroInvalidQueryException("$name must be an integer")
  }

private fun validatePage(
  page: Int,
  size: Int,
) {
  if (page < 0) throw XoboroInvalidQueryException("page must not be negative")
  if (size !in 1..MAXIMUM_OPS_PAGE_SIZE) {
    throw XoboroInvalidQueryException("size must be between 1 and $MAXIMUM_OPS_PAGE_SIZE")
  }
}

private fun String.toAuthenticationActivitySort():
  Pair<AuthenticationActivitySortField, SortDirection> {
  val fields =
    mapOf(
      "dateTime" to AuthenticationActivitySortField.DATE_TIME,
      "email" to AuthenticationActivitySortField.EMAIL,
      "success" to AuthenticationActivitySortField.SUCCESS,
      "ip" to AuthenticationActivitySortField.IP,
      "error" to AuthenticationActivitySortField.ERROR,
      "userId" to AuthenticationActivitySortField.USER_ID,
      "userAgent" to AuthenticationActivitySortField.USER_AGENT,
    )
  return toOpsSort(fields)
}

private fun String.toHistoricalEventSort(): Pair<HistoricalEventSortField, SortDirection> {
  val fields =
    mapOf(
      "type" to HistoricalEventSortField.TYPE,
      "bookId" to HistoricalEventSortField.BOOK_ID,
      "seriesId" to HistoricalEventSortField.SERIES_ID,
      "timestamp" to HistoricalEventSortField.TIMESTAMP,
    )
  return toOpsSort(fields)
}

private fun <T> String.toOpsSort(fields: Map<String, T>): Pair<T, SortDirection> {
  val parts = split(',')
  if (parts.size !in 1..2) throw XoboroInvalidQueryException("sort must be field[,direction]")
  val rawField = parts[0].trim()
  val field =
    fields[rawField]
      ?: throw XoboroInvalidQueryException("Unsupported sort field: $rawField")
  val direction =
    when (parts.getOrNull(1)?.trim()?.lowercase()) {
      null, "", "desc" -> SortDirection.DESCENDING
      "asc" -> SortDirection.ASCENDING
      else -> throw XoboroInvalidQueryException("Sort direction must be asc or desc")
    }
  return field to direction
}

private fun AuthenticationActivityPage.toNativePage():
  XoboroPageResponse<XoboroAuthenticationActivityResponse> {
  val totalPages = calculateTotalPages(totalElements, request.pageSize)
  return XoboroPageResponse(
    items = content.map(AuthenticationActivity::toNativeResponse),
    page = request.pageNumber,
    size = request.pageSize,
    totalItems = totalElements,
    totalPages = totalPages,
    hasPrevious = request.pageNumber > 0,
    hasNext = request.pageNumber + 1 < totalPages,
  )
}

private fun HistoricalEventPage.toNativePage(): XoboroPageResponse<XoboroHistoricalEventResponse> {
  val totalPages = calculateTotalPages(totalElements, request.size)
  return XoboroPageResponse(
    items = content.map(HistoricalEvent::toNativeResponse),
    page = request.page,
    size = request.size,
    totalItems = totalElements,
    totalPages = totalPages,
    hasPrevious = request.page > 0,
    hasNext = request.page + 1 < totalPages,
  )
}

private fun calculateTotalPages(
  totalItems: Long,
  size: Int,
): Int =
  if (totalItems == 0L) {
    0
  } else {
    ((totalItems - 1) / size + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  }

private fun AuthenticationActivity.toNativeResponse(): XoboroAuthenticationActivityResponse =
  XoboroAuthenticationActivityResponse(
    userId = userId?.value,
    email = email,
    apiKeyId = apiKeyId?.value,
    apiKeyComment = apiKeyComment,
    ip = ip,
    userAgent = userAgent,
    success = success,
    error = error,
    dateTimeMillis = dateTimeMillis,
    source = source,
  )

private fun HistoricalEvent.toNativeResponse(): XoboroHistoricalEventResponse =
  XoboroHistoricalEventResponse(
    id = id,
    type = type,
    timestampMillis = timestampMillis,
    bookId = bookId?.value,
    seriesId = seriesId?.value,
    properties = properties,
  )

private suspend fun ApplicationCall.respondServerSettingsForbidden() {
  respond(
    HttpStatusCode.Forbidden,
    XoboroApiError(
      "server_settings_forbidden",
      "Server settings require an administrator",
    ),
  )
}

private suspend fun ApplicationCall.respondClientSettingsForbidden() {
  respond(
    HttpStatusCode.Forbidden,
    XoboroApiError(
      "client_settings_forbidden",
      "Global client settings require an administrator",
    ),
  )
}

private suspend fun ApplicationCall.respondAuthenticationActivityForbidden() {
  respond(
    HttpStatusCode.Forbidden,
    XoboroApiError(
      "authentication_activity_forbidden",
      "Authentication activity requires an administrator",
    ),
  )
}

private suspend fun ApplicationCall.respondHistoryForbidden() {
  respond(
    HttpStatusCode.Forbidden,
    XoboroApiError(
      "history_forbidden",
      "History requires an administrator",
    ),
  )
}

private suspend fun ApplicationCall.respondInvalidOpsRequest(failure: Throwable) {
  respond(
    HttpStatusCode.BadRequest,
    XoboroApiError(
      "invalid_request",
      failure.message ?: "Request is invalid",
    ),
  )
}

@Serializable
data class XoboroServerSettingsResponse(
  val deleteEmptyCollections: Boolean,
  val deleteEmptyReadLists: Boolean,
  val rememberMeDurationDays: Long,
  val thumbnailSize: XoboroThumbnailSizeResponse,
  val taskPoolSize: Int,
  val serverPort: XoboroSettingMultiSourceResponse<Int?>,
  val serverContextPath: XoboroSettingMultiSourceResponse<String?>,
  val koboProxy: Boolean,
  val koboPort: Int?,
  val kepubifyPath: XoboroSettingMultiSourceResponse<String?>,
)

@Serializable
data class XoboroSettingMultiSourceResponse<T>(
  val configurationSource: T,
  val databaseSource: T,
  val effectiveValue: T,
)

@Serializable
enum class XoboroThumbnailSizeResponse {
  DEFAULT,
  MEDIUM,
  LARGE,
  XLARGE,
}

@Serializable
data class XoboroClientSettingResponse(
  val value: String,
  val allowUnauthorized: Boolean? = null,
)

@Serializable
data class XoboroClientSettingWriteRequest(
  val value: String,
)

@Serializable
data class XoboroClientSettingGlobalWriteRequest(
  val value: String,
  val allowUnauthorized: Boolean,
)

@Serializable
data class XoboroAuthenticationActivityResponse(
  val userId: String? = null,
  val email: String? = null,
  val apiKeyId: String? = null,
  val apiKeyComment: String? = null,
  val ip: String? = null,
  val userAgent: String? = null,
  val success: Boolean,
  val error: String? = null,
  val dateTimeMillis: Long,
  val source: String? = null,
)

@Serializable
data class XoboroHistoricalEventResponse(
  val id: String,
  val type: String,
  val timestampMillis: Long,
  val bookId: String? = null,
  val seriesId: String? = null,
  val properties: Map<String, String> = emptyMap(),
)

private const val DEFAULT_OPS_PAGE_SIZE = 20
private const val MAXIMUM_OPS_PAGE_SIZE = 200
