package io.xoboro.server.tasks

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.ScanInterval
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface ScheduledTaskRegistration : AutoCloseable {
  override fun close()
}

interface FixedRateTaskScheduler : AutoCloseable {
  fun schedule(
    initialDelayMillis: Long,
    intervalMillis: Long,
    task: () -> Unit,
  ): ScheduledTaskRegistration
}

class ExecutorFixedRateTaskScheduler(
  private val shutdownTimeoutMillis: Long = 30_000,
  private val onFailure: (Throwable) -> Unit = {},
  private val executor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "xoboro-library-scheduler").apply { isDaemon = true }
    },
) : FixedRateTaskScheduler {
  private val closed = AtomicBoolean(false)

  init {
    require(shutdownTimeoutMillis > 0) { "Scheduler shutdown timeout must be positive" }
  }

  override fun schedule(
    initialDelayMillis: Long,
    intervalMillis: Long,
    task: () -> Unit,
  ): ScheduledTaskRegistration {
    check(!closed.get()) { "Fixed-rate task scheduler is closed" }
    require(initialDelayMillis >= 0) { "Initial delay must not be negative" }
    require(intervalMillis > 0) { "Task interval must be positive" }
    val future =
      executor.scheduleAtFixedRate(
        {
          runCatching(task).exceptionOrNull()?.let { failure ->
            runCatching { onFailure(failure) }
          }
        },
        initialDelayMillis,
        intervalMillis,
        TimeUnit.MILLISECONDS,
      )
    return ScheduledTaskRegistration {
      future.cancel(false)
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    executor.shutdown()
    if (!executor.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
      executor.shutdownNow()
      executor.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)
    }
  }
}

class LibraryScanScheduler(
  private val libraries: LibraryRepository,
  private val emitter: ScanLibraryTaskEmitter,
  private val scheduler: FixedRateTaskScheduler,
) : AutoCloseable {
  private val lock = Any()
  private val started = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  private val registrations = ConcurrentHashMap<LibraryId, ScheduledTaskRegistration>()

  fun start() {
    check(!closed.get()) { "Library scan scheduler is closed" }
    if (!started.compareAndSet(false, true)) return
    libraries.findAll().forEach { library ->
      if (library.settings.scanOnStartup) {
        emitter.scanLibrary(library.id)
      }
      schedule(library)
    }
  }

  fun schedule(library: Library) {
    synchronized(lock) {
      check(!closed.get()) { "Library scan scheduler is closed" }
      registrations.remove(library.id)?.close()
      val intervalMillis = library.settings.scanInterval.toMillisOrNull() ?: return
      val registration =
        scheduler.schedule(
          initialDelayMillis = intervalMillis,
          intervalMillis = intervalMillis,
        ) {
          emitter.scanLibrary(library.id)
        }
      registrations[library.id] = registration
    }
  }

  fun cancel(libraryId: LibraryId) {
    synchronized(lock) {
      registrations.remove(libraryId)?.close()
    }
  }

  override fun close() {
    val registrationsToClose =
      synchronized(lock) {
        if (!closed.compareAndSet(false, true)) return
        registrations.values.toList().also {
          registrations.clear()
        }
      }
    registrationsToClose.forEach(ScheduledTaskRegistration::close)
    scheduler.close()
  }
}

internal fun ScanInterval.toMillisOrNull(): Long? =
  when (this) {
    ScanInterval.DISABLED -> null
    ScanInterval.HOURLY -> TimeUnit.HOURS.toMillis(1)
    ScanInterval.EVERY_6H -> TimeUnit.HOURS.toMillis(6)
    ScanInterval.EVERY_12H -> TimeUnit.HOURS.toMillis(12)
    ScanInterval.DAILY -> TimeUnit.DAYS.toMillis(1)
    ScanInterval.WEEKLY -> TimeUnit.DAYS.toMillis(7)
  }
