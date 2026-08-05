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
  private val chunkSize: Int = RefreshMetadataTaskEmitter.FAN_OUT_CHUNK_SIZE,
  private val currentTimeMillis: () -> Long,
) {
  init {
    require(chunkSize > 0) { "Fan-out chunk size must be positive" }
  }

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

  /**
   * Emits at most [chunkSize] of a library's analyses, chaining a successor for the remainder.
   *
   * See [LibraryFanOutCursor]: an unbounded pass over 24,696 books cannot be relied on to reach its
   * own end, because one busy enqueue defers the whole task and the re-run starts over - re-queuing
   * every book it had already analysed, since a completed task leaves no row behind.
   */
  fun analyzeLibrary(
    libraryId: LibraryId,
    priority: Int = TaskPriority.HIGH,
    from: LibraryFanOutCursor = LibraryFanOutCursor.BOOKS_START,
  ): Int {
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Task emission timestamp must not be negative" }
    val pending =
      books
        .findAllByLibraryId(libraryId)
        .filter { it.deletedAtMillis == null }
        .sortedBy { it.id.value }
        .filter { from.afterId == null || it.id.value > from.afterId }
    val chunk = pending.take(chunkSize)
    val emitted = enqueueBooks(chunk.asSequence(), priority)
    if (pending.size > chunk.size) {
      resumeAfter(libraryId, priority, chunk.last().id.value, nowMillis)
    }
    return emitted
  }

  /**
   * Queues the chunk that carries on after [afterId].
   *
   * [enqueueOrRetry] on purpose: a busy store must fail this task so the worker re-runs the same
   * chunk, because losing the successor abandons the rest of the library with nothing to say so.
   * The successor's id encodes its cursor, so the re-run queues the same one rather than a second.
   */
  private fun resumeAfter(
    libraryId: LibraryId,
    priority: Int,
    afterId: String,
    nowMillis: Long,
  ) {
    val cursor = LibraryFanOutCursor(LibraryFanOutCursor.Stage.BOOKS, afterId)
    queue.enqueueOrRetry(
      task =
        DurableTask(
          id =
            AnalyzeLibraryTaskHandler.taskId(libraryId) +
              RefreshMetadataTaskEmitter.RESUME_SEPARATOR +
              cursor.encode(),
          type = AnalyzeLibraryTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(AnalyzeLibraryTaskHandler.LIBRARY_ID_FIELD, libraryId.value)
              put(AnalyzeLibraryTaskHandler.CURSOR_FIELD, cursor.encode())
            }.toString(),
          priority = priority,
          groupId = libraryId.value,
          availableAtMillis = nowMillis,
          maxAttempts = RefreshMetadataTaskEmitter.FAN_OUT_MAX_ATTEMPTS,
        ),
      nowMillis = nowMillis,
    )
  }

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
  private val analyzeLibrary: (LibraryId, LibraryFanOutCursor) -> Unit,
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
    analyzeLibrary(
      LibraryId(libraryId),
      LibraryFanOutCursor.decode(
        task.optionalStringPayload(json, CURSOR_FIELD),
        LibraryFanOutCursor.BOOKS_START,
      ),
    )
  }

  companion object {
    const val TASK_TYPE: String = "ANALYZE_LIBRARY"
    internal const val LIBRARY_ID_FIELD = "libraryId"

    /** Absent on the task a request queues, present on every chunk that continues it. */
    internal const val CURSOR_FIELD = "cursor"

    fun taskId(libraryId: LibraryId): String = "${TASK_TYPE}_${libraryId.value}"
  }
}
