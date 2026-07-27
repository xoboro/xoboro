package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.SyncPointRepository

fun Route.komgaSyncPointRoutes(syncPoints: SyncPointRepository) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    delete("/api/v1/syncpoints/me") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      val keyIds =
        call.request.queryParameters
          .getAll("key_id")
          .orEmpty()
          .flatMap { value -> value.split(',') }
          .map(String::trim)
          .filter(String::isNotEmpty)
          .map(::ApiKeyId)
      if (keyIds.isEmpty()) {
        syncPoints.deleteByUserId(principal.user.id)
      } else {
        syncPoints.deleteByUserIdAndApiKeyIds(principal.user.id, keyIds)
      }
      call.respond(HttpStatusCode.NoContent)
    }
  }
}
