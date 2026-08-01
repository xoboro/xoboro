package io.xoboro.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json

/**
 * Marks a call whose native error body was written by the route itself with a specific code, so the
 * application-wide StatusPages handler leaves that body alone instead of flattening it to a generic
 * code.
 */
val XoboroNativeErrorBodyWritten: AttributeKey<Unit> = AttributeKey("XoboroNativeErrorBodyWritten")

/**
 * Writes a native error body, with the content type stated rather than negotiated.
 *
 * `respond(status, XoboroApiError(...))` went through content negotiation, so a caller whose
 * `Accept` header did not admit JSON got `406 Not Acceptable` and never learned the real status. A
 * browser's `EventSource` sends exactly `Accept: text/event-stream` and cannot be told to send
 * anything else, so every unauthenticated or expired-session subscription to the event stream
 * answered `406` where the description declares `401` - and because `EventSource` reconnects on its
 * own indefinitely, a session that expired mid-reading left the UI silently disconnected with no way
 * to learn it had to sign in again.
 *
 * An error is not the negotiated resource. A client that asked for one media type and cannot have it
 * is better served by the status it needs plus a body it did not ask for than by `406` and nothing
 * at all, so this writes the JSON directly.
 */
suspend fun ApplicationCall.respondNativeError(
  status: HttpStatusCode,
  code: String,
  message: String,
) {
  attributes.put(XoboroNativeErrorBodyWritten, Unit)
  respondText(
    text = NATIVE_ERROR_JSON.encodeToString(XoboroApiError.serializer(), XoboroApiError(code, message)),
    contentType = ContentType.Application.Json,
    status = status,
  )
}

/**
 * Matches the module's own serializer configuration, so an error body written here is byte-identical
 * to one that went through negotiation. `explicitNulls = false` is the setting that matters: a
 * nullable field must be omitted rather than sent as `null`, which is the shape every client of this
 * API is written against.
 */
private val NATIVE_ERROR_JSON = Json { explicitNulls = false }
