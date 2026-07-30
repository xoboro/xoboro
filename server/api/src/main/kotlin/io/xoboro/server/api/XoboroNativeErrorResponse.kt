package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

/**
 * Marks a call whose native error body was written by the route itself with a specific code, so the
 * application-wide StatusPages handler leaves that body alone instead of flattening it to a generic
 * code.
 */
val XoboroNativeErrorBodyWritten: AttributeKey<Unit> = AttributeKey("XoboroNativeErrorBodyWritten")

suspend fun ApplicationCall.respondNativeError(
  status: HttpStatusCode,
  code: String,
  message: String,
) {
  attributes.put(XoboroNativeErrorBodyWritten, Unit)
  respond(status, XoboroApiError(code, message))
}
