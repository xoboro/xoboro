package io.xoboro.server

import io.xoboro.compatibility.komga.api.komgaClaimRoutes
import io.xoboro.compatibility.komga.api.komgaAnnouncementRoutes
import io.xoboro.compatibility.komga.api.komgaClientSettingsRoutes
import io.xoboro.compatibility.komga.api.komgaAuthenticationActivityRoutes
import io.xoboro.compatibility.komga.api.komgaSessionRoutes
import io.xoboro.compatibility.komga.api.komgaServerSettingsRoutes
import io.xoboro.compatibility.komga.api.komgaOAuth2Routes
import io.xoboro.compatibility.komga.api.komgaLibraryRoutes
import io.xoboro.compatibility.komga.api.installKomgaBasicAuthentication
import io.xoboro.compatibility.komga.api.komgaAuthenticatedUserRoutes
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.AnnouncementLifecycle
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryScanRequester
import io.xoboro.core.application.RememberMeTokenService
import io.xoboro.core.application.OAuth2LoginLifecycle
import io.xoboro.core.application.ServerSettingsLifecycle
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main() {
  val config = ServerConfig.fromEnvironment()
  XoboroRuntime.open(config).use { runtime ->
    embeddedServer(
      factory = Netty,
      host = "0.0.0.0",
      port = runtime.effectiveServerPort,
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
    rememberMeTokenService = runtime.rememberMeTokenService,
    oauth2LoginLifecycle = runtime.oauth2LoginLifecycle,
    clientSettingsLifecycle = runtime.clientSettingsLifecycle,
    announcementLifecycle = runtime.announcementLifecycle,
    serverSettingsLifecycle = runtime.serverSettingsLifecycle,
    libraryAdministrationLifecycle = runtime.libraryAdministrationLifecycle,
    libraryScanRequester = runtime.libraryScanRequester,
    libraryRepository = runtime.libraryRepository,
    contextPath = runtime.effectiveServerContextPath,
  )
}

fun Application.xoboroModule(
  readiness: () -> Boolean = { true },
  onStop: () -> Unit = {},
  userLifecycle: UserLifecycle? = null,
  apiKeyLifecycle: ApiKeyLifecycle? = null,
  authenticationActivityLifecycle: AuthenticationActivityLifecycle? = null,
  userSessionLifecycle: UserSessionLifecycle? = null,
  rememberMeTokenService: RememberMeTokenService? = null,
  oauth2LoginLifecycle: OAuth2LoginLifecycle? = null,
  clientSettingsLifecycle: ClientSettingsLifecycle? = null,
  announcementLifecycle: AnnouncementLifecycle? = null,
  serverSettingsLifecycle: ServerSettingsLifecycle? = null,
  libraryAdministrationLifecycle: LibraryAdministrationLifecycle? = null,
  libraryScanRequester: LibraryScanRequester? = null,
  libraryRepository: LibraryRepository? = null,
  contextPath: String? = null,
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
      rememberMe = rememberMeTokenService,
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
    val routes: Route.() -> Unit = {
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
      clientSettingsLifecycle?.let(::komgaClientSettingsRoutes)
      announcementLifecycle?.let(::komgaAnnouncementRoutes)
      serverSettingsLifecycle?.let(::komgaServerSettingsRoutes)
      libraryAdministrationLifecycle?.let { libraries ->
        komgaLibraryRoutes(
          libraries = libraries,
          scanRequester = requireNotNull(libraryScanRequester),
        )
      }
      oauth2LoginLifecycle?.let { oauth2 ->
        komgaOAuth2Routes(
          oauth2 = oauth2,
          sessions = requireNotNull(userSessionLifecycle),
          authenticationActivities = authenticationActivityLifecycle,
        )
      }
    }
    if (contextPath == null) {
      routes()
    } else {
      route(contextPath, routes)
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
