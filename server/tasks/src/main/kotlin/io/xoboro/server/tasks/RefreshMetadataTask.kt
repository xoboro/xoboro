package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.MetadataRefreshLifecycle
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RefreshMetadataTaskEmitter(
  private val books: BookRepository,
  private val series: SeriesRepository,
  private val queue: DurableTaskQueue,
  private val currentTimeMillis: () -> Long,
) {
  fun refreshLibrary(
    libraryId: LibraryId,
    priority: Int = TaskPriority.HIGH,
  ): Int {
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Task emission timestamp must not be negative" }
    var emitted = 0
    books
      .findAllByLibraryId(libraryId)
      .asSequence()
      .filter { it.deletedAtMillis == null }
      .forEach { book ->
        if (
          queue.enqueue(
            task =
              DurableTask(
                id = bookTaskId(book.id),
                type = RefreshBookMetadataTaskHandler.TASK_TYPE,
                payloadJson =
                  buildJsonObject {
                    put(RefreshBookMetadataTaskHandler.BOOK_ID_FIELD, book.id.value)
                  }.toString(),
                priority = priority,
                groupId = book.seriesId.value,
                availableAtMillis = nowMillis,
              ),
            nowMillis = nowMillis,
          )
        ) {
          emitted += 1
        }
      }
    series
      .findAllByLibraryId(libraryId)
      .asSequence()
      .filter { it.deletedAtMillis == null }
      .forEach { item ->
        if (
          queue.enqueue(
            task =
              DurableTask(
                id = seriesTaskId(item.id),
                type = RefreshSeriesMetadataTaskHandler.TASK_TYPE,
                payloadJson =
                  buildJsonObject {
                    put(RefreshSeriesMetadataTaskHandler.SERIES_ID_FIELD, item.id.value)
                  }.toString(),
                priority = priority,
                groupId = item.id.value,
                availableAtMillis = nowMillis,
              ),
            nowMillis = nowMillis,
          )
        ) {
          emitted += 1
        }
      }
    return emitted
  }

  companion object {
    fun bookTaskId(bookId: BookId): String = "REFRESH_BOOK_METADATA_${bookId.value}"

    fun seriesTaskId(seriesId: SeriesId): String = "REFRESH_SERIES_METADATA_${seriesId.value}"
  }
}

class RefreshBookMetadataTaskHandler(
  private val metadata: MetadataRefreshLifecycle,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    metadata.refreshBook(BookId(task.requiredStringPayload(json, BOOK_ID_FIELD, TASK_TYPE)))
  }

  companion object {
    const val TASK_TYPE: String = "REFRESH_BOOK_METADATA"
    internal const val BOOK_ID_FIELD: String = "bookId"
  }
}

class RefreshSeriesMetadataTaskHandler(
  private val metadata: MetadataRefreshLifecycle,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    metadata.refreshSeries(SeriesId(task.requiredStringPayload(json, SERIES_ID_FIELD, TASK_TYPE)))
  }

  companion object {
    const val TASK_TYPE: String = "REFRESH_SERIES_METADATA"
    internal const val SERIES_ID_FIELD: String = "seriesId"
  }
}

private fun DurableTask.requiredStringPayload(
  json: Json,
  field: String,
  taskType: String,
): String =
  json.parseToJsonElement(payloadJson)
    .jsonObject[field]
    ?.jsonPrimitive
    ?.takeIf { it.isString }
    ?.contentOrNull
    ?.takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("$taskType payload must contain a non-blank $field")
