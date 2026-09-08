package io.xoboro.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ClosedWriteChannelException
import io.xoboro.compatibility.komga.api.KepubContentAccess
import io.xoboro.compatibility.komga.api.KomgaSseEventHub
import io.xoboro.compatibility.komga.api.KomgaSseUserSnapshot
import io.xoboro.compatibility.komga.api.KomgaTaskStatusProvider
import io.xoboro.compatibility.komga.api.KoreaderSyncLifecycle
import io.xoboro.compatibility.komga.api.installKomgaBasicAuthentication
import io.xoboro.compatibility.komga.api.installKomgaCors
import io.xoboro.compatibility.komga.api.installKomgaSecurityHeaders
import io.xoboro.compatibility.komga.api.installKomgaShallowEtag
import io.xoboro.compatibility.komga.api.komgaAnnouncementRoutes
import io.xoboro.compatibility.komga.api.komgaArchiveRoutes
import io.xoboro.compatibility.komga.api.komgaArtworkRoutes
import io.xoboro.compatibility.komga.api.komgaAuthenticatedUserRoutes
import io.xoboro.compatibility.komga.api.komgaAuthenticationActivityRoutes
import io.xoboro.compatibility.komga.api.komgaCatalogMaintenanceRoutes
import io.xoboro.compatibility.komga.api.komgaCatalogRoutes
import io.xoboro.compatibility.komga.api.komgaClaimRoutes
import io.xoboro.compatibility.komga.api.komgaClientSettingsRoutes
import io.xoboro.compatibility.komga.api.komgaComicRackRoutes
import io.xoboro.compatibility.komga.api.komgaFileLifecycleRoutes
import io.xoboro.compatibility.komga.api.komgaFileSystemRoutes
import io.xoboro.compatibility.komga.api.komgaHistoryRoutes
import io.xoboro.compatibility.komga.api.komgaKoboRoutes
import io.xoboro.compatibility.komga.api.komgaKoreaderSyncRoutes
import io.xoboro.compatibility.komga.api.komgaLibraryRoutes
import io.xoboro.compatibility.komga.api.komgaMediaRoutes
import io.xoboro.compatibility.komga.api.komgaMetadataRoutes
import io.xoboro.compatibility.komga.api.komgaOAuth2Routes
import io.xoboro.compatibility.komga.api.komgaOpdsRoutes
import io.xoboro.compatibility.komga.api.komgaOpenApiRoutes
import io.xoboro.compatibility.komga.api.komgaOrganizationRoutes
import io.xoboro.compatibility.komga.api.komgaPageHashRoutes
import io.xoboro.compatibility.komga.api.komgaReadProgressRoutes
import io.xoboro.compatibility.komga.api.komgaServerResourceRoutes
import io.xoboro.compatibility.komga.api.komgaServerSettingsRoutes
import io.xoboro.compatibility.komga.api.komgaSessionRoutes
import io.xoboro.compatibility.komga.api.komgaSseRoutes
import io.xoboro.compatibility.komga.api.komgaSyncPointRoutes
import io.xoboro.compatibility.komga.api.komgaTachiyomiProgressRoutes
import io.xoboro.compatibility.komga.api.komgaTransientBookRoutes
import io.xoboro.compatibility.komga.api.komgaWebPubRoutes
import io.xoboro.compatibility.komga.api.respondError
import io.xoboro.core.application.AnnouncementLifecycle
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogFileLifecycleRequester
import io.xoboro.core.application.CatalogMaintenanceRequester
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.application.CompatibilityMaintenanceRequester
import io.xoboro.core.application.DatabaseBackupRequester
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.FontResourceCatalog
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryAvailabilityProbe
import io.xoboro.core.application.LibraryMaintenanceRequester
import io.xoboro.core.application.LibraryScanRequester
import io.xoboro.core.application.MediaSyncLifecycle
import io.xoboro.core.application.MetadataEditingLifecycle
import io.xoboro.core.application.MetadataFacetRepository
import io.xoboro.core.application.OAuth2LoginLifecycle
import io.xoboro.core.application.OperationalMetricsSnapshotProvider
import io.xoboro.core.application.OperationalStatusSnapshot
import io.xoboro.core.application.OrganizationLifecycle
import io.xoboro.core.application.PageHashLifecycle
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.application.ReadListImportLifecycle
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.RememberMeTokenService
import io.xoboro.core.application.SequentialReadProgressLifecycle
import io.xoboro.core.application.ServerReleaseCatalog
import io.xoboro.core.application.ServerSettingsLifecycle
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.application.TransientBookLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.CatalogChangeRepository
import io.xoboro.core.domain.HistoricalEventRepository
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SyncPointRepository
import io.xoboro.server.api.CrossSiteRequestRejectedException
import io.xoboro.server.api.XOBORO_API_PREFIX
import io.xoboro.server.api.XoboroInvalidQueryException
import io.xoboro.server.api.XoboroNativeErrorBodyWritten
import io.xoboro.server.api.XoboroNativeEventHub
import io.xoboro.server.api.configureXoboroNativeAuthentication
import io.xoboro.server.api.configureXoboroNativeRateLimits
import io.xoboro.server.api.respondNativeError
import io.xoboro.server.api.xoboroNativeArchiveRoutes
import io.xoboro.server.api.xoboroNativeArtworkRoutes
import io.xoboro.server.api.xoboroNativeAuthenticationRoutes
import io.xoboro.server.api.xoboroNativeCatalogRoutes
import io.xoboro.server.api.xoboroNativeChangeRoutes
import io.xoboro.server.api.xoboroNativeCollectionsRoutes
import io.xoboro.server.api.xoboroNativeDeliveryRoutes
import io.xoboro.server.api.xoboroNativeDuplicatePageRoutes
import io.xoboro.server.api.xoboroNativeEventRoutes
import io.xoboro.server.api.xoboroNativeLibraryAdminRoutes
import io.xoboro.server.api.xoboroNativeMetadataRoutes
import io.xoboro.server.api.xoboroNativeOAuth2Routes
import io.xoboro.server.api.xoboroNativeOpsRoutes
import io.xoboro.server.api.xoboroNativeProgressRoutes
import io.xoboro.server.api.xoboroNativeSelfServiceRoutes
import io.xoboro.server.api.xoboroNativeUserAdminRoutes
import io.xoboro.server.persistence.DatabaseBackupManager
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.KomgaDatabaseImporter
import io.xoboro.server.persistence.XoboroDatabase
import java.nio.file.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
  val config = ServerConfig.fromEnvironment()
  if (runDatabaseCommand(args, config)) return
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

internal fun runDatabaseCommand(
  args: Array<String>,
  config: ServerConfig,
  output: (String) -> Unit = ::println,
): Boolean {
  if (args.isEmpty()) return false
  require(args.size >= 2) {
    "Usage: xoboro <backup|restore|verify-backup|import-komga> <path> [--replace] [--dry-run]"
  }
  val path = Path.of(args[1]).toAbsolutePath().normalize()
  val options = args.drop(2).toSet()
  require(options.size == args.size - 2 && options.all { it in setOf("--replace", "--dry-run") }) {
    "Unknown or duplicate database command option"
  }
  val replace = "--replace" in options
  val dryRun = "--dry-run" in options
  when (args[0]) {
    "backup" -> {
      require(!dryRun) { "backup does not accept --dry-run" }
      XoboroDatabase.open(DatabaseConfig(config.databasePath)).use { database ->
        output(database.backups.create(path, replaceExisting = replace).toString())
      }
    }
    "restore" -> {
      require(!dryRun) { "restore does not accept --dry-run" }
      output(
        DatabaseBackupManager
          .restore(path, config.databasePath, replaceExisting = replace)
          .toString(),
      )
    }
    "verify-backup" -> {
      require(!replace && !dryRun) {
        "verify-backup does not accept --replace or --dry-run"
      }
      DatabaseBackupManager.verify(path)
      output(path.toString())
    }
    "import-komga" -> {
      require(!(replace && dryRun)) { "--replace and --dry-run cannot be combined" }
      XoboroDatabase.open(DatabaseConfig(config.databasePath)).use { database ->
        val importer = KomgaDatabaseImporter(database)
        val report =
          if (dryRun) {
            importer.inspect(path)
          } else {
            importer.import(path, replaceExisting = replace)
          }
        output(
          report.render(
            heading =
              if (dryRun) "Komga inspection complete; target was not imported" else
                "Komga import complete",
          ),
        )
      }
    }
    else -> error("Unknown command: ${args[0]}")
  }
  return true
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
    libraryAvailabilityProbe = runtime.libraryAvailabilityProbe,
    libraryMaintenanceRequester = runtime.libraryMaintenanceRequester,
    libraryScanRequester = runtime.libraryScanRequester,
    catalogReadRepository = runtime.catalogReadRepository,
    catalogChangeRepository = runtime.catalogChangeRepository,
    catalogMaintenanceRequester = runtime.catalogMaintenanceRequester,
    catalogFileLifecycleRequester = runtime.catalogFileLifecycleRequester,
    transientBookLifecycle = runtime.transientBookLifecycle,
    sequentialReadProgressLifecycle = runtime.sequentialReadProgressLifecycle,
    historicalEventRepository = runtime.historicalEventRepository,
    syncPointRepository = runtime.syncPointRepository,
    mediaSyncLifecycle = runtime.mediaSyncLifecycle,
    readListImportLifecycle = runtime.readListImportLifecycle,
    fontResourceCatalog = runtime.fontResourceCatalog,
    serverReleaseCatalog = runtime.serverReleaseCatalog,
    compatibilityMaintenanceRequester = runtime.compatibilityMaintenanceRequester,
    metadataEditingLifecycle = runtime.metadataEditingLifecycle,
    metadataFacetRepository = runtime.metadataFacetRepository,
    bookContentAccess = runtime.bookContentAccess,
    kepubContentAccess = runtime.kepubContentAccess,
    pageHashRepository = runtime.pageHashRepository,
    pageHashLifecycle = runtime.pageHashLifecycle,
    organizationLifecycle = runtime.organizationLifecycle,
    seriesCollectionRepository = runtime.seriesCollectionRepository,
    readListRepository = runtime.readListRepository,
    readProgressLifecycle = runtime.readProgressLifecycle,
    koreaderSyncLifecycle = runtime.koreaderSyncLifecycle,
    sseEventHub = runtime.sseEventHub,
    nativeEventHub = runtime.nativeEventHub,
    sseTaskStatusProvider = runtime.sseTaskStatusProvider,
    durableTaskQueue = runtime.durableTaskQueue,
    databaseBackupRequester = runtime.databaseBackupRequester,
    libraryRepository = runtime.libraryRepository,
    contextPath = runtime.effectiveServerContextPath,
    webDirectory = runtime.webDirectory,
    corsAllowedOrigins = runtime.corsAllowedOrigins,
    trustedProxyHosts = runtime.trustedProxyHosts,
    metricsToken = runtime.metricsToken,
    taskQueueSize = { runtime.sseTaskStatusProvider.snapshot().count },
    workerCount = runtime::taskWorkerCount,
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
  libraryAvailabilityProbe: LibraryAvailabilityProbe? = null,
  libraryMaintenanceRequester: LibraryMaintenanceRequester? = null,
  libraryScanRequester: LibraryScanRequester? = null,
  catalogReadRepository: CatalogReadRepository? = null,
  catalogChangeRepository: CatalogChangeRepository? = null,
  catalogMaintenanceRequester: CatalogMaintenanceRequester? = null,
  catalogFileLifecycleRequester: CatalogFileLifecycleRequester? = null,
  transientBookLifecycle: TransientBookLifecycle? = null,
  sequentialReadProgressLifecycle: SequentialReadProgressLifecycle? = null,
  historicalEventRepository: HistoricalEventRepository? = null,
  syncPointRepository: SyncPointRepository? = null,
  mediaSyncLifecycle: MediaSyncLifecycle? = null,
  readListImportLifecycle: ReadListImportLifecycle? = null,
  fontResourceCatalog: FontResourceCatalog? = null,
  serverReleaseCatalog: ServerReleaseCatalog? = null,
  compatibilityMaintenanceRequester: CompatibilityMaintenanceRequester? = null,
  metadataEditingLifecycle: MetadataEditingLifecycle? = null,
  metadataFacetRepository: MetadataFacetRepository? = null,
  bookContentAccess: BookContentAccess? = null,
  kepubContentAccess: KepubContentAccess? = null,
  pageHashRepository: PageHashRepository? = null,
  pageHashLifecycle: PageHashLifecycle? = null,
  organizationLifecycle: OrganizationLifecycle? = null,
  seriesCollectionRepository: SeriesCollectionRepository? = null,
  readListRepository: ReadListRepository? = null,
  readProgressLifecycle: ReadProgressLifecycle? = null,
  koreaderSyncLifecycle: KoreaderSyncLifecycle? = null,
  sseEventHub: KomgaSseEventHub? = null,
  nativeEventHub: XoboroNativeEventHub? = null,
  sseTaskStatusProvider: KomgaTaskStatusProvider? = null,
  durableTaskQueue: DurableTaskQueue? = null,
  databaseBackupRequester: DatabaseBackupRequester? = null,
  libraryRepository: LibraryRepository? = null,
  contextPath: String? = null,
  webDirectory: Path? = null,
  corsAllowedOrigins: Set<String> = emptySet(),
  trustedProxyHosts: Set<String> = emptySet(),
  metricsToken: String? = null,
  taskQueueSize: () -> Int = { 0 },
  workerCount: () -> Int = { 0 },
) {
  monitor.subscribe(ApplicationStopped) {
    onStop()
  }
  // Created unconditionally: the raw Prometheus scrape route below stays gated behind
  // metricsToken, but the native JSON metrics endpoint is available to any authenticated
  // administrator and should not require operators to also configure a separate scrape secret.
  val operationalMetrics = OperationalMetrics()
  install(CallLogging) {
    format { call ->
      val status = call.response.status()?.value ?: 0
      "${call.request.httpMethod.value} ${call.request.path()} $status"
    }
  }
  install(ContentNegotiation) {
    json(
      Json {
        explicitNulls = false
      },
    )
  }
  installKomgaShallowEtag()
  installKomgaSecurityHeaders()
  installKomgaCors(corsAllowedOrigins)
  val nativeSessions = userSessionLifecycle
  userLifecycle?.let {
    installKomgaBasicAuthentication(
      users = it,
      apiKeys = apiKeyLifecycle,
      authenticationActivities = authenticationActivityLifecycle,
      sessions = userSessionLifecycle,
      rememberMe = rememberMeTokenService,
      additionalConfiguration = {
        nativeSessions?.let { sessions ->
          configureXoboroNativeAuthentication(sessions, rememberMeTokenService)
        }
      },
    )
  }
  install(StatusPages) {
    status(HttpStatusCode.Forbidden, HttpStatusCode.NotFound) { call, status ->
      if (call.attributes.contains(XoboroNativeErrorBodyWritten)) {
        return@status
      }
      if (call.request.path().isXoboroNativeApiPath()) {
        // `respondNativeError`, not `respond`: an error body must not go through content
        // negotiation. A caller whose `Accept` header does not admit JSON used to get `406` here
        // and never learn the real status - see the function's own documentation for the case that
        // made this concrete.
        call.respondNativeError(
          status,
          code = if (status == HttpStatusCode.NotFound) "not_found" else "forbidden",
          message = status.description,
        )
      } else if (
        call.request.header(io.ktor.http.HttpHeaders.Origin) == null &&
        call.request.path().isSpringErrorSurface()
      ) {
        call.respondError(
          status,
          if (status == HttpStatusCode.NotFound) "404 NOT_FOUND" else status.description,
        )
      }
    }
    status(HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests) { call, status ->
      if (call.attributes.contains(XoboroNativeErrorBodyWritten)) {
        return@status
      }
      if (call.request.path().isXoboroNativeApiPath()) {
        call.respondNativeError(
          status,
          code =
            if (status == HttpStatusCode.TooManyRequests) {
              "rate_limit_exceeded"
            } else {
              "authentication_required"
            },
          message = status.description,
        )
      }
    }
    exception<BadRequestException> { call, cause ->
      if (call.request.path().isXoboroNativeApiPath()) {
        call.respondNativeError(
          HttpStatusCode.BadRequest,
          code = "invalid_request",
          message = "Malformed request",
        )
      } else if (call.request.path().isSpringErrorSurface()) {
        call.respondError(
          HttpStatusCode.BadRequest,
          cause.message ?: HttpStatusCode.BadRequest.description,
        )
      } else {
        throw cause
      }
    }
    exception<ContentTransformationException> { call, cause ->
      if (call.request.path().isXoboroNativeApiPath()) {
        call.respondNativeError(
          HttpStatusCode.BadRequest,
          code = "invalid_request",
          message = "Malformed JSON request",
        )
      } else if (call.request.path().isSpringErrorSurface()) {
        call.respondError(
          HttpStatusCode.BadRequest,
          cause.message ?: HttpStatusCode.BadRequest.description,
        )
      } else {
        throw cause
      }
    }
    exception<CrossSiteRequestRejectedException> { call, cause ->
      call.respondNativeError(
        status = HttpStatusCode.Forbidden,
        code = CrossSiteRequestRejectedException.CODE,
        message = requireNotNull(cause.message),
      )
    }
    exception<XoboroInvalidQueryException> { call, cause ->
      call.respondNativeError(
        status = HttpStatusCode.BadRequest,
        code = "invalid_query",
        message = requireNotNull(cause.message),
      )
    }
    exception<ClosedWriteChannelException> { _, _ ->
      // A streaming response can outlive its browser. The response is already committed, so an
      // ordinary disconnect is complete: do not log it as a server failure or attempt a 500 body.
    }
    exception<ChannelWriteException> { _, _ ->
      // Netty can wrap the same closed response in its CIO write exception.
    }
    exception<Throwable> { call, cause ->
      call.application.environment.log.error("Unhandled request failure", cause)
      if (call.request.path().isSpringErrorSurface()) {
        call.respondError(
          HttpStatusCode.InternalServerError,
          cause.message ?: "Internal server error",
        )
      } else {
        call.respond(
          status = HttpStatusCode.InternalServerError,
          message = ErrorResponse(code = "internal_error", message = "Internal server error"),
        )
      }
    }
  }
  install(SSE)
  installTrustedProxyHeaders(trustedProxyHosts)
  if (userLifecycle != null && nativeSessions != null) {
    install(RateLimit) {
      configureXoboroNativeRateLimits()
    }
  }
  installOperationalMetrics(operationalMetrics)
  val operationalMetricsSnapshotProvider =
    OperationalMetricsSnapshotProvider {
      OperationalStatusSnapshot(
        ready = readiness(),
        uptimeSeconds = operationalMetrics.uptimeSeconds(),
        activeRequests = operationalMetrics.activeRequestCount(),
        totalRequests = operationalMetrics.totalRequestCount(),
        requestsByStatusClass = operationalMetrics.requestCountsByStatusClass(),
        taskQueue = durableTaskQueue?.counts() ?: TaskCounts(pending = 0, running = 0, dead = 0),
        taskWorkerCount = workerCount(),
      )
    }

  routing {
    val routes: Route.() -> Unit = {
      get("/health") {
        call.respond(HealthResponse())
      }
      xoboroOpenApiRoutes()
      metricsToken?.let { token ->
        operationalMetricsRoute(
          token = token,
          metrics = operationalMetrics,
          readiness = readiness,
          taskQueueSize = taskQueueSize,
          workerCount = workerCount,
        )
      }
      komgaOpenApiRoutes()
      get("/ready") {
        val ready = readiness()
        call.respond(
          status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
          message = HealthResponse(status = if (ready) "UP" else "DOWN"),
        )
      }
      userLifecycle?.let {
        nativeSessions?.let { sessions ->
          xoboroNativeAuthenticationRoutes(
            users = it,
            sessions = sessions,
            rememberMe = rememberMeTokenService,
            activities = authenticationActivityLifecycle,
          )
          xoboroNativeUserAdminRoutes(it)
          if (apiKeyLifecycle == null) {
            xoboroNativeSelfServiceRoutes(it)
          } else {
            xoboroNativeSelfServiceRoutes(it, apiKeyLifecycle)
          }
          if (nativeEventHub != null) {
            xoboroNativeEventRoutes(hub = nativeEventHub, sessions = sessions)
          }
          oauth2LoginLifecycle?.let(::xoboroNativeOAuth2Routes)
          if (
            pageHashRepository != null &&
            pageHashLifecycle != null &&
            compatibilityMaintenanceRequester != null
          ) {
            // Reuses the same durable requester instance the Komga-compatible surface enqueues
            // removals through - there is exactly one thing in the process that turns a delete
            // decision into a queued task, native or compatible.
            xoboroNativeDuplicatePageRoutes(
              pageHashRepository,
              pageHashLifecycle,
              compatibilityMaintenanceRequester,
            )
          }
          if (
            serverSettingsLifecycle != null &&
            clientSettingsLifecycle != null &&
            authenticationActivityLifecycle != null &&
            historicalEventRepository != null &&
            durableTaskQueue != null &&
            catalogReadRepository != null &&
            catalogMaintenanceRequester != null &&
            databaseBackupRequester != null
          ) {
            xoboroNativeOpsRoutes(
              serverSettings = serverSettingsLifecycle,
              clientSettings = clientSettingsLifecycle,
              authenticationActivities = authenticationActivityLifecycle,
              history = historicalEventRepository,
              tasks = durableTaskQueue,
              catalog = catalogReadRepository,
              catalogMaintenance = catalogMaintenanceRequester,
              backups = databaseBackupRequester,
              operationalMetrics = operationalMetricsSnapshotProvider,
            )
          }
          if (catalogReadRepository != null && artworkLifecycle != null) {
            xoboroNativeArtworkRoutes(catalogReadRepository, artworkLifecycle)
          }
          if (
            organizationLifecycle != null &&
            seriesCollectionRepository != null &&
            readListRepository != null &&
            catalogReadRepository != null
          ) {
            xoboroNativeCollectionsRoutes(
              organization = organizationLifecycle,
              collections = seriesCollectionRepository,
              readLists = readListRepository,
              catalog = catalogReadRepository,
            )
          }
          if (
            readListRepository != null &&
            catalogReadRepository != null &&
            bookContentAccess != null
          ) {
            xoboroNativeArchiveRoutes(
              catalog = catalogReadRepository,
              readLists = readListRepository,
              content = bookContentAccess,
            )
          }
          catalogChangeRepository?.let { xoboroNativeChangeRoutes(it) }
          if (libraryAdministrationLifecycle != null && catalogReadRepository != null) {
            xoboroNativeCatalogRoutes(
              libraries = libraryAdministrationLifecycle,
              catalog = catalogReadRepository,
            )
            if (
              libraryScanRequester != null &&
              libraryMaintenanceRequester != null &&
              libraryAvailabilityProbe != null
            ) {
              xoboroNativeLibraryAdminRoutes(
                libraries = libraryAdministrationLifecycle,
                scanRequester = libraryScanRequester,
                maintenanceRequester = libraryMaintenanceRequester,
                availabilityProbe = libraryAvailabilityProbe,
              )
            }
            if (bookContentAccess != null) {
              xoboroNativeDeliveryRoutes(catalogReadRepository, bookContentAccess)
            }
            readProgressLifecycle?.let {
              xoboroNativeProgressRoutes(catalogReadRepository, it)
            }
            if (metadataEditingLifecycle != null && metadataFacetRepository != null) {
              xoboroNativeMetadataRoutes(
                catalogReadRepository,
                metadataEditingLifecycle,
                metadataFacetRepository,
              )
            }
          }
        }
        komgaFileSystemRoutes()
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
      catalogFileLifecycleRequester?.let(::komgaFileLifecycleRoutes)
      transientBookLifecycle?.let(::komgaTransientBookRoutes)
      sequentialReadProgressLifecycle?.let(::komgaTachiyomiProgressRoutes)
      historicalEventRepository?.let(::komgaHistoryRoutes)
      syncPointRepository?.let(::komgaSyncPointRoutes)
      readListImportLifecycle?.let(::komgaComicRackRoutes)
      if (
        fontResourceCatalog != null &&
        serverReleaseCatalog != null &&
        compatibilityMaintenanceRequester != null &&
        pageHashRepository != null
      ) {
        komgaServerResourceRoutes(
          fonts = fontResourceCatalog,
          releases = serverReleaseCatalog,
          maintenance = compatibilityMaintenanceRequester,
          pageHashes = pageHashRepository,
          applicationVersion = APPLICATION_VERSION,
        )
      }
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
      if (
        apiKeyLifecycle != null &&
        mediaSyncLifecycle != null &&
        catalogReadRepository != null &&
        readProgressLifecycle != null &&
        artworkLifecycle != null &&
        bookContentAccess != null
      ) {
        komgaKoboRoutes(
          apiKeys = apiKeyLifecycle,
          sync = mediaSyncLifecycle,
          catalog = catalogReadRepository,
          progress = readProgressLifecycle,
          artwork = artworkLifecycle,
          content = bookContentAccess,
          kepub = kepubContentAccess,
        )
      }
      if (
        catalogReadRepository != null &&
        readListRepository != null &&
        bookContentAccess != null
      ) {
        komgaArchiveRoutes(
          catalog = catalogReadRepository,
          readLists = readListRepository,
          content = bookContentAccess,
        )
      }
      if (catalogReadRepository != null && readProgressLifecycle != null) {
        komgaReadProgressRoutes(catalogReadRepository, readProgressLifecycle)
      }
      koreaderSyncLifecycle?.let(::komgaKoreaderSyncRoutes)
      // userLifecycle also gates installKomgaBasicAuthentication above, which registers the
      // providers this route authenticates against — so requiring it here does not drop a
      // configuration that could ever have authenticated a subscriber.
      if (sseEventHub != null && sseTaskStatusProvider != null && userLifecycle != null) {
        komgaSseRoutes(
          events = sseEventHub,
          users = KomgaSseUserSnapshot(userLifecycle::findByIdOrNull),
          tasks = sseTaskStatusProvider,
        )
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
      if (
        catalogReadRepository != null &&
        libraryRepository != null &&
        seriesCollectionRepository != null &&
        readListRepository != null &&
        artworkLifecycle != null &&
        bookContentAccess != null &&
        readProgressLifecycle != null
      ) {
        komgaOpdsRoutes(
          catalog = catalogReadRepository,
          libraries = libraryRepository,
          collections = seriesCollectionRepository,
          readLists = readListRepository,
          artwork = artworkLifecycle,
          content = bookContentAccess,
          progress = readProgressLifecycle,
          facets = metadataFacetRepository,
        )
      }
      oauth2LoginLifecycle?.let { oauth2 ->
        komgaOAuth2Routes(
          oauth2 = oauth2,
          sessions = requireNotNull(userSessionLifecycle),
          authenticationActivities = authenticationActivityLifecycle,
        )
      }
      // Last on purpose. This is the only wildcard in the tree, and registering it
      // before the API routes would let it answer for paths they own.
      webDirectory?.let(::xoboroWebAssetRoutes)
    }
    if (contextPath == null) {
      routes()
    } else {
      route(contextPath, routes)
    }
  }
}

private fun String.isXoboroNativeApiPath(): Boolean =
  indexOf(XOBORO_API_PREFIX).let { prefixIndex ->
    prefixIndex >= 0 &&
      getOrNull(prefixIndex + XOBORO_API_PREFIX.length).let { boundary ->
        boundary == null || boundary == '/'
      }
  }

private fun String.isSpringErrorSurface(): Boolean =
  contains("/api/") || contains("/opds/")

private const val APPLICATION_VERSION = "0.1.0-SNAPSHOT"

/**
 * The `/health` and `/ready` body.
 *
 * Both fields are annotated because kotlinx.serialization omits values equal to
 * their default, and the installed `Json` does not set `encodeDefaults`. Without
 * the annotations `/health` answers `{}`, and `/ready` answers `{}` when the
 * server is up but `{"status":"DOWN"}` when it is not - so a probe checking for
 * `"status":"UP"` never sees it, and the healthy case is the one that looks
 * broken. `encodeDefaults` is deliberately not turned on globally: it would
 * change the shape of every native and Komga-compatible response at once.
 */
@Serializable
data class HealthResponse(
  @EncodeDefault val status: String = "UP",
  @EncodeDefault val service: String = "xoboro",
)

@Serializable
data class ErrorResponse(
  val code: String,
  val message: String,
)
