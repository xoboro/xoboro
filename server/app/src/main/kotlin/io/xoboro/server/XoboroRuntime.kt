package io.xoboro.server

import com.github.f4b6a3.tsid.TsidCreator
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.server.media.AnalyzeBook
import io.xoboro.server.media.ZipMediaAnalyzer
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqApiKeyRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReconciliationStore
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import io.xoboro.server.security.Sha512TokenEncoder
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
        val media = JooqBookMediaRepository(database)
        val queue = JooqDurableTaskQueue(database)
        val userRepository = JooqUserRepository(database)
        val userLifecycle =
          UserLifecycle(
            users = userRepository,
            passwordHasher = BCryptPasswordHasher(),
            userIdFactory = { TsidCreator.getTsid256().toString() },
            currentTimeMillis = System::currentTimeMillis,
          )
        val apiKeyLifecycle =
          ApiKeyLifecycle(
            users = userRepository,
            apiKeys = JooqApiKeyRepository(database),
            tokenEncoder = Sha512TokenEncoder(),
            apiKeyIdFactory = { TsidCreator.getTsid256().toString() },
            plainTextKeyFactory = { UUID.randomUUID().toString().replace("-", "") },
            currentTimeMillis = System::currentTimeMillis,
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
