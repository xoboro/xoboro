package io.xoboro.server

import com.github.f4b6a3.tsid.TsidCreator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.AnnouncementLifecycle
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryEvent
import io.xoboro.core.application.LibraryLifecycle
import io.xoboro.core.application.LibraryMaintenanceRequester
import io.xoboro.core.application.LibraryMaintenanceQueue
import io.xoboro.core.application.LibraryScanRequester
import io.xoboro.core.application.RoutingLibraryRootAccess
import io.xoboro.core.application.RememberMeTokenService
import io.xoboro.core.application.OAuth2LoginLifecycle
import io.xoboro.core.application.ServerSettingsLifecycle
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaItemRepository
import io.xoboro.server.media.AnalyzeBook
import io.xoboro.server.media.ZipMediaAnalyzer
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqApiKeyRepository
import io.xoboro.server.persistence.JooqAnnouncementReadRepository
import io.xoboro.server.persistence.JooqAuthenticationActivityRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReconciliationStore
import io.xoboro.server.persistence.JooqClientSettingsRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqLibraryTrashStore
import io.xoboro.server.persistence.JooqMediaItemRepository
import io.xoboro.server.persistence.JooqServerSettingRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import io.xoboro.server.security.InMemoryUserSessionRepository
import io.xoboro.server.security.InMemoryOAuth2PendingAuthorizationStore
import io.xoboro.server.security.Sha512TokenEncoder
import io.xoboro.server.security.SpringCompatibleRememberMeTokenService
import io.xoboro.server.sources.local.LocalLibraryRootInspector
import io.xoboro.server.sources.local.LocalSourceInventory
import io.xoboro.server.sources.local.LocalSourceMediaAccess
import io.xoboro.server.tasks.AnalyzeBookTaskHandler
import io.xoboro.server.tasks.AnalyzeBookTaskEmitter
import io.xoboro.server.tasks.DurableLibraryMaintenanceRequester
import io.xoboro.server.tasks.DurableTaskWorker
import io.xoboro.server.tasks.EmptyLibraryTrashTaskEmitter
import io.xoboro.server.tasks.EmptyLibraryTrashTaskHandler
import io.xoboro.server.tasks.ExecutorFixedRateTaskScheduler
import io.xoboro.server.tasks.LibraryScanScheduler
import io.xoboro.server.tasks.ScanLibraryTaskEmitter
import io.xoboro.server.tasks.ScanLibraryTaskHandler
import io.xoboro.server.tasks.ScheduledLeaseHeartbeat
import io.xoboro.server.tasks.TaskWorkerPool
import io.xoboro.server.tasks.TaskWorkerPoolPolicy
import io.xoboro.server.tasks.TaskWorkerPolicy
import java.util.UUID
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.serialization.json.Json

class XoboroRuntime private constructor(
  private val database: XoboroDatabase,
  private val libraryScanScheduler: LibraryScanScheduler,
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
  val libraryAdministrationLifecycle: LibraryAdministrationLifecycle,
  val libraryMaintenanceRequester: LibraryMaintenanceRequester,
  val libraryScanRequester: LibraryScanRequester,
  val mediaItemRepository: MediaItemRepository,
  val libraryRepository: LibraryRepository,
  val effectiveServerPort: Int,
  val effectiveServerContextPath: String?,
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
      workerPool,
      heartbeat,
      oauthHttpClient,
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
            maximumPoolSize = (config.workerCount + 2).coerceAtMost(16),
          ),
        )
      var heartbeat: ScheduledLeaseHeartbeat? = null
      var workerPool: TaskWorkerPool? = null
      var libraryScanScheduler: LibraryScanScheduler? = null
      var oauthHttpClient: HttpClient? = null
      try {
        val libraries = JooqLibraryRepository(database)
        val books = JooqBookRepository(database)
        val mediaItems = JooqMediaItemRepository(database, books)
        val media = JooqBookMediaRepository(database)
        val queue = JooqDurableTaskQueue(database)
        val userRepository = JooqUserRepository(database)
        val tokenEncoder = Sha512TokenEncoder()
        val sessionRepository = InMemoryUserSessionRepository()
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
        val userLifecycle =
          UserLifecycle(
            users = userRepository,
            passwordHasher = BCryptPasswordHasher(),
            userIdFactory = { TsidCreator.getTsid256().toString() },
            currentTimeMillis = System::currentTimeMillis,
            invalidateUserSessions = { sessionRepository.deleteByUserId(it) },
          )
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
        val catalogScanner =
          CatalogScanner(
            inventories = listOf(LocalSourceInventory()),
            reconciliationStore = JooqCatalogReconciliationStore(database),
            currentTimeMillis = System::currentTimeMillis,
          )
        val scanEmitter =
          ScanLibraryTaskEmitter(
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
              scanEmitter.scanLibrary(id, deep = true)
            }

            override fun convertBooksToCbz(id: LibraryId) {
              scanEmitter.scanLibrary(id, deep = true)
            }
          }
        val libraryAdministrationLifecycle =
          LibraryAdministrationLifecycle(
            libraries = libraries,
            lifecycle =
              LibraryLifecycle(
                repository = libraries,
                rootAccess =
                  RoutingLibraryRootAccess(
                    listOf(LocalLibraryRootInspector()),
                  ),
                maintenanceQueue = libraryMaintenanceQueue,
                eventPublisher = { event ->
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
        val emptyLibraryTrashTaskEmitter =
          EmptyLibraryTrashTaskEmitter(
            queue = queue,
            currentTimeMillis = System::currentTimeMillis,
          )
        val libraryMaintenanceRequester =
          DurableLibraryMaintenanceRequester(
            analysis = analyzeBookTaskEmitter,
            trash = emptyLibraryTrashTaskEmitter,
          )
        val libraryTrashStore = JooqLibraryTrashStore(database)
        val analyzeBook =
          AnalyzeBook(
            books = books,
            libraries = libraries,
            accesses = listOf(LocalSourceMediaAccess()),
            media = media,
            zipAnalyzer = ZipMediaAnalyzer(),
            currentTimeMillis = System::currentTimeMillis,
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
                  afterScan = { library, _ ->
                    if (library.settings.emptyTrashAfterScan) {
                      emptyLibraryTrashTaskEmitter.emptyTrash(library.id)
                    }
                  },
                ),
                AnalyzeBookTaskHandler(
                  analyzeBook = { bookId ->
                    analyzeBook.execute(bookId)
                  },
                ),
                EmptyLibraryTrashTaskHandler(libraryTrashStore),
              ),
            heartbeat = createdHeartbeat,
            currentTimeMillis = System::currentTimeMillis,
            leaseTokenFactory = { UUID.randomUUID().toString() },
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
        return XoboroRuntime(
          database = database,
          libraryScanScheduler = createdLibraryScanScheduler,
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
          libraryAdministrationLifecycle = libraryAdministrationLifecycle,
          libraryMaintenanceRequester = libraryMaintenanceRequester,
          libraryScanRequester = libraryScanRequester,
          mediaItemRepository = mediaItems,
          libraryRepository = libraries,
          effectiveServerPort = effectiveServerPort,
          effectiveServerContextPath = effectiveServerContextPath,
        ).also {
          createdWorkerPool.start()
          createdLibraryScanScheduler.start()
        }
      } catch (failure: Throwable) {
        runCatching { libraryScanScheduler?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { workerPool?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { heartbeat?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { oauthHttpClient?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
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
