package io.xoboro.server

import io.xoboro.compatibility.komga.api.komgaClaimRoutes
import io.xoboro.compatibility.komga.api.komgaAuthenticationActivityRoutes
import io.xoboro.compatibility.komga.api.komgaSessionRoutes
import io.xoboro.compatibility.komga.api.installKomgaBasicAuthentication
import io.xoboro.compatibility.komga.api.komgaAuthenticatedUserRoutes
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.LibraryRepository
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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
import kotlinx.serialization.json.Json

fun main() {
  val config = ServerConfig.fromEnvironment()
  XoboroRuntime.open(config).use { runtime ->
    embeddedServer(
      factory = Netty,
      host = "0.0.0.0",
      port = config.port,
      module = {
        xoboroModule(runtime)
      },
    ).start(wait = true)
  }
}

fun Application.xoboroModule(runtime: XoboroRuntime) {
  xoboroModule(
    readiness = runtime::isReady,
    onStop = runtime::close,
    userLifecycle = runtime.userLifecycle,
    apiKeyLifecycle = runtime.apiKeyLifecycle,
    authenticationActivityLifecycle = runtime.authenticationActivityLifecycle,
    userSessionLifecycle = runtime.userSessionLifecycle,
    libraryRepository = runtime.libraryRepository,
  )
}

fun Application.xoboroModule(
  readiness: () -> Boolean = { true },
  onStop: () -> Unit = {},
  userLifecycle: UserLifecycle? = null,
  apiKeyLifecycle: ApiKeyLifecycle? = null,
  authenticationActivityLifecycle: AuthenticationActivityLifecycle? = null,
  userSessionLifecycle: UserSessionLifecycle? = null,
  libraryRepository: LibraryRepository? = null,
) {
  monitor.subscribe(ApplicationStopped) {
    onStop()
  }
  install(CallLogging)
  install(ContentNegotiation) {
    json(
      Json {
        explicitNulls = false
      },
    )
  }
  userLifecycle?.let {
    installKomgaBasicAuthentication(
      users = it,
      apiKeys = apiKeyLifecycle,
      authenticationActivities = authenticationActivityLifecycle,
      sessions = userSessionLifecycle,
    )
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
    get("/ready") {
      val ready = readiness()
      call.respond(
        status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        message = HealthResponse(status = if (ready) "UP" else "DOWN"),
      )
    }
    userLifecycle?.let {
      komgaClaimRoutes(it)
      komgaAuthenticatedUserRoutes(
        users = it,
        libraries = requireNotNull(libraryRepository),
        apiKeys = apiKeyLifecycle,
      )
      authenticationActivityLifecycle?.let { activities ->
        komgaAuthenticationActivityRoutes(
          users = it,
          activities = activities,
        )
      }
      userSessionLifecycle?.let(::komgaSessionRoutes)
    }
  }
}

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
