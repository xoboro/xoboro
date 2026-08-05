package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.MetadataRefreshLifecycle
import io.xoboro.core.application.TaskEnqueue
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.application.enqueueOrRetry
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
  /**
   * Queues one task that will fan [refreshLibrary] out from a worker.
   *
   * A library's fan-out is one enqueue per book plus one per series - 24,696 inserts for a library
   * of 314 webtoon series, and 145,105 across the whole install. Doing that inline left a request
   * holding the write lock for the duration and, worse, gave it nowhere to go when the lock was
   * already held: the loop died mid-way with a `500` and a partially queued library, which is what
   * left one library with no series metadata at all.
   *
   * One row is the whole request's work. Everything after it belongs to the worker, which already
   * has the lease, backoff and dead-letter machinery a fan-out of that size needs.
   */
  fun refreshLibraryDeferred(
    libraryId: LibraryId,
    priority: Int = TaskPriority.HIGH,
  ): TaskEnqueue {
    val nowMillis = now()
    return queue.enqueue(
      task =
        DurableTask(
          id = libraryTaskId(libraryId),
          type = RefreshLibraryMetadataTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(RefreshLibraryMetadataTaskHandler.LIBRARY_ID_FIELD, libraryId.value)
            }.toString(),
          priority = priority,
          groupId = libraryId.value,
          availableAtMillis = nowMillis,
          // A fan-out this long runs *while* a scan holds the write lock, so it expects to be
          // deferred repeatedly before it gets through. The default budget of 3 would dead-letter
          // it during an ordinary first scan; each retry re-enqueues idempotently and picks up
          // where contention stopped it.
          maxAttempts = FAN_OUT_MAX_ATTEMPTS,
        ),
      nowMillis = nowMillis,
    )
  }

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
    queue.enqueueOrRetry(
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
    queue.enqueueOrRetry(
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
    internal const val FAN_OUT_MAX_ATTEMPTS: Int = 10

    fun bookTaskId(bookId: BookId): String = "REFRESH_BOOK_METADATA_${bookId.value}"

    fun seriesTaskId(seriesId: SeriesId): String = "REFRESH_SERIES_METADATA_${seriesId.value}"

    fun libraryTaskId(libraryId: LibraryId): String =
      "REFRESH_LIBRARY_METADATA_${libraryId.value}"
  }
}

/**
 * Fans a library's metadata refresh out into one task per book and per series.
 *
 * Runs the fan-out from a worker rather than from the request that asked for it, so contention on
 * the task store defers the work instead of failing a request half-done. [refreshLibrary] is passed
 * in rather than the emitter itself, matching [AnalyzeBookTaskHandler]'s shape and keeping this
 * handler free of the repositories the fan-out reads.
 */
class RefreshLibraryMetadataTaskHandler(
  private val refreshLibrary: (LibraryId) -> Unit,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    refreshLibrary(LibraryId(task.requiredStringPayload(json, LIBRARY_ID_FIELD, TASK_TYPE)))
  }

  companion object {
    const val TASK_TYPE: String = "REFRESH_LIBRARY_METADATA"
    internal const val LIBRARY_ID_FIELD: String = "libraryId"
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
