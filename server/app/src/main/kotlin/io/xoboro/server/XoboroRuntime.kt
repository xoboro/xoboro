package io.xoboro.server

import com.github.f4b6a3.tsid.TsidCreator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.xoboro.compatibility.komga.api.KepubContentAccess
import io.xoboro.compatibility.komga.api.KomgaSseEventBridge
import io.xoboro.compatibility.komga.api.KomgaSseEventHub
import io.xoboro.compatibility.komga.api.KomgaTaskQueueSseDto
import io.xoboro.compatibility.komga.api.KomgaTaskStatusProvider
import io.xoboro.compatibility.komga.api.KoreaderSyncLifecycle
import io.xoboro.core.application.ActivityRetentionLifecycle
import io.xoboro.core.application.AnnouncementLifecycle
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.ArtworkProcessor
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogFileLifecycleRequester
import io.xoboro.core.application.CatalogMaintenanceRequester
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.application.CompatibilityMaintenanceRequester
import io.xoboro.core.application.DatabaseBackupRequester
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.FontResourceCatalog
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryAvailabilityLifecycle
import io.xoboro.core.application.LibraryAvailabilityProbe
import io.xoboro.core.application.LibraryEvent
import io.xoboro.core.application.LibraryEventPublisher
import io.xoboro.core.application.LibraryLifecycle
import io.xoboro.core.application.LibraryMaintenanceQueue
import io.xoboro.core.application.LibraryMaintenanceRequester
import io.xoboro.core.application.LibraryScanRequester
import io.xoboro.core.application.LocalArtworkRefreshLifecycle
import io.xoboro.core.application.MediaSyncLifecycle
import io.xoboro.core.application.MetadataEditingLifecycle
import io.xoboro.core.application.MetadataFacetRepository
import io.xoboro.core.application.MetadataRefreshLifecycle
import io.xoboro.core.application.OAuth2LoginLifecycle
import io.xoboro.core.application.OrganizationLifecycle
import io.xoboro.core.application.PageHashLifecycle
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.application.ReadListImportLifecycle
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.RememberMeTokenService
import io.xoboro.core.application.RoutingLibraryRootAccess
import io.xoboro.core.application.SequentialReadProgressLifecycle
import io.xoboro.core.application.ServerReleaseCatalog
import io.xoboro.core.application.ServerSettingsLifecycle
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.application.TransientBookLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.HistoricalEventRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaItemRepository
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.ServerAlreadyClaimedException
import io.xoboro.core.domain.SyncPointRepository
import io.xoboro.server.api.XoboroNativeEventBridge
import io.xoboro.server.api.XoboroNativeEventHub
import io.xoboro.server.media.AnalyzeBook
import io.xoboro.server.media.BookContentService
import io.xoboro.server.media.LocalFontResourceCatalog
import io.xoboro.server.media.LocalTransientBookLifecycle
import io.xoboro.server.media.RarToCbzConverter
import io.xoboro.server.media.SafeJpegArtworkProcessor
import io.xoboro.server.media.ZipMediaAnalyzer
import io.xoboro.server.metadata.ComicInfoMetadataProvider
import io.xoboro.server.metadata.ComicRackReadListParser
import io.xoboro.server.metadata.EpubMetadataProvider
import io.xoboro.server.metadata.IsbnBarcodeMetadataProvider
import io.xoboro.server.metadata.MylarSeriesDiagnostic
import io.xoboro.server.metadata.MylarSeriesMetadataProvider
import io.xoboro.server.metadata.OneShotSeriesMetadataProvider
import io.xoboro.server.metadata.PdfMetadataProvider
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqAnnouncementReadRepository
import io.xoboro.server.persistence.JooqApiKeyRepository
import io.xoboro.server.persistence.JooqArtworkRepository
import io.xoboro.server.persistence.JooqAuthenticationActivityRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookMetadataAggregationRepository
import io.xoboro.server.persistence.JooqBookMetadataRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReadRepository
import io.xoboro.server.persistence.JooqCatalogReconciliationStore
import io.xoboro.server.persistence.JooqClientSettingsRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqHistoricalEventRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqLibraryTrashStore
import io.xoboro.server.persistence.JooqMediaItemFingerprintIndex
import io.xoboro.server.persistence.JooqMediaItemRepository
import io.xoboro.server.persistence.JooqMediaSyncSnapshotRepository
import io.xoboro.server.persistence.JooqMetadataFacetRepository
import io.xoboro.server.persistence.JooqMetadataOrganizationWriter
import io.xoboro.server.persistence.JooqPageHashRepository
import io.xoboro.server.persistence.JooqReadListImportMatcher
import io.xoboro.server.persistence.JooqReadListRepository
import io.xoboro.server.persistence.JooqReadProgressRepository
import io.xoboro.server.persistence.JooqSeriesCollectionRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqServerSettingRepository
import io.xoboro.server.persistence.JooqSyncPointRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.JooqUserSessionRepository
import io.xoboro.server.persistence.LocalDatabaseBackupRequester
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.persistence.isStoreContention
import io.xoboro.server.security.AdaptivePasswordHasher
import io.xoboro.server.security.InMemoryOAuth2PendingAuthorizationStore
import io.xoboro.server.security.Sha512TokenEncoder
import io.xoboro.server.security.SpringCompatibleRememberMeTokenService
import io.xoboro.server.sources.local.LocalLibraryRootInspector
import io.xoboro.server.sources.local.LocalSourceArtworkAccess
import io.xoboro.server.sources.local.LocalSourceInventory
import io.xoboro.server.sources.local.LocalSourceMediaAccess
import io.xoboro.server.sources.local.LocalSourceMutationAccess
import io.xoboro.server.sources.local.LocalSourceRandomAccess
import io.xoboro.server.sources.local.LocalSourceSidecarAccess
import io.xoboro.server.sources.webdav.WebDavLibraryRootInspector
import io.xoboro.server.sources.webdav.WebDavSourceArtworkAccess
import io.xoboro.server.sources.webdav.WebDavSourceInventory
import io.xoboro.server.sources.webdav.WebDavSourceMediaAccess
import io.xoboro.server.sources.webdav.WebDavSourceRandomAccess
import io.xoboro.server.sources.webdav.WebDavSourceSidecarAccess
import io.xoboro.server.tasks.ActivityRetentionScheduler
import io.xoboro.server.tasks.AnalyzeBookTaskEmitter
import io.xoboro.server.tasks.AnalyzeBookTaskHandler
import io.xoboro.server.tasks.AnalyzeLibraryTaskHandler
import io.xoboro.server.tasks.ArchiveMaintenanceTaskEmitter
import io.xoboro.server.tasks.ArchiveMaintenanceTaskHandler
import io.xoboro.server.tasks.BookCoverGenerationLifecycle
import io.xoboro.server.tasks.CatalogSourceFileLifecycle
import io.xoboro.server.tasks.DeleteBookFileTaskHandler
import io.xoboro.server.tasks.DeleteSeriesFileTaskHandler
import io.xoboro.server.tasks.DurableCatalogFileLifecycleRequester
import io.xoboro.server.tasks.DurableCatalogMaintenanceRequester
import io.xoboro.server.tasks.DurableCompatibilityMaintenanceRequester
import io.xoboro.server.tasks.DurableLibraryMaintenanceRequester
import io.xoboro.server.tasks.DurableTaskWorker
import io.xoboro.server.tasks.EmptyLibraryTrashTaskEmitter
import io.xoboro.server.tasks.EmptyLibraryTrashTaskHandler
import io.xoboro.server.tasks.ExecutorFixedRateTaskScheduler
import io.xoboro.server.tasks.FindBookArtworkTaskHandler
import io.xoboro.server.tasks.GenerateBookArtworkTaskHandler
import io.xoboro.server.tasks.ImportBookTaskHandler
import io.xoboro.server.tasks.LibraryScanScheduler
import io.xoboro.server.tasks.OrganizationArtworkTaskEmitter
import io.xoboro.server.tasks.OrganizationArtworkTaskHandler
import io.xoboro.server.tasks.RefreshBookMetadataTaskHandler
import io.xoboro.server.tasks.RefreshLibraryMetadataTaskHandler
import io.xoboro.server.tasks.RefreshMetadataTaskEmitter
import io.xoboro.server.tasks.RefreshSeriesMetadataTaskHandler
import io.xoboro.server.tasks.RemoveDuplicatePagesTaskHandler
import io.xoboro.server.tasks.ScanLibraryTaskEmitter
import io.xoboro.server.tasks.ScanLibraryTaskHandler
import io.xoboro.server.tasks.ScheduledLeaseHeartbeat
import io.xoboro.server.tasks.SeriesAggregationScheduler
import io.xoboro.server.tasks.TaskWorkerPolicy
import io.xoboro.server.tasks.TaskWorkerPool
import io.xoboro.server.tasks.TaskWorkerPoolPolicy
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.serialization.json.Json

class XoboroRuntime private constructor(
  private val database: XoboroDatabase,
  private val libraryScanScheduler: LibraryScanScheduler,
  private val activityRetentionScheduler: ActivityRetentionScheduler,
  private val seriesAggregationScheduler: SeriesAggregationScheduler,
  private val heartbeat: ScheduledLeaseHeartbeat,
  private val workerPool: TaskWorkerPool,
  private val oauthHttpClient: HttpClient,
  val userLifecycle: UserLifecycle,
  val apiKeyLifecycle: ApiKeyLifecycle,
  val authenticationActivityLifecycle: AuthenticationActivityLifecycle,
  val userSessionLifecycle: UserSessionLifecycle,
  val rememberMeTokenService: RememberMeTokenService,
  val oauth2LoginLifecycle: OAuth2LoginLifecycle,
  val serverSettingsLifecycle: ServerSettingsLifecycle,
  val clientSettingsLifecycle: ClientSettingsLifecycle,
  val announcementLifecycle: AnnouncementLifecycle,
  val artworkLifecycle: ArtworkLifecycle,
  val libraryAdministrationLifecycle: LibraryAdministrationLifecycle,
  val libraryAvailabilityProbe: LibraryAvailabilityProbe,
  val libraryMaintenanceRequester: LibraryMaintenanceRequester,
  val libraryScanRequester: LibraryScanRequester,
  val catalogReadRepository: CatalogReadRepository,
  val catalogMaintenanceRequester: CatalogMaintenanceRequester,
  val catalogFileLifecycleRequester: CatalogFileLifecycleRequester,
  val transientBookLifecycle: TransientBookLifecycle,
  val sequentialReadProgressLifecycle: SequentialReadProgressLifecycle,
  val historicalEventRepository: HistoricalEventRepository,
  val syncPointRepository: SyncPointRepository,
  val mediaSyncLifecycle: MediaSyncLifecycle,
  val readListImportLifecycle: ReadListImportLifecycle,
  val fontResourceCatalog: FontResourceCatalog,
  val serverReleaseCatalog: ServerReleaseCatalog,
  val compatibilityMaintenanceRequester: CompatibilityMaintenanceRequester,
  val metadataEditingLifecycle: MetadataEditingLifecycle,
  val metadataFacetRepository: MetadataFacetRepository,
  val pageHashRepository: PageHashRepository,
  val pageHashLifecycle: PageHashLifecycle,
  val bookContentAccess: BookContentAccess,
  val kepubContentAccess: KepubContentAccess,
  val organizationLifecycle: OrganizationLifecycle,
  val seriesCollectionRepository: SeriesCollectionRepository,
  val readListRepository: ReadListRepository,
  val readProgressLifecycle: ReadProgressLifecycle,
  val koreaderSyncLifecycle: KoreaderSyncLifecycle,
  val sseEventHub: KomgaSseEventHub,
  val nativeEventHub: XoboroNativeEventHub,
  val sseTaskStatusProvider: KomgaTaskStatusProvider,
  val durableTaskQueue: DurableTaskQueue,
  val databaseBackupRequester: DatabaseBackupRequester,
  val mediaItemRepository: MediaItemRepository,
  val libraryRepository: LibraryRepository,
  val effectiveServerPort: Int,
  val effectiveServerContextPath: String?,
  /** Directory holding the built web UI, or `null` when none is deployed. */
  val webDirectory: Path?,
  val corsAllowedOrigins: Set<String>,
  val trustedProxyHosts: Set<String>,
  val metricsToken: String?,
) : AutoCloseable {
  private val closed = AtomicBoolean(false)

  fun isReady(): Boolean =
    !closed.get() && database.isAvailable()

  fun taskWorkerCount(): Int = workerPool.workerCount()

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    var failure: Throwable? = null
    listOf<AutoCloseable>(
      libraryScanScheduler,
      activityRetentionScheduler,
      seriesAggregationScheduler,
      workerPool,
      heartbeat,
      oauthHttpClient,
      sseEventHub,
      nativeEventHub,
      database,
    ).forEach { resource ->
      try {
        resource.close()
      } catch (caught: Throwable) {
        if (failure == null) {
          failure = caught
        } else {
          failure.addSuppressed(caught)
        }
      }
    }
    failure?.let { throw it }
  }

  companion object {
    private val logger = Logger.getLogger(XoboroRuntime::class.java.name)
    const val DEFAULT_SESSION_TIMEOUT_MILLIS: Long = 7L * 24 * 60 * 60 * 1_000

    fun open(config: ServerConfig): XoboroRuntime {
      val database =
        XoboroDatabase.open(
          DatabaseConfig(
            path = config.databasePath,
            maximumPoolSize = DatabaseConfig.poolSizeForWorkers(config.workerCount),
          ),
        )
      var heartbeat: ScheduledLeaseHeartbeat? = null
      var workerPool: TaskWorkerPool? = null
      var libraryScanScheduler: LibraryScanScheduler? = null
      var activityRetentionScheduler: ActivityRetentionScheduler? = null
      var seriesAggregationScheduler: SeriesAggregationScheduler? = null
      var oauthHttpClient: HttpClient? = null
      var sseEventHub: KomgaSseEventHub? = null
      var nativeEventHub: XoboroNativeEventHub? = null
      try {
        val libraries = JooqLibraryRepository(database)
        val books = JooqBookRepository(database)
        val series = JooqSeriesRepository(database)
        val bookMetadata = JooqBookMetadataRepository(database)
        val seriesMetadata = JooqSeriesMetadataRepository(database)
        val mediaItems = JooqMediaItemRepository(database, books)
        val media = JooqBookMediaRepository(database)
        val readProgresses = JooqReadProgressRepository(database)
        val collections = JooqSeriesCollectionRepository(database)
        val readLists = JooqReadListRepository(database)
        val sseEvents = KomgaSseEventHub().also { sseEventHub = it }
        val sseBridge =
          KomgaSseEventBridge(sseEvents) { bookId ->
            books.findByIdOrNull(bookId)?.seriesId
          }
        // Constructed ahead of its original position (immediately before `metadataEditing`) so
        // the native hub below can depend on it: JooqCatalogReadRepository only needs repository
        // references that already exist at this point, so moving it earlier changes nothing
        // about its behaviour.
        val catalogReads =
          JooqCatalogReadRepository(
            database = database,
            books = books,
            series = series,
            bookMetadata = bookMetadata,
            seriesMetadata = seriesMetadata,
            media = media,
            readProgress = readProgresses,
          )
        val nativeEvents = XoboroNativeEventHub(catalog = catalogReads).also { nativeEventHub = it }
        val artworkRepository = JooqArtworkRepository(database)
        // Bound where `maximumCoverDimension` is defined, which is further down this function than the
        // artwork lifecycle has to exist. Unset is a wiring mistake rather than a size to guess at, so
        // reading it throws: quietly falling back to the untrusted-input ceiling is the exact defect
        // this indirection exists to remove, and it would show up only as a slow grid.
        var coverDimension: (() -> Int)? = null
        val artworkLifecycle =
          ArtworkLifecycle(
            artwork = artworkRepository,
            processor = SafeJpegArtworkProcessor(),
            sidecarProcessor =
              ArtworkProcessor { input ->
                val dimension =
                  requireNotNull(coverDimension) {
                    "The cover dimension supplier was not wired before artwork was processed"
                  }.invoke()
                SafeJpegArtworkProcessor(maximumDimension = dimension).process(input)
              },
            idFactory = { TsidCreator.getTsid256().toString() },
            currentTimeMillis = System::currentTimeMillis,
            eventPublisher = { event ->
              sseBridge.publish(event)
              XoboroNativeEventBridge.map(event)?.let(nativeEvents::publish)
            },
            groupingMembers = { owner ->
              // Only the two grouping kinds cost a query. Media-item and series owners are scoped
              // from their own identifier, so returning early keeps the common case free.
              when (owner.kind) {
                ArtworkOwnerKind.COLLECTION ->
                  collections
                    .findByIdOrNull(CollectionId(owner.id))
                    ?.seriesIds
                    ?.map(SeriesId::value)
                    .orEmpty()
                ArtworkOwnerKind.READ_LIST ->
                  readLists
                    .findByIdOrNull(ReadListId(owner.id))
                    ?.bookIds
                    ?.map(BookId::value)
                    .orEmpty()
                ArtworkOwnerKind.MEDIA_ITEM, ArtworkOwnerKind.SERIES -> emptyList()
              }
            },
          )
        val organizationLifecycle =
          OrganizationLifecycle(
            collections = collections,
            readLists = readLists,
            series = series,
            books = books,
            collectionIdFactory = { TsidCreator.getTsid256().toString() },
            readListIdFactory = { TsidCreator.getTsid256().toString() },
            currentTimeMillis = System::currentTimeMillis,
            eventPublisher = { event ->
              sseBridge.publish(event)
              nativeEvents.publish(XoboroNativeEventBridge.map(event))
            },
          )
        val metadataEditing =
          MetadataEditingLifecycle(
            books = books,
            series = series,
            bookMetadata = bookMetadata,
            seriesMetadata = seriesMetadata,
            currentTimeMillis = System::currentTimeMillis,
            eventPublisher = { event ->
              sseBridge.publish(event)
              nativeEvents.publish(XoboroNativeEventBridge.map(event))
            },
          )
        val metadataFacets = JooqMetadataFacetRepository(database)
        val pageHashes = JooqPageHashRepository(database)
        val pageHashLifecycle =
          PageHashLifecycle(
            hashes = pageHashes,
            currentTimeMillis = System::currentTimeMillis,
          )
        val readProgressLifecycle =
          ReadProgressLifecycle(
            books = books,
            series = series,
            media = media,
            progresses = readProgresses,
            currentTimeMillis = System::currentTimeMillis,
            eventPublisher = { event ->
              sseBridge.publish(event)
              nativeEvents.publish(XoboroNativeEventBridge.map(event))
            },
          )
        val koreaderSyncLifecycle =
          KoreaderSyncLifecycle(
            fingerprints = JooqMediaItemFingerprintIndex(database),
            catalog = catalogReads,
            media = media,
            progress = readProgressLifecycle,
            currentTimeMillis = System::currentTimeMillis,
          )
        val sequentialReadProgressLifecycle =
          SequentialReadProgressLifecycle(
            catalog = catalogReads,
            readLists = readLists,
            progress = readProgressLifecycle,
          )
        val historicalEvents = JooqHistoricalEventRepository(database)
        val syncPoints = JooqSyncPointRepository(database)
        val mediaSyncLifecycle =
          MediaSyncLifecycle(
            syncPoints = syncPoints,
            snapshots = JooqMediaSyncSnapshotRepository(database),
            catalog = catalogReads,
            readLists = readLists,
            syncPointIdFactory = { TsidCreator.getTsid256().toString() },
            currentTimeMillis = System::currentTimeMillis,
          )
        val readListImports =
          ReadListImportLifecycle(
            parser = ComicRackReadListParser(),
            matcher = JooqReadListImportMatcher(database),
            readLists = readLists,
          )
        val queue = JooqDurableTaskQueue(database)
        val userRepository = JooqUserRepository(database)
        val tokenEncoder = Sha512TokenEncoder()
        val sessionRepository = JooqUserSessionRepository(database)
        val settings = JooqServerSettingRepository(database)
        val effectiveServerPort = settings.find("SERVER_PORT")?.toInt() ?: config.port
        val effectiveServerContextPath =
          settings.find("SERVER_CONTEXT_PATH") ?: config.configuredContextPath
        val serverSettingsLifecycle =
          ServerSettingsLifecycle(
            store = settings,
            configuredServerPort = config.configuredPort,
            effectiveServerPort = { effectiveServerPort },
            configuredServerContextPath = config.configuredContextPath,
            effectiveServerContextPath = { effectiveServerContextPath },
            defaultTaskPoolSize = config.workerCount,
            rememberMeKeyFactory = { UUID.randomUUID().toString().replace("-", "") },
            onTaskPoolSizeChanged = { workerCount ->
              workerPool?.resize(workerCount)
            },
          )
        // One supplier for every cover producer, so the analysis-time generator and the operator's
        // regeneration sweep can never disagree about how large a cover should be.
        val maximumCoverDimension = { serverSettingsLifecycle.snapshot().thumbnailSize.maximumDimension }
        // Sidecar artwork is a third cover producer, and it disagreed: it stored the untrusted-input
        // ceiling of 1600px while the other two used this, so a sidecar-covered series sent 85.8 KB to
        // a 140px grid cell against a generated cover's 16.9 KB.
        coverDimension = maximumCoverDimension
        val userLifecycle =
          UserLifecycle(
            users = userRepository,
            passwordHasher = AdaptivePasswordHasher(),
            userIdFactory = { TsidCreator.getTsid256().toString() },
            currentTimeMillis = System::currentTimeMillis,
            invalidateUserSessions = { sessionRepository.deleteByUserId(it) },
            eventPublisher = { event ->
              sseBridge.publish(event)
              // XoboroNativeEventBridge.map(UserEvent) is always null (see its doc comment: the
              // stream re-authenticates itself on every request, so this event would be
              // redundant rather than useful) — routed through the bridge anyway so that
              // decision stays in one place instead of being duplicated as a second, silent skip
              // here.
              XoboroNativeEventBridge.map(event)?.let(nativeEvents::publish)
            },
          )
        config.initialAdministrator?.let { initial ->
          // Only when the server is still unclaimed, and never otherwise. Restarting with the variables
          // still set must not reset the administrator's password - which would also mean that anyone
          // who can edit the environment could take the account over by restarting, turning a
          // provisioning convenience into a back door.
          //
          // claimIfEmpty underneath does the check atomically, so ServerAlreadyClaimedException is the
          // expected outcome on every restart after the first and is not an error.
          try {
            userLifecycle.claimInitialAdministrator(initial.email, initial.password)
            // The email, never the password. This line goes to the same log an operator pastes into an
            // issue.
            logger.info("Claimed the initial administrator from configuration: ${initial.email}")
          } catch (_: ServerAlreadyClaimedException) {
            logger.fine("Server is already claimed; the configured initial administrator was ignored")
          }
        }
        val apiKeyLifecycle =
          ApiKeyLifecycle(
            users = userRepository,
            apiKeys = JooqApiKeyRepository(database),
            tokenEncoder = tokenEncoder,
            apiKeyIdFactory = { TsidCreator.getTsid256().toString() },
            plainTextKeyFactory = { UUID.randomUUID().toString().replace("-", "") },
            currentTimeMillis = System::currentTimeMillis,
          )
        val userSessionLifecycle =
          UserSessionLifecycle(
            users = userRepository,
            sessions = sessionRepository,
            tokenEncoder = tokenEncoder,
            plainTokenFactory = { UUID.randomUUID().toString().replace("-", "") },
            currentTimeMillis = System::currentTimeMillis,
            inactivityTimeoutMillis = DEFAULT_SESSION_TIMEOUT_MILLIS,
          )
        userSessionLifecycle.deleteExpired()
        val createdOAuthHttpClient =
          HttpClient(CIO) {
            expectSuccess = true
            followRedirects = false
            install(HttpTimeout) {
              requestTimeoutMillis = 15_000
              connectTimeoutMillis = 10_000
              socketTimeoutMillis = 15_000
            }
            install(ContentNegotiation) {
              json(Json { ignoreUnknownKeys = true })
            }
          }
        oauthHttpClient = createdOAuthHttpClient
        val secureRandom = SecureRandom()
        val oauth2LoginLifecycle =
          OAuth2LoginLifecycle(
            registrations = config.oauth2Registrations,
            users = userLifecycle,
            pendingAuthorizations = InMemoryOAuth2PendingAuthorizationStore(),
            identityGateway = HttpOAuth2IdentityGateway(createdOAuthHttpClient),
            accountCreationEnabled = config.oauth2AccountCreation,
            oidcEmailVerificationEnabled = config.oidcEmailVerification,
            accountLinking = config.oauth2AccountLinking,
            randomPasswordFactory = { secureRandom.urlToken(24) },
            stateFactory = { secureRandom.urlToken(32) },
            browserBindingFactory = { secureRandom.urlToken(32) },
            nonceFactory = { secureRandom.urlToken(32) },
            currentTimeMillis = System::currentTimeMillis,
          )
        val rememberMeTokenService =
          SpringCompatibleRememberMeTokenService(
            users = userRepository,
            secretKeyProvider = serverSettingsLifecycle::rememberMeKey,
            currentTimeMillis = System::currentTimeMillis,
            tokenValidityMillisProvider = serverSettingsLifecycle::rememberMeDurationMillis,
          )
        val authenticationActivityLifecycle =
          AuthenticationActivityLifecycle(
            activities = JooqAuthenticationActivityRepository(database),
            currentTimeMillis = System::currentTimeMillis,
          )
        val clientSettingsLifecycle =
          ClientSettingsLifecycle(JooqClientSettingsRepository(database))
        val announcementLifecycle =
          AnnouncementLifecycle(
            feedProvider = HttpAnnouncementFeedProvider.komgaCompatible(),
            reads = JooqAnnouncementReadRepository(database),
          )
        val reconciliationStore =
          JooqCatalogReconciliationStore(
            database = database,
            eventPublisher = { event ->
              sseBridge.publish(event)
              nativeEvents.publish(XoboroNativeEventBridge.map(event))
            },
          )
        // Here and nowhere else. No scan is running yet, so every staging session is abandoned by
        // definition - the only moment that inference holds. One library had accumulated eight of
        // them holding 612,314 candidate rows, each widening the indexes the next scan searches.
        reconciliationStore.discardAbandonedSessions(System.currentTimeMillis()).takeIf { it > 0 }
          ?.let { logger.info("Retired $it scan session(s) left staging by an earlier run") }
        val catalogScanner =
          CatalogScanner(
            inventories = listOf(LocalSourceInventory(), WebDavSourceInventory()),
            reconciliationStore = reconciliationStore,
            currentTimeMillis = System::currentTimeMillis,
          )
        val libraryAvailabilityLifecycle =
          LibraryAvailabilityLifecycle(
            libraries = libraries,
            currentTimeMillis = System::currentTimeMillis,
            eventPublisher =
              LibraryEventPublisher { event ->
                sseBridge.publish(event)
                nativeEvents.publish(XoboroNativeEventBridge.map(event))
              },
          )
        val scanEmitter =
          ScanLibraryTaskEmitter(
            queue = queue,
            currentTimeMillis = System::currentTimeMillis,
          )
        val archiveMaintenanceEmitter =
          ArchiveMaintenanceTaskEmitter(
            books = books,
            queue = queue,
            currentTimeMillis = System::currentTimeMillis,
          )
        val createdLibraryScanScheduler =
          LibraryScanScheduler(
            libraries = libraries,
            emitter = scanEmitter,
            scheduler =
              ExecutorFixedRateTaskScheduler(
                shutdownTimeoutMillis = config.shutdownTimeoutMillis,
                onFailure = { failure ->
                  logger.log(Level.SEVERE, "Periodic library scan scheduling failed", failure)
                },
              ),
          )
        libraryScanScheduler = createdLibraryScanScheduler
        val createdActivityRetentionScheduler =
          ActivityRetentionScheduler(
            retention =
              ActivityRetentionLifecycle(
                history = historicalEvents,
                authenticationActivity = authenticationActivityLifecycle,
                retention = serverSettingsLifecycle::activityRetention,
                currentTimeMillis = System::currentTimeMillis,
              ),
            scheduler =
              ExecutorFixedRateTaskScheduler(
                shutdownTimeoutMillis = config.shutdownTimeoutMillis,
                onFailure = { failure ->
                  logger.log(Level.SEVERE, "Activity retention sweep failed", failure)
                },
              ),
            onSwept = { swept ->
              logger.info(
                "Activity retention removed ${swept.historyDeleted} history and " +
                  "${swept.authenticationActivityDeleted} authentication activity rows",
              )
            },
          ).also(ActivityRetentionScheduler::start)
        activityRetentionScheduler = createdActivityRetentionScheduler
        val createdSeriesAggregationScheduler =
          SeriesAggregationScheduler(
            sweepOnce = JooqBookMetadataAggregationRepository(database)::refreshSomeDirty,
            scheduler =
              ExecutorFixedRateTaskScheduler(
                shutdownTimeoutMillis = config.shutdownTimeoutMillis,
                onFailure = { failure ->
                  logger.log(Level.SEVERE, "Series aggregation sweep failed", failure)
                },
              ),
          ).also(SeriesAggregationScheduler::start)
        seriesAggregationScheduler = createdSeriesAggregationScheduler
        val libraryMaintenanceQueue =
          object : LibraryMaintenanceQueue {
            override fun scanLibrary(id: LibraryId) {
              scanEmitter.scanLibrary(id)
            }

            override fun reschedulePeriodicScan(library: Library) {
              createdLibraryScanScheduler.schedule(library)
            }

            override fun hashBooksWithoutFileHash(id: LibraryId) {
              scanEmitter.scanLibrary(id, deep = true)
            }

            override fun hashBooksWithoutKoreaderHash(id: LibraryId) {
              scanEmitter.scanLibrary(id, deep = true)
            }

            override fun hashBooksWithMissingPageHash(id: LibraryId) {
              scanEmitter.scanLibrary(id, deep = true)
            }

            override fun repairExtensions(id: LibraryId) {
              val library = libraries.findById(id)
              archiveMaintenanceEmitter.maintainLibrary(
                libraryId = id,
                repairExtensions = true,
                convertToCbz = library.settings.convertToCbz,
              )
            }

            override fun convertBooksToCbz(id: LibraryId) {
              val library = libraries.findById(id)
              archiveMaintenanceEmitter.maintainLibrary(
                libraryId = id,
                repairExtensions = library.settings.repairExtensions,
                convertToCbz = true,
              )
            }
          }
        val libraryRootAccess =
          RoutingLibraryRootAccess(
            listOf(LocalLibraryRootInspector(), WebDavLibraryRootInspector()),
          )
        val libraryAvailabilityProbe =
          LibraryAvailabilityProbe(
            libraries = libraries,
            rootAccess = libraryRootAccess,
            availability = libraryAvailabilityLifecycle,
          )
        val libraryAdministrationLifecycle =
          LibraryAdministrationLifecycle(
            libraries = libraries,
            lifecycle =
              LibraryLifecycle(
                repository = libraries,
                rootAccess = libraryRootAccess,
                maintenanceQueue = libraryMaintenanceQueue,
                eventPublisher = { event ->
                  sseBridge.publish(event)
                  nativeEvents.publish(XoboroNativeEventBridge.map(event))
                  when (event) {
                    is LibraryEvent.Added ->
                      createdLibraryScanScheduler.schedule(event.library)
                    is LibraryEvent.Deleted ->
                      createdLibraryScanScheduler.cancel(event.library.id)
                    is LibraryEvent.Updated -> Unit
                  }
                },
              ),
            libraryIdFactory = { TsidCreator.getTsid256().toString() },
            currentTimeMillis = System::currentTimeMillis,
          )
        val libraryScanRequester =
          LibraryScanRequester { libraryId, deep ->
            scanEmitter.scanLibrary(
              libraryId = libraryId,
              deep = deep,
              priority = TaskPriority.HIGHEST,
            )
          }
        val analyzeBookTaskEmitter =
          AnalyzeBookTaskEmitter(
            books = books,
            queue = queue,
            currentTimeMillis = System::currentTimeMillis,
          )
        val localMediaAccess = LocalSourceMediaAccess()
        val localMutationAccess = LocalSourceMutationAccess()
        // A remote source has to hand the analyzers a real file, so archives are cached beside
        // the database rather than in the library: the library is read-only and, being remote,
        // is the one place a cache must not live.
        val webDavMediaAccess =
          WebDavSourceMediaAccess(
            cacheDirectory = config.databasePath.toAbsolutePath().parent.resolve("webdav-cache"),
          )
        // Analysis that needs no entry bytes reads an archive's trailer by range instead, which is
        // what keeps a remote scan from transferring the whole library. See AnalyzeBook.
        val randomAccesses = listOf(LocalSourceRandomAccess(), WebDavSourceRandomAccess())
        val bookContentAccess =
          BookContentService(
            libraries = libraries,
            books = books,
            media = media,
            accesses = listOf(localMediaAccess, webDavMediaAccess),
            randomAccesses = randomAccesses,
          )
        val kepubContentAccess =
          ExternalKepubContentAccess(
            books = bookContentAccess,
            executablePath = {
              serverSettingsLifecycle.snapshot().kepubifyPath.effectiveValue
            },
            cacheDirectory =
              config.databasePath
                .toAbsolutePath()
                .normalize()
                .parent
                .resolve("cache/kepub"),
          )
        val comicInfoMetadataProvider =
          ComicInfoMetadataProvider(listOf(localMediaAccess, webDavMediaAccess), randomAccesses)
        val epubMetadataProvider =
          EpubMetadataProvider(listOf(localMediaAccess, webDavMediaAccess))
        val pdfMetadataProvider =
          PdfMetadataProvider(listOf(localMediaAccess, webDavMediaAccess))
        val isbnBarcodeMetadataProvider =
          IsbnBarcodeMetadataProvider(bookContentAccess)
        val metadataRefreshLifecycle =
          MetadataRefreshLifecycle(
            libraries = libraries,
            books = books,
            series = series,
            bookMetadata = bookMetadata,
            seriesMetadata = seriesMetadata,
            bookProviders =
              listOf(
                comicInfoMetadataProvider,
                epubMetadataProvider,
                pdfMetadataProvider,
                isbnBarcodeMetadataProvider,
              ),
            seriesProviders =
              listOf(
                comicInfoMetadataProvider,
                epubMetadataProvider,
                MylarSeriesMetadataProvider(
                  accesses = listOf(LocalSourceSidecarAccess(), WebDavSourceSidecarAccess()),
                  diagnostics = { diagnostic ->
                    // Schema drift is INFO because a newer Mylar writing new fields is normal and
                    // logging it as a problem would train an operator to ignore the log. Everything
                    // else is a file the operator wrote that Xoboro could not use, which is WARNING.
                    val level =
                      if (diagnostic is MylarSeriesDiagnostic.SeriesJsonIgnored) {
                        Level.INFO
                      } else {
                        Level.WARNING
                      }
                    logger.log(level, "Mylar series.json: $diagnostic")
                  },
                ),
                OneShotSeriesMetadataProvider(bookMetadata),
              ),
            currentTimeMillis = System::currentTimeMillis,
            eventPublisher = { event ->
              sseBridge.publish(event)
              nativeEvents.publish(XoboroNativeEventBridge.map(event))
            },
            organizationWriter =
              JooqMetadataOrganizationWriter(
                database = database,
                collectionIdFactory = { TsidCreator.getTsid256().toString() },
                readListIdFactory = { TsidCreator.getTsid256().toString() },
                currentTimeMillis = System::currentTimeMillis,
                eventPublisher = { event ->
                  sseBridge.publish(event)
                  nativeEvents.publish(XoboroNativeEventBridge.map(event))
                },
              ),
          )
        val localArtworkRefreshLifecycle =
          LocalArtworkRefreshLifecycle(
            libraries = libraries,
            books = books,
            series = series,
            artwork = artworkLifecycle,
            accesses = listOf(LocalSourceArtworkAccess(), WebDavSourceArtworkAccess()),
          )
        val refreshMetadataTaskEmitter =
          RefreshMetadataTaskEmitter(
            books = books,
            series = series,
            queue = queue,
            currentTimeMillis = System::currentTimeMillis,
          )
        val emptyLibraryTrashTaskEmitter =
          EmptyLibraryTrashTaskEmitter(
            queue = queue,
            currentTimeMillis = System::currentTimeMillis,
          )
        val libraryMaintenanceRequester =
          DurableLibraryMaintenanceRequester(
            analysis = analyzeBookTaskEmitter,
            metadata = refreshMetadataTaskEmitter,
            trash = emptyLibraryTrashTaskEmitter,
          )
        val catalogMaintenanceRequester =
          DurableCatalogMaintenanceRequester(
            analysis = analyzeBookTaskEmitter,
            metadata = refreshMetadataTaskEmitter,
            queue = queue,
          )
        val catalogFileLifecycleRequester =
          DurableCatalogFileLifecycleRequester(
            books = books,
            series = series,
            queue = queue,
            taskIdFactory = { UUID.randomUUID().toString() },
            currentTimeMillis = System::currentTimeMillis,
          )
        val transientBookLifecycle =
          LocalTransientBookLifecycle(
            libraries = libraries,
            idFactory = { TsidCreator.getTsid256().toString() },
            currentTimeMillis = System::currentTimeMillis,
          )
        val compatibilityMaintenanceRequester =
          DurableCompatibilityMaintenanceRequester(
            books = books,
            queue = queue,
            taskIdFactory = { UUID.randomUUID().toString() },
            currentTimeMillis = System::currentTimeMillis,
            organizationArtwork =
              OrganizationArtworkTaskEmitter(
                collections = collections,
                readLists = readLists,
                artwork = artworkRepository,
                queue = queue,
                currentTimeMillis = System::currentTimeMillis,
              ),
          )
        val libraryTrashStore = JooqLibraryTrashStore(database)
        val analyzeBook =
          AnalyzeBook(
            books = books,
            libraries = libraries,
            accesses = listOf(localMediaAccess, webDavMediaAccess),
            media = media,
            zipAnalyzer = ZipMediaAnalyzer(),
            randomAccesses = randomAccesses,
            currentTimeMillis = System::currentTimeMillis,
          )
        val bookCoverGeneration =
          BookCoverGenerationLifecycle(
            books = books,
            media = media,
            bookMetadata = bookMetadata,
            series = series,
            content = bookContentAccess,
            artwork = artworkLifecycle,
            maximumCoverDimension = maximumCoverDimension,
            isStoreBusy = ::isStoreContention,
          )
        val catalogSourceFileLifecycle =
          CatalogSourceFileLifecycle(
            books = books,
            series = series,
            libraries = libraries,
            mutations = listOf(LocalSourceMutationAccess()),
            scanEmitter = scanEmitter,
            history = historicalEvents,
            historyIdFactory = { TsidCreator.getTsid256().toString() },
            currentTimeMillis = System::currentTimeMillis,
            // Deliberately not fanned out to nativeEvents: XoboroNativeEventBridge.map(
            // CatalogImportEvent) always returns null (there is no native import endpoint, and
            // its sourceFile is an absolute server path), so there is nothing for the native hub
            // to publish here.
            importEventPublisher = sseBridge::publish,
          )
        val createdHeartbeat = ScheduledLeaseHeartbeat()
        heartbeat = createdHeartbeat
        val worker =
          DurableTaskWorker(
            queue = queue,
            handlers =
              listOf(
                ScanLibraryTaskHandler(
                  libraries = libraries,
                  scanner = catalogScanner,
                  availability = libraryAvailabilityLifecycle,
                  afterScan = { library, _ ->
                    if (library.settings.emptyTrashAfterScan) {
                      emptyLibraryTrashTaskEmitter.emptyTrash(library.id)
                    }
                    archiveMaintenanceEmitter.maintainLibrary(
                      libraryId = library.id,
                      repairExtensions = library.settings.repairExtensions,
                      convertToCbz = library.settings.convertToCbz,
                    )
                  },
                ),
                AnalyzeBookTaskHandler(
                  analyzeBook = { bookId ->
                    analyzeBook.execute(bookId)
                  },
                  afterAnalyze = { bookId ->
                    refreshMetadataTaskEmitter.refreshBook(bookId)
                    bookCoverGeneration.generateForBook(bookId)
                    books.findByIdOrNull(bookId)?.let { book ->
                      refreshMetadataTaskEmitter.refreshSeriesMetadata(book.seriesId)
                      bookCoverGeneration.generateForSeries(book.seriesId)
                    }
                  },
                ),
                // The library-wide fan-outs. Both run the per-book emission a request used to do
                // inline, where a busy task store failed the request half-way through. Registered
                // here and nowhere else: an unregistered type leaves its row PENDING forever.
                AnalyzeLibraryTaskHandler(
                  analyzeLibrary = { id, cursor ->
                    analyzeBookTaskEmitter.analyzeLibrary(id, from = cursor)
                  },
                ),
                RefreshLibraryMetadataTaskHandler(
                  refreshLibrary = { id, cursor ->
                    refreshMetadataTaskEmitter.refreshLibrary(id, from = cursor)
                  },
                ),
                EmptyLibraryTrashTaskHandler(libraryTrashStore),
                RefreshBookMetadataTaskHandler(
                  metadataRefreshLifecycle,
                  afterRefresh =
                    BookMetadataRefreshCompletion(
                      books = books,
                      localArtworkRefresh = localArtworkRefreshLifecycle,
                      refreshMetadataTaskEmitter = refreshMetadataTaskEmitter,
                    ),
                ),
                RefreshSeriesMetadataTaskHandler(
                  metadataRefreshLifecycle,
                  afterRefresh = { localArtworkRefreshLifecycle.refreshSeries(it) },
                ),
                DeleteBookFileTaskHandler(catalogSourceFileLifecycle),
                DeleteSeriesFileTaskHandler(catalogSourceFileLifecycle),
                ImportBookTaskHandler(catalogSourceFileLifecycle),
                GenerateBookArtworkTaskHandler(
                  bookContentAccess,
                  artworkLifecycle,
                  maximumCoverDimension,
                ),
                OrganizationArtworkTaskHandler(
                  collections = collections,
                  readLists = readLists,
                  books = books,
                  artwork = artworkRepository,
                  lifecycle = artworkLifecycle,
                ),
                FindBookArtworkTaskHandler(
                  catalog = catalogReads,
                  artwork = artworkLifecycle,
                  queue = queue,
                  taskIdFactory = { UUID.randomUUID().toString() },
                  currentTimeMillis = System::currentTimeMillis,
                  maximumCoverDimension = maximumCoverDimension,
                ),
                RemoveDuplicatePagesTaskHandler(
                  books = books,
                  libraries = libraries,
                  pageHashes = pageHashes,
                  mutations = listOf(localMutationAccess),
                  scanEmitter = scanEmitter,
                ),
                ArchiveMaintenanceTaskHandler(
                  books = books,
                  libraries = libraries,
                  media = media,
                  accesses = listOf(localMediaAccess, webDavMediaAccess),
                  mutations = listOf(localMutationAccess),
                  converter = RarToCbzConverter(),
                  analysisEmitter = analyzeBookTaskEmitter,
                  scanEmitter = scanEmitter,
                  currentTimeMillis = System::currentTimeMillis,
                ),
              ),
            heartbeat = createdHeartbeat,
            currentTimeMillis = System::currentTimeMillis,
            leaseTokenFactory = { UUID.randomUUID().toString() },
            isStoreBusy = ::isStoreContention,
            policy = TaskWorkerPolicy(leaseDurationMillis = config.taskLeaseMillis),
          )
        val createdWorkerPool =
          TaskWorkerPool(
            runner = worker,
            policy =
              TaskWorkerPoolPolicy(
                workerCount = serverSettingsLifecycle.snapshot().taskPoolSize,
                idlePollMillis = config.taskPollMillis,
                failurePollMillis = config.taskFailurePollMillis,
                shutdownTimeoutMillis = config.shutdownTimeoutMillis,
              ),
            onFailure = { failure ->
              logger.log(Level.SEVERE, "Durable task worker failed", failure)
            },
          )
        workerPool = createdWorkerPool
        val databaseBackupRequester =
          LocalDatabaseBackupRequester(
            backups = database.backups,
            directory = config.backupsDirectory,
            idFactory = { TsidCreator.getTsid256().toString() },
          )
        val sseTaskStatusProvider =
          KomgaTaskStatusProvider {
            val counts = queue.countsByType()
            KomgaTaskQueueSseDto(
              count = counts.values.sum(),
              countByType = counts,
            )
          }
        return XoboroRuntime(
          database = database,
          libraryScanScheduler = createdLibraryScanScheduler,
          activityRetentionScheduler = createdActivityRetentionScheduler,
          seriesAggregationScheduler = createdSeriesAggregationScheduler,
          heartbeat = createdHeartbeat,
          workerPool = createdWorkerPool,
          oauthHttpClient = createdOAuthHttpClient,
          userLifecycle = userLifecycle,
          apiKeyLifecycle = apiKeyLifecycle,
          authenticationActivityLifecycle = authenticationActivityLifecycle,
          userSessionLifecycle = userSessionLifecycle,
          rememberMeTokenService = rememberMeTokenService,
          oauth2LoginLifecycle = oauth2LoginLifecycle,
          serverSettingsLifecycle = serverSettingsLifecycle,
          clientSettingsLifecycle = clientSettingsLifecycle,
          announcementLifecycle = announcementLifecycle,
          artworkLifecycle = artworkLifecycle,
          libraryAdministrationLifecycle = libraryAdministrationLifecycle,
          libraryAvailabilityProbe = libraryAvailabilityProbe,
          libraryMaintenanceRequester = libraryMaintenanceRequester,
          libraryScanRequester = libraryScanRequester,
          catalogReadRepository = catalogReads,
          catalogMaintenanceRequester = catalogMaintenanceRequester,
          catalogFileLifecycleRequester = catalogFileLifecycleRequester,
          transientBookLifecycle = transientBookLifecycle,
          sequentialReadProgressLifecycle = sequentialReadProgressLifecycle,
          historicalEventRepository = historicalEvents,
          syncPointRepository = syncPoints,
          mediaSyncLifecycle = mediaSyncLifecycle,
          readListImportLifecycle = readListImports,
          fontResourceCatalog = LocalFontResourceCatalog(config.fontsDirectory),
          serverReleaseCatalog = GithubReleaseCatalog(createdOAuthHttpClient),
          compatibilityMaintenanceRequester = compatibilityMaintenanceRequester,
          metadataEditingLifecycle = metadataEditing,
          metadataFacetRepository = metadataFacets,
          pageHashRepository = pageHashes,
          pageHashLifecycle = pageHashLifecycle,
          bookContentAccess = bookContentAccess,
          kepubContentAccess = kepubContentAccess,
          organizationLifecycle = organizationLifecycle,
          seriesCollectionRepository = collections,
          readListRepository = readLists,
          readProgressLifecycle = readProgressLifecycle,
          koreaderSyncLifecycle = koreaderSyncLifecycle,
          sseEventHub = sseEvents,
          nativeEventHub = nativeEvents,
          sseTaskStatusProvider = sseTaskStatusProvider,
          durableTaskQueue = queue,
          databaseBackupRequester = databaseBackupRequester,
          mediaItemRepository = mediaItems,
          libraryRepository = libraries,
          effectiveServerPort = effectiveServerPort,
          effectiveServerContextPath = effectiveServerContextPath,
          // Resolved once at start-up rather than checked per request: a UI that
          // appears while the server runs is not a case worth supporting, and a
          // per-request stat on every unmatched path would be.
          webDirectory = config.webDirectory.takeIf(::isServableWebDirectory),
          corsAllowedOrigins = config.corsAllowedOrigins,
          trustedProxyHosts = config.trustedProxyHosts,
          metricsToken = config.metricsToken,
        ).also {
          createdWorkerPool.start()
          createdLibraryScanScheduler.start()
        }
      } catch (failure: Throwable) {
        runCatching { libraryScanScheduler?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { activityRetentionScheduler?.close() }
        runCatching { seriesAggregationScheduler?.close() }
          .exceptionOrNull()
          ?.let(failure::addSuppressed)
        runCatching { workerPool?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { heartbeat?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { oauthHttpClient?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { sseEventHub?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { nativeEventHub?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { database.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
      }
    }
  }
}

private fun SecureRandom.urlToken(byteCount: Int): String {
  require(byteCount > 0)
  return ByteArray(byteCount)
    .also(::nextBytes)
    .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
}
