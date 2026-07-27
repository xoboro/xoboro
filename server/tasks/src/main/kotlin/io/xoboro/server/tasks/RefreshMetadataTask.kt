package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.MetadataRefreshLifecycle
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.Book
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
    val nowMillis = now()
    var emitted = 0
    books
      .findAllByLibraryId(libraryId)
      .asSequence()
      .filter { it.deletedAtMillis == null }
      .forEach { if (enqueueBook(it, priority, nowMillis)) emitted += 1 }
    series
      .findAllByLibraryId(libraryId)
      .asSequence()
      .filter { it.deletedAtMillis == null }
      .forEach { if (enqueueSeries(it.id, priority, nowMillis)) emitted += 1 }
    return emitted
  }

  fun refreshBook(
    bookId: BookId,
    priority: Int = TaskPriority.HIGH,
  ): Boolean {
    val book = books.findByIdOrNull(bookId)?.takeIf { it.deletedAtMillis == null } ?: return false
    return enqueueBook(book, priority, now())
  }

  fun refreshSeries(
    seriesId: SeriesId,
    priority: Int = TaskPriority.HIGH,
  ): Int {
    val nowMillis = now()
    var emitted = 0
    books
      .findAllBySeriesId(seriesId)
      .asSequence()
      .filter { it.deletedAtMillis == null }
      .forEach { if (enqueueBook(it, priority, nowMillis)) emitted += 1 }
    series.findByIdOrNull(seriesId)?.takeIf { it.deletedAtMillis == null }?.let {
      if (enqueueSeries(it.id, priority, nowMillis)) emitted += 1
    }
    return emitted
  }

  fun refreshSeriesMetadata(
    seriesId: SeriesId,
    priority: Int = TaskPriority.HIGH,
  ): Boolean {
    val item = series.findByIdOrNull(seriesId)?.takeIf { it.deletedAtMillis == null } ?: return false
    return enqueueSeries(item.id, priority, now())
  }

  private fun enqueueBook(
    book: Book,
    priority: Int,
    nowMillis: Long,
  ): Boolean =
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

  private fun enqueueSeries(
    seriesId: SeriesId,
    priority: Int,
    nowMillis: Long,
  ): Boolean =
    queue.enqueue(
      task =
        DurableTask(
          id = seriesTaskId(seriesId),
          type = RefreshSeriesMetadataTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(RefreshSeriesMetadataTaskHandler.SERIES_ID_FIELD, seriesId.value)
            }.toString(),
          priority = priority,
          groupId = seriesId.value,
          availableAtMillis = nowMillis,
        ),
      nowMillis = nowMillis,
    )

  private fun now(): Long =
    currentTimeMillis().also {
      require(it >= 0) { "Task emission timestamp must not be negative" }
    }

  companion object {
    fun bookTaskId(bookId: BookId): String = "REFRESH_BOOK_METADATA_${bookId.value}"

    fun seriesTaskId(seriesId: SeriesId): String = "REFRESH_SERIES_METADATA_${seriesId.value}"
  }
}

class RefreshBookMetadataTaskHandler(
  private val metadata: MetadataRefreshLifecycle,
  private val afterRefresh: (BookId) -> Unit = {},
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val bookId = BookId(task.requiredStringPayload(json, BOOK_ID_FIELD, TASK_TYPE))
    metadata.refreshBook(bookId)?.let { afterRefresh(bookId) }
  }

  companion object {
    const val TASK_TYPE: String = "REFRESH_BOOK_METADATA"
    internal const val BOOK_ID_FIELD: String = "bookId"
  }
}

class RefreshSeriesMetadataTaskHandler(
  private val metadata: MetadataRefreshLifecycle,
  private val afterRefresh: (SeriesId) -> Unit = {},
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val seriesId = SeriesId(task.requiredStringPayload(json, SERIES_ID_FIELD, TASK_TYPE))
    metadata.refreshSeries(seriesId)?.let { afterRefresh(seriesId) }
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
