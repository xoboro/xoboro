package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.LibraryTrashStore
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.LibraryId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class EmptyLibraryTrashTaskEmitter(
  private val queue: DurableTaskQueue,
  private val currentTimeMillis: () -> Long,
) {
  fun emptyTrash(
    libraryId: LibraryId,
    priority: Int = TaskPriority.HIGH,
  ): Boolean {
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Task emission timestamp must not be negative" }
    return queue.enqueue(
      task =
        DurableTask(
          id = taskId(libraryId),
          type = EmptyLibraryTrashTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(EmptyLibraryTrashTaskHandler.LIBRARY_ID_FIELD, libraryId.value)
            }.toString(),
          priority = priority,
          availableAtMillis = nowMillis,
        ),
      nowMillis = nowMillis,
    )
  }

  companion object {
    fun taskId(libraryId: LibraryId): String = "EMPTY_TRASH_${libraryId.value}"
  }
}

class EmptyLibraryTrashTaskHandler(
  private val trash: LibraryTrashStore,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val libraryId =
      json.parseToJsonElement(task.payloadJson)
        .jsonObject[LIBRARY_ID_FIELD]
        ?.jsonPrimitive
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?.let(::LibraryId)
        ?: throw IllegalArgumentException(
          "EMPTY_TRASH payload must contain a non-blank libraryId",
        )
    trash.emptyTrash(libraryId)
  }

  companion object {
    const val TASK_TYPE: String = "EMPTY_TRASH"
    internal const val LIBRARY_ID_FIELD: String = "libraryId"
  }
}
