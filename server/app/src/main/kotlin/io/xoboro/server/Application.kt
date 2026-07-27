package io.xoboro.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

private const val DEFAULT_PORT = 25600

fun main() {
  embeddedServer(
    factory = Netty,
    host = "0.0.0.0",
    port = configuredPort(),
    module = Application::xoboroModule,
  ).start(wait = true)
}

fun Application.xoboroModule() {
  install(CallLogging)
  install(ContentNegotiation) {
    json()
  }
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      call.application.environment.log.error("Unhandled request failure", cause)
      call.respond(
        status = HttpStatusCode.InternalServerError,
        message = ErrorResponse(code = "internal_error", message = "Internal server error"),
      )
    }
  }

  routing {
    get("/health") {
      call.respond(HealthResponse())
    }
  }
}

private fun configuredPort(): Int =
  System.getenv("XOBORO_PORT")
    ?.toIntOrNull()
    ?.takeIf { it in 1..65535 }
    ?: DEFAULT_PORT

@Serializable
data class HealthResponse(
  val status: String = "UP",
  val service: String = "xoboro",
)

@Serializable
data class ErrorResponse(
  val code: String,
  val message: String,
)
