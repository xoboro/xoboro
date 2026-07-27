package io.xoboro.server

import io.xoboro.compatibility.komga.api.komgaClaimRoutes
import io.xoboro.compatibility.komga.api.komgaCatalogRoutes
import io.xoboro.compatibility.komga.api.komgaCatalogMaintenanceRoutes
import io.xoboro.compatibility.komga.api.komgaMediaRoutes
import io.xoboro.compatibility.komga.api.komgaPageHashRoutes
import io.xoboro.compatibility.komga.api.komgaMetadataRoutes
import io.xoboro.compatibility.komga.api.komgaOrganizationRoutes
import io.xoboro.compatibility.komga.api.komgaReadProgressRoutes
import io.xoboro.compatibility.komga.api.komgaWebPubRoutes
import io.xoboro.compatibility.komga.api.komgaAnnouncementRoutes
import io.xoboro.compatibility.komga.api.komgaArtworkRoutes
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
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogMaintenanceRequester
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryMaintenanceRequester
import io.xoboro.core.application.LibraryScanRequester
import io.xoboro.core.application.MetadataEditingLifecycle
import io.xoboro.core.application.MetadataFacetRepository
import io.xoboro.core.application.PageHashLifecycle
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.application.OrganizationLifecycle
import io.xoboro.core.application.RememberMeTokenService
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.OAuth2LoginLifecycle
import io.xoboro.core.application.ServerSettingsLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollectionRepository
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
    artworkLifecycle = runtime.artworkLifecycle,
    serverSettingsLifecycle = runtime.serverSettingsLifecycle,
    libraryAdministrationLifecycle = runtime.libraryAdministrationLifecycle,
    libraryMaintenanceRequester = runtime.libraryMaintenanceRequester,
    libraryScanRequester = runtime.libraryScanRequester,
    catalogReadRepository = runtime.catalogReadRepository,
    catalogMaintenanceRequester = runtime.catalogMaintenanceRequester,
    metadataEditingLifecycle = runtime.metadataEditingLifecycle,
    metadataFacetRepository = runtime.metadataFacetRepository,
    bookContentAccess = runtime.bookContentAccess,
    pageHashRepository = runtime.pageHashRepository,
    pageHashLifecycle = runtime.pageHashLifecycle,
    organizationLifecycle = runtime.organizationLifecycle,
    seriesCollectionRepository = runtime.seriesCollectionRepository,
    readListRepository = runtime.readListRepository,
    readProgressLifecycle = runtime.readProgressLifecycle,
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
  artworkLifecycle: ArtworkLifecycle? = null,
  serverSettingsLifecycle: ServerSettingsLifecycle? = null,
  libraryAdministrationLifecycle: LibraryAdministrationLifecycle? = null,
  libraryMaintenanceRequester: LibraryMaintenanceRequester? = null,
  libraryScanRequester: LibraryScanRequester? = null,
  catalogReadRepository: CatalogReadRepository? = null,
  catalogMaintenanceRequester: CatalogMaintenanceRequester? = null,
  metadataEditingLifecycle: MetadataEditingLifecycle? = null,
  metadataFacetRepository: MetadataFacetRepository? = null,
  bookContentAccess: BookContentAccess? = null,
  pageHashRepository: PageHashRepository? = null,
  pageHashLifecycle: PageHashLifecycle? = null,
  organizationLifecycle: OrganizationLifecycle? = null,
  seriesCollectionRepository: SeriesCollectionRepository? = null,
  readListRepository: ReadListRepository? = null,
  readProgressLifecycle: ReadProgressLifecycle? = null,
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
      if (
        artworkLifecycle != null &&
        catalogReadRepository != null &&
        bookContentAccess != null &&
        seriesCollectionRepository != null &&
        readListRepository != null
      ) {
        komgaArtworkRoutes(
          artwork = artworkLifecycle,
          catalog = catalogReadRepository,
          content = requireNotNull(bookContentAccess),
          collections = seriesCollectionRepository,
          readLists = readListRepository,
        )
      }
      serverSettingsLifecycle?.let(::komgaServerSettingsRoutes)
      libraryAdministrationLifecycle?.let { libraries ->
        komgaLibraryRoutes(
          libraries = libraries,
          scanRequester = requireNotNull(libraryScanRequester),
          maintenanceRequester = requireNotNull(libraryMaintenanceRequester),
        )
      }
      catalogReadRepository?.let(::komgaCatalogRoutes)
      catalogMaintenanceRequester?.let(::komgaCatalogMaintenanceRoutes)
      if (metadataEditingLifecycle != null && metadataFacetRepository != null) {
        komgaMetadataRoutes(metadataEditingLifecycle, metadataFacetRepository)
      }
      if (catalogReadRepository != null && bookContentAccess != null) {
        komgaMediaRoutes(catalogReadRepository, bookContentAccess)
      }
      if (
        pageHashRepository != null &&
        pageHashLifecycle != null &&
        bookContentAccess != null
      ) {
        komgaPageHashRoutes(
          hashes = pageHashRepository,
          lifecycle = pageHashLifecycle,
          content = bookContentAccess,
        )
      }
      if (
        catalogReadRepository != null &&
        organizationLifecycle != null &&
        seriesCollectionRepository != null &&
        readListRepository != null
      ) {
        komgaOrganizationRoutes(
          collections = seriesCollectionRepository,
          readLists = readListRepository,
          lifecycle = organizationLifecycle,
          catalog = catalogReadRepository,
        )
      }
      if (catalogReadRepository != null && readProgressLifecycle != null) {
        komgaReadProgressRoutes(catalogReadRepository, readProgressLifecycle)
      }
      if (
        catalogReadRepository != null &&
        readProgressLifecycle != null &&
        bookContentAccess != null
      ) {
        komgaWebPubRoutes(
          catalogReadRepository,
          readProgressLifecycle,
          bookContentAccess,
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
