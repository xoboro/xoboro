package io.xoboro.server.tasks

import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.TaskPriority
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
  fun scanLibrary(
    libraryId: LibraryId,
    deep: Boolean = false,
    priority: Int = TaskPriority.DEFAULT,
  ): Boolean {
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Task emission timestamp must not be negative" }
    return queue.enqueue(
      task =
        DurableTask(
          id = taskId(libraryId, deep),
          type = ScanLibraryTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(LIBRARY_ID_FIELD, libraryId.value)
              put(DEEP_FIELD, deep)
            }.toString(),
          priority = priority,
          availableAtMillis = nowMillis,
        ),
      nowMillis = nowMillis,
    )
  }

  companion object {
    internal const val LIBRARY_ID_FIELD: String = "libraryId"
    internal const val DEEP_FIELD: String = "deep"

    fun taskId(
      libraryId: LibraryId,
      deep: Boolean,
    ): String = "SCAN_LIBRARY_${libraryId.value}_DEEP_$deep"
  }
}

class ScanLibraryTaskHandler(
  private val libraries: LibraryRepository,
  private val scanner: CatalogScanner,
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
      scanner.scan(library, deep)
      afterScan(library, deep)
    }
  }

  companion object {
    const val TASK_TYPE: String = "SCAN_LIBRARY"
  }
}
