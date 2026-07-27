package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.xoboro.core.application.NullableSettingUpdate
import io.xoboro.core.application.ServerSettingsLifecycle
import io.xoboro.core.application.ServerSettingsSnapshot
import io.xoboro.core.application.ServerSettingsUpdate
import io.xoboro.core.application.SettingMultiSource
import io.xoboro.core.application.ThumbnailSize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

fun Route.komgaServerSettingsRoutes(settings: ServerSettingsLifecycle) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    get("/api/v1/settings") {
      if (!call.requireSettingsAdministrator()) return@get
      call.respond(SETTINGS_WIRE_JSON.encodeToJsonElement(settings.snapshot().toDto()))
    }
    patch("/api/v1/settings") {
      if (!call.requireSettingsAdministrator()) return@patch
      val raw = call.receive<JsonObject>()
      val update =
        runCatching { raw.toSettingsUpdate() }
          .getOrElse { failure ->
            call.respond(
              HttpStatusCode.BadRequest,
              ValidationErrorResponse(
                listOf(ViolationDto("settings", failure.message ?: "invalid value")),
              ),
            )
            return@patch
          }
      runCatching { settings.update(update) }
        .onFailure { failure ->
          call.respond(
            HttpStatusCode.BadRequest,
            ValidationErrorResponse(
              listOf(ViolationDto("settings", failure.message ?: "invalid value")),
            ),
          )
          return@patch
        }
      call.respond(HttpStatusCode.NoContent)
    }
  }
}

@Serializable
data class SettingsDto(
  val deleteEmptyCollections: Boolean,
  val deleteEmptyReadLists: Boolean,
  val rememberMeDurationDays: Long,
  val thumbnailSize: ThumbnailSizeDto,
  val taskPoolSize: Int,
  val serverPort: SettingMultiSourceDto<Int?>,
  val serverContextPath: SettingMultiSourceDto<String?>,
  val koboProxy: Boolean,
  val koboPort: Int?,
  val kepubifyPath: SettingMultiSourceDto<String?>,
)

@Serializable
data class SettingMultiSourceDto<T>(
  val configurationSource: T,
  val databaseSource: T,
  val effectiveValue: T,
)

@Serializable
enum class ThumbnailSizeDto {
  DEFAULT,
  MEDIUM,
  LARGE,
  XLARGE,
}

private suspend fun io.ktor.server.application.ApplicationCall.requireSettingsAdministrator():
  Boolean {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  if (principal.user.isAdmin) return true
  respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
  return false
}

private fun ServerSettingsSnapshot.toDto(): SettingsDto =
  SettingsDto(
    deleteEmptyCollections = deleteEmptyCollections,
    deleteEmptyReadLists = deleteEmptyReadLists,
    rememberMeDurationDays = rememberMeDurationDays,
    thumbnailSize = ThumbnailSizeDto.valueOf(thumbnailSize.name),
    taskPoolSize = taskPoolSize,
    serverPort = serverPort.toDto(),
    serverContextPath = serverContextPath.toDto(),
    koboProxy = koboProxy,
    koboPort = koboPort,
    kepubifyPath = kepubifyPath.toDto(),
  )

private fun <T> SettingMultiSource<T>.toDto(): SettingMultiSourceDto<T> =
  SettingMultiSourceDto(
    configurationSource = configurationSource,
    databaseSource = databaseSource,
    effectiveValue = effectiveValue,
  )

private fun JsonObject.toSettingsUpdate(): ServerSettingsUpdate =
  ServerSettingsUpdate(
    deleteEmptyCollections = optional("deleteEmptyCollections"),
    deleteEmptyReadLists = optional("deleteEmptyReadLists"),
    rememberMeDurationDays = optional("rememberMeDurationDays"),
    renewRememberMeKey = optional("renewRememberMeKey"),
    thumbnailSize = optional<ThumbnailSizeDto>("thumbnailSize")?.toDomain(),
    taskPoolSize = optional("taskPoolSize"),
    serverPort = nullableUpdate("serverPort"),
    serverContextPath = nullableUpdate("serverContextPath"),
    koboProxy = optional("koboProxy"),
    koboPort = nullableUpdate("koboPort"),
    kepubifyPath = nullableUpdate("kepubifyPath"),
  )

private inline fun <reified T> JsonObject.optional(key: String): T? =
  get(key)
    ?.takeUnless { it is JsonNull }
    ?.let { WIRE_JSON.decodeFromJsonElement<T>(it) }

private inline fun <reified T> JsonObject.nullableUpdate(
  key: String,
): NullableSettingUpdate<T> =
  if (key !in this) {
    NullableSettingUpdate()
  } else {
    NullableSettingUpdate(
      isSet = true,
      value = optional(key),
    )
  }

private fun ThumbnailSizeDto.toDomain(): ThumbnailSize =
  ThumbnailSize.valueOf(name)

private val WIRE_JSON = Json { ignoreUnknownKeys = true }
private val SETTINGS_WIRE_JSON = Json { explicitNulls = true }
