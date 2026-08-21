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
  private val chunkSize: Int = FAN_OUT_CHUNK_SIZE,
  private val currentTimeMillis: () -> Long,
) {
  init {
    require(chunkSize > 0) { "Fan-out chunk size must be positive" }
  }

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
  /**
   * [seriesOnly] stops the fan-out before it reaches books, which is the difference between 3,339
   * tasks and about 148,000 on this install.
   *
   * It exists because there was no way to ask for series artwork on its own. A series' cover comes
   * from its sidecar and is rewritten by a series refresh, so regenerating 3,339 covers meant asking
   * for a whole library refresh - and that also queued a book metadata refresh for all 145,105
   * books, the most expensive per-item operation in the system. It re-reads the file and rebuilds
   * the full-text row; V31 measured that path at 43 minutes of CPU across 111,745 books. The host
   * reached 758% CPU on 10 cores and had to be recovered by dropping the task pool to 1 and deleting
   * the unclaimed queue.
   */
  fun refreshLibraryDeferred(
    libraryId: LibraryId,
    priority: Int = TaskPriority.HIGH,
    seriesOnly: Boolean = false,
  ): TaskEnqueue {
    val nowMillis = now()
    return queue.enqueue(
      task =
        DurableTask(
          id = libraryTaskId(libraryId, seriesOnly),
          type = RefreshLibraryMetadataTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(RefreshLibraryMetadataTaskHandler.LIBRARY_ID_FIELD, libraryId.value)
              put(RefreshLibraryMetadataTaskHandler.SERIES_ONLY_FIELD, seriesOnly)
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

  /**
   * Emits at most [chunkSize] of a library's refresh tasks, chaining a successor for the remainder.
   *
   * Series first, and that order still matters. A library's books outnumber its series by two orders
   * of magnitude - 24,696 against 314 in one real library - while the series sidecar carries the
   * title, summary and status a refresh visibly produces. Book metadata has a second route to the
   * same place, because a successful analysis chains its own refresh, so it is the half that can
   * afford to go last: with books first, a library sat at 27 of 314 series filled while the queue
   * looked healthy.
   *
   * Ordering was the mitigation; chunking is the fix. See [LibraryFanOutCursor] for why a single
   * unbounded pass could never be relied on to reach its own end.
   */
  fun refreshLibrary(
    libraryId: LibraryId,
    priority: Int = TaskPriority.HIGH,
    from: LibraryFanOutCursor = LibraryFanOutCursor.START,
    seriesOnly: Boolean = false,
  ): Int {
    val nowMillis = now()
    var emitted = 0
    var budget = chunkSize
    var cursor = from

    if (cursor.stage == LibraryFanOutCursor.Stage.SERIES) {
      for (id in liveSeriesIds(libraryId, cursor.afterId)) {
        if (budget == 0) {
          return emitted.also { resumeAt(libraryId, priority, cursor, nowMillis, seriesOnly) }
        }
        if (enqueueSeries(id, priority, nowMillis)) emitted += 1
        cursor = LibraryFanOutCursor(LibraryFanOutCursor.Stage.SERIES, id.value)
        budget -= 1
      }
      // The scope is checked where the stages meet rather than at the top, so a series-only fan-out
      // that was interrupted and resumed still stops here instead of falling through to books.
      if (seriesOnly) return emitted
      cursor = LibraryFanOutCursor.BOOKS_START
    }
    if (seriesOnly) return emitted
    for (book in liveBooks(libraryId, cursor.afterId)) {
      if (budget == 0) {
        return emitted.also { resumeAt(libraryId, priority, cursor, nowMillis, seriesOnly) }
      }
      if (enqueueBook(book, priority, nowMillis)) emitted += 1
      cursor = LibraryFanOutCursor(LibraryFanOutCursor.Stage.BOOKS, book.id.value)
      budget -= 1
    }
    return emitted
  }

  private fun liveSeriesIds(
    libraryId: LibraryId,
    afterId: String?,
  ): List<SeriesId> =
    series
      .findAllByLibraryId(libraryId)
      .filter { it.deletedAtMillis == null }
      .map { it.id }
      .sortedBy { it.value }
      .filter { afterId == null || it.value > afterId }

  private fun liveBooks(
    libraryId: LibraryId,
    afterId: String?,
  ): List<Book> =
    books
      .findAllByLibraryId(libraryId)
      .filter { it.deletedAtMillis == null }
      .sortedBy { it.id.value }
      .filter { afterId == null || it.id.value > afterId }

  /**
   * Queues the chunk that carries on from [cursor].
   *
   * Deliberately [enqueueOrRetry]: a busy store here must fail the task so the worker re-runs this
   * same chunk, because losing the successor would abandon the rest of the library silently. The
   * chunk's own id encodes the cursor, so the re-run queues the same successor rather than a second
   * one.
   */
  private fun resumeAt(
    libraryId: LibraryId,
    priority: Int,
    cursor: LibraryFanOutCursor,
    nowMillis: Long,
    seriesOnly: Boolean,
  ) {
    queue.enqueueOrRetry(
      task =
        DurableTask(
          id = "${libraryTaskId(libraryId, seriesOnly)}$RESUME_SEPARATOR${cursor.encode()}",
          type = RefreshLibraryMetadataTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(RefreshLibraryMetadataTaskHandler.LIBRARY_ID_FIELD, libraryId.value)
              put(RefreshLibraryMetadataTaskHandler.CURSOR_FIELD, cursor.encode())
              put(RefreshLibraryMetadataTaskHandler.SERIES_ONLY_FIELD, seriesOnly)
            }.toString(),
          priority = priority,
          groupId = libraryId.value,
          availableAtMillis = nowMillis,
          maxAttempts = FAN_OUT_MAX_ATTEMPTS,
        ),
      nowMillis = nowMillis,
    )
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

    /**
     * How many enqueues one fan-out chunk performs before handing the rest to a successor.
     *
     * Sized against what contention costs when it interrupts a chunk: the chunk re-runs whole, so a
     * thousand is a second or two of re-queuing rather than a whole library's worth. It is also the
     * bound on how far ahead of the work the queue runs, which matters because each chunk re-reads
     * the library's rows to find its starting point.
     */
    internal const val FAN_OUT_CHUNK_SIZE: Int = 1_000

    /**
     * Separates a resumed chunk's cursor from the library task id it continues.
     *
     * A distinct id per chunk is what lets a successor be queued while its predecessor is still
     * `RUNNING` - an `enqueue` collision on a running row keeps the old payload and would drop the
     * cursor. Shared with [AnalyzeBookTaskEmitter], which chains the same way.
     */
    internal const val RESUME_SEPARATOR: String = "__RESUME__"

    fun bookTaskId(bookId: BookId): String = "REFRESH_BOOK_METADATA_${bookId.value}"

    fun seriesTaskId(seriesId: SeriesId): String = "REFRESH_SERIES_METADATA_${seriesId.value}"

    /**
     * A series-only fan-out gets an id of its own so it cannot be deduplicated into a full one.
     * They queue the same task type against the same library, and the queue keeps the row it
     * already has: sharing an id would silently answer "covers only" with a request for everything,
     * or drop it entirely.
     */
    fun libraryTaskId(
      libraryId: LibraryId,
      seriesOnly: Boolean = false,
    ): String =
      if (seriesOnly) {
        "REFRESH_LIBRARY_SERIES_METADATA_${libraryId.value}"
      } else {
        "REFRESH_LIBRARY_METADATA_${libraryId.value}"
      }
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
  private val refreshLibrary: (LibraryId, LibraryFanOutCursor, Boolean) -> Unit,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    refreshLibrary(
      LibraryId(task.requiredStringPayload(json, LIBRARY_ID_FIELD, TASK_TYPE)),
      LibraryFanOutCursor.decode(
        task.optionalStringPayload(json, CURSOR_FIELD),
        LibraryFanOutCursor.START,
      ),
      task.optionalBooleanPayload(json, SERIES_ONLY_FIELD),
    )
  }

  companion object {
    const val TASK_TYPE: String = "REFRESH_LIBRARY_METADATA"
    internal const val LIBRARY_ID_FIELD: String = "libraryId"

    /** Absent on the task a request queues, present on every chunk that continues it. */
    internal const val CURSOR_FIELD: String = "cursor"

    /**
     * Absent on a payload written before this existed, which reads as `false` - the whole-library
     * fan-out those tasks were queued for.
     */
    internal const val SERIES_ONLY_FIELD: String = "seriesOnly"
  }
}

class RefreshBookMetadataTaskHandler(
  private val metadata: MetadataRefreshLifecycle,
  private val afterRefresh: (BookId, Int) -> Unit = { _, _ -> },
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val bookId = BookId(task.requiredStringPayload(json, BOOK_ID_FIELD, TASK_TYPE))
    metadata.refreshBook(bookId)?.let { afterRefresh(bookId, task.priority) }
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
  optionalStringPayload(json, field)
    ?: throw IllegalArgumentException("$taskType payload must contain a non-blank $field")

/**
 * Reads a payload flag, answering `false` for absent, malformed, or anything that is not a boolean.
 *
 * Not [optionalStringPayload]: that one narrows to `isString` and so returns null for the JSON
 * boolean `true` that [buildJsonObject] writes, which reads back as `false` - a scope silently
 * widening to the whole library. A flag whose failure mode is "does more work than asked" is worth
 * its own function.
 */
internal fun DurableTask.optionalBooleanPayload(
  json: Json,
  field: String,
): Boolean =
  json
    .parseToJsonElement(payloadJson)
    .jsonObject[field]
    ?.jsonPrimitive
    ?.contentOrNull
    ?.toBooleanStrictOrNull()
    ?: false

internal fun DurableTask.optionalStringPayload(
  json: Json,
  field: String,
): String? =
  json.parseToJsonElement(payloadJson)
    .jsonObject[field]
    ?.jsonPrimitive
    ?.takeIf { it.isString }
    ?.contentOrNull
    ?.takeIf(String::isNotBlank)
