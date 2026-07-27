package io.xoboro.server

import com.github.f4b6a3.tsid.TsidCreator
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.AnnouncementLifecycle
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.application.RememberMeTokenService
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
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
import io.xoboro.server.persistence.JooqMediaItemRepository
import io.xoboro.server.persistence.JooqServerSettingRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import io.xoboro.server.security.InMemoryUserSessionRepository
import io.xoboro.server.security.Sha512TokenEncoder
import io.xoboro.server.security.SpringCompatibleRememberMeTokenService
import io.xoboro.server.sources.local.LocalSourceInventory
import io.xoboro.server.sources.local.LocalSourceMediaAccess
import io.xoboro.server.tasks.AnalyzeBookTaskHandler
import io.xoboro.server.tasks.DurableTaskWorker
import io.xoboro.server.tasks.ExecutorFixedRateTaskScheduler
import io.xoboro.server.tasks.LibraryScanScheduler
import io.xoboro.server.tasks.ScanLibraryTaskEmitter
import io.xoboro.server.tasks.ScanLibraryTaskHandler
import io.xoboro.server.tasks.ScheduledLeaseHeartbeat
import io.xoboro.server.tasks.TaskWorkerPool
import io.xoboro.server.tasks.TaskWorkerPoolPolicy
import io.xoboro.server.tasks.TaskWorkerPolicy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

class XoboroRuntime private constructor(
  private val database: XoboroDatabase,
  private val libraryScanScheduler: LibraryScanScheduler,
  private val heartbeat: ScheduledLeaseHeartbeat,
  private val workerPool: TaskWorkerPool,
  val userLifecycle: UserLifecycle,
  val apiKeyLifecycle: ApiKeyLifecycle,
  val authenticationActivityLifecycle: AuthenticationActivityLifecycle,
  val userSessionLifecycle: UserSessionLifecycle,
  val rememberMeTokenService: RememberMeTokenService,
  val clientSettingsLifecycle: ClientSettingsLifecycle,
  val announcementLifecycle: AnnouncementLifecycle,
  val mediaItemRepository: MediaItemRepository,
  val libraryRepository: LibraryRepository,
) : AutoCloseable {
  private val closed = AtomicBoolean(false)

  fun isReady(): Boolean =
    !closed.get() && database.isAvailable()

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    var failure: Throwable? = null
    listOf<AutoCloseable>(
      libraryScanScheduler,
      workerPool,
      heartbeat,
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
    const val DEFAULT_REMEMBER_ME_TIMEOUT_MILLIS: Long = 365L * 24 * 60 * 60 * 1_000
    const val DEFAULT_REMEMBER_ME_MAX_AGE_SECONDS: Int = 365 * 24 * 60 * 60
    private const val REMEMBER_ME_KEY_SETTING: String = "REMEMBER_ME_KEY"

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
        val rememberMeTokenService =
          SpringCompatibleRememberMeTokenService(
            users = userRepository,
            secretKey =
              settings.findOrCreate(REMEMBER_ME_KEY_SETTING) {
                UUID.randomUUID().toString().replace("-", "")
              },
            currentTimeMillis = System::currentTimeMillis,
            tokenValidityMillis = DEFAULT_REMEMBER_ME_TIMEOUT_MILLIS,
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
                ),
                AnalyzeBookTaskHandler(
                  analyzeBook = { bookId ->
                    analyzeBook.execute(bookId)
                  },
                ),
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
                workerCount = config.workerCount,
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
          userLifecycle = userLifecycle,
          apiKeyLifecycle = apiKeyLifecycle,
          authenticationActivityLifecycle = authenticationActivityLifecycle,
          userSessionLifecycle = userSessionLifecycle,
          rememberMeTokenService = rememberMeTokenService,
          clientSettingsLifecycle = clientSettingsLifecycle,
          announcementLifecycle = announcementLifecycle,
          mediaItemRepository = mediaItems,
          libraryRepository = libraries,
        ).also {
          createdWorkerPool.start()
          createdLibraryScanScheduler.start()
        }
      } catch (failure: Throwable) {
        runCatching { libraryScanScheduler?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { workerPool?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { heartbeat?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { database.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
      }
    }
  }
}
