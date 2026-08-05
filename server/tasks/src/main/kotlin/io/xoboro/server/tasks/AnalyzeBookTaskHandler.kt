package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.TaskEnqueue
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.application.enqueueOrRetry
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AnalyzeBookTaskEmitter(
  private val books: BookRepository,
  private val queue: DurableTaskQueue,
  private val currentTimeMillis: () -> Long,
) {
  /**
   * Queues one task that will fan [analyzeLibrary] out from a worker.
   *
   * Same shape and same reason as [RefreshMetadataTaskEmitter.refreshLibraryDeferred]: one enqueue
   * per book is tens of thousands of inserts, which is a worker's job and not a request's. Inline,
   * it failed the request outright once a scan held the write lock.
   */
  fun analyzeLibraryDeferred(
    libraryId: LibraryId,
    priority: Int = TaskPriority.HIGH,
  ): TaskEnqueue {
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Task emission timestamp must not be negative" }
    return queue.enqueue(
      task =
        DurableTask(
          id = AnalyzeLibraryTaskHandler.taskId(libraryId),
          type = AnalyzeLibraryTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(AnalyzeLibraryTaskHandler.LIBRARY_ID_FIELD, libraryId.value)
            }.toString(),
          priority = priority,
          groupId = libraryId.value,
          availableAtMillis = nowMillis,
          maxAttempts = RefreshMetadataTaskEmitter.FAN_OUT_MAX_ATTEMPTS,
        ),
      nowMillis = nowMillis,
    )
  }

  fun analyzeLibrary(
    libraryId: LibraryId,
    priority: Int = TaskPriority.HIGH,
  ): Int =
    enqueueBooks(
      books.findAllByLibraryId(libraryId).asSequence(),
      priority,
    )

  fun analyzeBook(
    bookId: BookId,
    priority: Int = TaskPriority.HIGH,
  ): Boolean =
    books.findByIdOrNull(bookId)
      ?.takeIf { it.deletedAtMillis == null }
      ?.let { enqueueBooks(sequenceOf(it), priority) == 1 }
      ?: false

  fun analyzeSeries(
    seriesId: SeriesId,
    priority: Int = TaskPriority.HIGH,
  ): Int =
    enqueueBooks(
      books.findAllBySeriesId(seriesId).asSequence(),
      priority,
    )

  private fun enqueueBooks(
    candidates: Sequence<io.xoboro.core.domain.Book>,
    priority: Int,
  ): Int {
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Task emission timestamp must not be negative" }
    return candidates
      .filter { it.deletedAtMillis == null }
      .count { book ->
        queue.enqueueOrRetry(
          task =
            DurableTask(
              id = taskId(book.id),
              type = AnalyzeBookTaskHandler.TASK_TYPE,
              payloadJson =
                buildJsonObject {
                  put(AnalyzeBookTaskHandler.BOOK_ID_FIELD, book.id.value)
                }.toString(),
              priority = priority,
              groupId = book.seriesId.value,
              availableAtMillis = nowMillis,
            ),
          nowMillis = nowMillis,
        )
      }
  }

  companion object {
    fun taskId(bookId: BookId): String = "ANALYZE_BOOK_${bookId.value}"
  }
}

class AnalyzeBookTaskHandler(
  private val analyzeBook: (BookId) -> Unit,
  private val afterAnalyze: (BookId) -> Unit = {},
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val bookId =
      json.parseToJsonElement(task.payloadJson)
        .jsonObject[BOOK_ID_FIELD]
        ?.jsonPrimitive
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("ANALYZE_BOOK payload must contain a non-blank bookId")
    BookId(bookId).also { id ->
      analyzeBook(id)
      afterAnalyze(id)
    }
  }

  companion object {
    const val TASK_TYPE: String = "ANALYZE_BOOK"
    internal const val BOOK_ID_FIELD = "bookId"
  }
}

/**
 * Fans a library's analysis out into one task per book, from a worker rather than from the request
 * that asked for it. See [AnalyzeBookTaskEmitter.analyzeLibraryDeferred].
 */
class AnalyzeLibraryTaskHandler(
  private val analyzeLibrary: (LibraryId) -> Unit,
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
        ?: throw IllegalArgumentException("$TASK_TYPE payload must contain a non-blank libraryId")
    analyzeLibrary(LibraryId(libraryId))
  }

  companion object {
    const val TASK_TYPE: String = "ANALYZE_LIBRARY"
    internal const val LIBRARY_ID_FIELD = "libraryId"

    fun taskId(libraryId: LibraryId): String = "${TASK_TYPE}_${libraryId.value}"
  }
}
