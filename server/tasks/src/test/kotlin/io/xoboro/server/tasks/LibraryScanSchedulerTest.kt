package io.xoboro.server.tasks

import io.xoboro.core.application.ClaimedTask
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.TaskEnqueue
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.ScanInterval
import io.xoboro.core.domain.SourceLocation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryScanSchedulerTest {
  @Test
  fun `enqueues startup scans and schedules enabled intervals once`() {
    val queue = RecordingQueue()
    val fixedRateScheduler = RecordingFixedRateScheduler()
    val hourly = library("hourly", ScanInterval.HOURLY, scanOnStartup = true)
    val disabled = library("disabled", ScanInterval.DISABLED, scanOnStartup = true)
    val everySixHours = library("six-hours", ScanInterval.EVERY_6H)
    val scheduler =
      LibraryScanScheduler(
        libraries = InMemoryLibraryRepository(listOf(hourly, disabled, everySixHours)),
        emitter = ScanLibraryTaskEmitter(queue, currentTimeMillis = { 100 }),
        scheduler = fixedRateScheduler,
      )

    scheduler.start()
    scheduler.start()

    assertEquals(
      setOf(
        "SCAN_LIBRARY_hourly_DEEP_false",
        "SCAN_LIBRARY_disabled_DEEP_false",
      ),
      queue.tasks.map(DurableTask::id).toSet(),
    )
    assertEquals(2, fixedRateScheduler.schedules.size)
    assertEquals(
      listOf(
        TimeUnit.HOURS.toMillis(1),
        TimeUnit.HOURS.toMillis(6),
      ),
      fixedRateScheduler.schedules.map(Schedule::intervalMillis),
    )
    fixedRateScheduler.schedules.first().task()
    assertEquals(3, queue.tasks.size)

    scheduler.close()
    scheduler.close()

    assertTrue(fixedRateScheduler.closed)
    assertTrue(fixedRateScheduler.schedules.all(Schedule::cancelled))
  }

  @Test
  fun `rescheduling replaces the previous registration and disabled cancels it`() {
    val queue = RecordingQueue()
    val fixedRateScheduler = RecordingFixedRateScheduler()
    val initial = library("library-1", ScanInterval.HOURLY)
    val scheduler =
      LibraryScanScheduler(
        libraries = InMemoryLibraryRepository(emptyList()),
        emitter = ScanLibraryTaskEmitter(queue, currentTimeMillis = { 100 }),
        scheduler = fixedRateScheduler,
      )

    scheduler.schedule(initial)
    val first = fixedRateScheduler.schedules.single()
    scheduler.schedule(initial.copy(settings = initial.settings.copy(scanInterval = ScanInterval.DAILY)))
    val second = fixedRateScheduler.schedules.last()

    assertTrue(first.cancelled)
    assertFalse(second.cancelled)
    assertEquals(TimeUnit.DAYS.toMillis(1), second.initialDelayMillis)
    assertEquals(TimeUnit.DAYS.toMillis(1), second.intervalMillis)

    scheduler.schedule(initial.copy(settings = initial.settings.copy(scanInterval = ScanInterval.DISABLED)))

    assertTrue(second.cancelled)
    assertEquals(2, fixedRateScheduler.schedules.size)
  }

  @Test
  fun `executor scheduler survives task failures`() {
    val calls = AtomicInteger()
    val failures = AtomicInteger()
    val completedAfterFailure = CountDownLatch(1)
    val scheduler =
      ExecutorFixedRateTaskScheduler(
        shutdownTimeoutMillis = 1_000,
        onFailure = { failures.incrementAndGet() },
      )
    scheduler.schedule(initialDelayMillis = 0, intervalMillis = 10) {
      if (calls.incrementAndGet() == 1) {
        error("synthetic scheduled failure")
      }
      completedAfterFailure.countDown()
    }

    assertTrue(completedAfterFailure.await(2, TimeUnit.SECONDS))
    scheduler.close()
    scheduler.close()

    assertEquals(1, failures.get())
    assertTrue(calls.get() >= 2)
    assertFailsWith<IllegalStateException> {
      scheduler.schedule(0, 10) {}
    }
  }

  @Test
  fun `maps every Komga scan interval exactly`() {
    assertEquals(
      mapOf(
        ScanInterval.DISABLED to null,
        ScanInterval.HOURLY to TimeUnit.HOURS.toMillis(1),
        ScanInterval.EVERY_6H to TimeUnit.HOURS.toMillis(6),
        ScanInterval.EVERY_12H to TimeUnit.HOURS.toMillis(12),
        ScanInterval.DAILY to TimeUnit.DAYS.toMillis(1),
        ScanInterval.WEEKLY to TimeUnit.DAYS.toMillis(7),
      ),
      ScanInterval.entries.associateWith(ScanInterval::toMillisOrNull),
    )
  }

  private fun library(
    id: String,
    interval: ScanInterval,
    scanOnStartup: Boolean = false,
  ): Library =
    Library(
      id = LibraryId(id),
      name = "Library $id",
      root = SourceLocation("local", "file:///synthetic/$id"),
      settings =
        LibrarySettings(
          scanOnStartup = scanOnStartup,
          scanInterval = interval,
        ),
      createdAtMillis = 1,
    )

  private class RecordingQueue : DurableTaskQueue {
    val tasks = mutableListOf<DurableTask>()

    override fun enqueue(
      task: DurableTask,
      nowMillis: Long,
    ): TaskEnqueue {
      tasks += task
      return TaskEnqueue.QUEUED
    }

    override fun claimNext(
      workerId: String,
      leaseToken: String,
      nowMillis: Long,
      leaseDurationMillis: Long,
    ): ClaimedTask? = error("not used")

    override fun renewLease(
      taskId: String,
      leaseToken: String,
      nowMillis: Long,
      leaseDurationMillis: Long,
    ): Boolean = error("not used")

    override fun complete(
      taskId: String,
      leaseToken: String,
    ): Boolean = error("not used")

    override fun fail(
      taskId: String,
      leaseToken: String,
      error: String,
      retryAtMillis: Long?,
      nowMillis: Long,
    ): Boolean = error("not used")

    override fun counts(): TaskCounts = error("not used")
  }

  private class RecordingFixedRateScheduler : FixedRateTaskScheduler {
    val schedules = mutableListOf<Schedule>()
    var closed = false

    override fun schedule(
      initialDelayMillis: Long,
      intervalMillis: Long,
      task: () -> Unit,
    ): ScheduledTaskRegistration {
      val schedule = Schedule(initialDelayMillis, intervalMillis, task)
      schedules += schedule
      return ScheduledTaskRegistration {
        schedule.cancelled = true
      }
    }

    override fun close() {
      closed = true
    }
  }

  private data class Schedule(
    val initialDelayMillis: Long,
    val intervalMillis: Long,
    val task: () -> Unit,
    var cancelled: Boolean = false,
  )

  private class InMemoryLibraryRepository(
    libraries: List<Library>,
  ) : LibraryRepository {
    private val items = libraries.associateBy { it.id }.toMutableMap()

    override fun findById(id: LibraryId): Library =
      findByIdOrNull(id) ?: throw NoSuchElementException()

    override fun findByIdOrNull(id: LibraryId): Library? = items[id]

    override fun findAll(): List<Library> = items.values.toList()

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> =
      ids.mapNotNull(items::get)

    override fun insert(library: Library) {
      items[library.id] = library
    }

    override fun update(library: Library) {
      items[library.id] = library
    }

    override fun delete(id: LibraryId) {
      items.remove(id)
    }

    override fun deleteAll() {
      items.clear()
    }

    override fun count(): Long = items.size.toLong()
  }
}
