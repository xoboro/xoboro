package io.xoboro.server.tasks

import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.LibraryAvailabilityLifecycle
import io.xoboro.core.application.SourceInventoryUnavailableException
import io.xoboro.core.application.TaskEnqueue
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.application.TaskStoreUnavailableException
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ScanLibraryTaskEmitter(
  private val queue: DurableTaskQueue,
  private val currentTimeMillis: () -> Long,
) {
  /**
   * Queues a scan, or a second pass when one is already running.
   *
   * A scan reads the source once, near its start. A request that arrives after that read cannot be
   * satisfied by the run in flight, however much of it is left - the change it is asking about has
   * already been missed. Collapsing onto the running task's id, which is what a deterministic id
   * does, therefore answered `202` and did nothing: delete a file, press Scan, and the catalog kept
   * serving the file until something else happened to trigger a scan. It also made an acceptance
   * test that deletes a file and rescans fail intermittently, which is how this was found.
   *
   * So a request that finds its id running queues [FOLLOW_UP_SUFFIX] instead, and a request that
   * finds *that* running queues the plain id. Two ids alternating, both in the library's exclusion
   * group, means at most one scan runs and at most one waits - any number of requests during a scan
   * collapse into exactly one more pass, which is what they collectively mean.
   */
  fun scanLibrary(
    libraryId: LibraryId,
    deep: Boolean = false,
    priority: Int = TaskPriority.DEFAULT,
  ): Boolean {
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Task emission timestamp must not be negative" }
    val queued = enqueueScan(taskId(libraryId, deep), libraryId, deep, priority, nowMillis)
    if (queued != TaskEnqueue.ALREADY_RUNNING) {
      return queued.orRetry(taskId(libraryId, deep))
    }
    val followUpId = taskId(libraryId, deep) + FOLLOW_UP_SUFFIX
    return enqueueScan(followUpId, libraryId, deep, priority, nowMillis).orRetry(followUpId)
  }

  private fun enqueueScan(
    id: String,
    libraryId: LibraryId,
    deep: Boolean,
    priority: Int,
    nowMillis: Long,
  ): TaskEnqueue =
    queue.enqueue(
      task =
        DurableTask(
          id = id,
          type = ScanLibraryTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(LIBRARY_ID_FIELD, libraryId.value)
              put(DEEP_FIELD, deep)
            }.toString(),
          priority = priority,
          // Two scans of one library must never run at once. `begin` retires every other STAGING
          // session for the library, so a concurrent pair would each discard the other's staged
          // candidates. The group also serialises a scan against that library's metadata fan-out,
          // which share it - the two competing for the write lock is what this group is for.
          groupId = libraryId.value,
          availableAtMillis = nowMillis,
        ),
      nowMillis = nowMillis,
    )

  /**
   * A busy store still has to reach the caller, because a scan request is not replayable: the caller
   * is either a request that must report the condition or a task that must let the worker retry it.
   */
  private fun TaskEnqueue.orRetry(taskId: String): Boolean =
    when (this) {
      TaskEnqueue.QUEUED -> true
      TaskEnqueue.ALREADY_RUNNING -> false
      TaskEnqueue.UNAVAILABLE -> throw TaskStoreUnavailableException(taskId)
    }

  companion object {
    internal const val LIBRARY_ID_FIELD: String = "libraryId"
    internal const val DEEP_FIELD: String = "deep"

    /** Distinguishes the pass queued *behind* a running scan from the running scan itself. */
    internal const val FOLLOW_UP_SUFFIX: String = "_AGAIN"

    fun taskId(
      libraryId: LibraryId,
      deep: Boolean,
    ): String = "SCAN_LIBRARY_${libraryId.value}_DEEP_$deep"
  }
}

class ScanLibraryTaskHandler(
  private val libraries: LibraryRepository,
  private val scanner: CatalogScanner,
  private val availability: LibraryAvailabilityLifecycle? = null,
  private val afterScan: (Library, Boolean) -> Unit = { _, _ -> },
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val payload = json.parseToJsonElement(task.payloadJson).jsonObject
    val libraryId =
      payload[ScanLibraryTaskEmitter.LIBRARY_ID_FIELD]
        ?.jsonPrimitive
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?.let(::LibraryId)
        ?: throw IllegalArgumentException(
          "SCAN_LIBRARY payload must contain a non-blank libraryId",
        )
    val deep =
      payload[ScanLibraryTaskEmitter.DEEP_FIELD]
        ?.jsonPrimitive
        ?.takeIf { !it.isString }
        ?.booleanOrNull
        ?: throw IllegalArgumentException(
          "SCAN_LIBRARY payload must contain a boolean deep flag",
        )

    libraries.findByIdOrNull(libraryId)?.let { library ->
      try {
        scanner.scan(library, deep)
      } catch (failure: SourceInventoryUnavailableException) {
        availability?.markUnavailable(library.id)
        throw failure
      }
      availability?.markAvailable(library.id)
      afterScan(library, deep)
    }
  }

  companion object {
    const val TASK_TYPE: String = "SCAN_LIBRARY"
  }
}
