package io.xoboro.server.tasks

import io.xoboro.core.application.BookImportCommand
import io.xoboro.core.application.CatalogFileLifecycleRequester
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.SourceCopyMode
import io.xoboro.core.application.SourceImportRequest
import io.xoboro.core.application.SourceMutationAccess
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.HistoricalEvent
import io.xoboro.core.domain.HistoricalEventRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesRepository
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DurableCatalogFileLifecycleRequester(
  private val books: BookRepository,
  private val series: SeriesRepository,
  private val queue: DurableTaskQueue,
  private val taskIdFactory: () -> String,
  private val currentTimeMillis: () -> Long,
) : CatalogFileLifecycleRequester {
  override fun importBooks(
    books: List<BookImportCommand>,
    copyMode: SourceCopyMode,
  ): Int {
    val now = now()
    return books.count { command ->
      val target = series.findByIdOrNull(command.seriesId)
      if (target == null || target.deletedAtMillis != null) return@count false
      queue.enqueue(
        task =
          DurableTask(
            id = "IMPORT_BOOK_${taskIdFactory().requireIdentifier()}",
            type = ImportBookTaskHandler.TASK_TYPE,
            payloadJson =
              buildJsonObject {
                put(SOURCE_FILE_FIELD, command.sourceFile)
                put(SERIES_ID_FIELD, command.seriesId.value)
                put(COPY_MODE_FIELD, copyMode.name)
                command.upgradeBookId?.let { put(UPGRADE_BOOK_ID_FIELD, it.value) }
                command.destinationName?.let { put(DESTINATION_NAME_FIELD, it) }
              }.toString(),
            priority = TaskPriority.HIGHEST,
            groupId = command.seriesId.value,
            availableAtMillis = now,
          ),
        nowMillis = now,
      )
    }
  }

  override fun deleteBook(id: BookId): Boolean {
    val book = books.findByIdOrNull(id)?.takeIf { it.deletedAtMillis == null } ?: return false
    val now = now()
    return queue.enqueue(
      task =
        DurableTask(
          id = DeleteBookFileTaskHandler.taskId(id),
          type = DeleteBookFileTaskHandler.TASK_TYPE,
          payloadJson = buildJsonObject { put(BOOK_ID_FIELD, id.value) }.toString(),
          priority = TaskPriority.HIGHEST,
          groupId = book.seriesId.value,
          availableAtMillis = now,
        ),
      nowMillis = now,
    )
  }

  override fun deleteSeries(id: SeriesId): Boolean {
    val item = series.findByIdOrNull(id)?.takeIf { it.deletedAtMillis == null } ?: return false
    val now = now()
    return queue.enqueue(
      task =
        DurableTask(
          id = DeleteSeriesFileTaskHandler.taskId(id),
          type = DeleteSeriesFileTaskHandler.TASK_TYPE,
          payloadJson = buildJsonObject { put(SERIES_ID_FIELD, id.value) }.toString(),
          priority = TaskPriority.HIGHEST,
          groupId = item.id.value,
          availableAtMillis = now,
        ),
      nowMillis = now,
    )
  }

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Task emission timestamp must not be negative" } }

  private fun String.requireIdentifier(): String =
    trim().also { require(it.isNotEmpty()) { "Task ID factory must return a non-blank ID" } }

  companion object {
    internal const val BOOK_ID_FIELD = "bookId"
    internal const val SERIES_ID_FIELD = "seriesId"
    internal const val SOURCE_FILE_FIELD = "sourceFile"
    internal const val COPY_MODE_FIELD = "copyMode"
    internal const val UPGRADE_BOOK_ID_FIELD = "upgradeBookId"
    internal const val DESTINATION_NAME_FIELD = "destinationName"
  }
}

class CatalogSourceFileLifecycle(
  private val books: BookRepository,
  private val series: SeriesRepository,
  private val libraries: LibraryRepository,
  mutations: Collection<SourceMutationAccess>,
  private val scanEmitter: ScanLibraryTaskEmitter,
  private val history: HistoricalEventRepository? = null,
  private val historyIdFactory: () -> String = { UUID.randomUUID().toString() },
  private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
  private val mutationsBySourceId = mutations.associateBy(SourceMutationAccess::sourceId)

  init {
    require(mutations.none { it.sourceId.isBlank() }) { "Source mutation IDs must not be blank" }
    require(mutationsBySourceId.size == mutations.size) {
      "Source mutation IDs must be unique"
    }
  }

  fun deleteBook(id: BookId) {
    val book = books.findByIdOrNull(id) ?: return
    if (book.deletedAtMillis != null) return
    val library = libraries.findById(book.libraryId)
    if (mutation(library).delete(library.root.itemId, book.sourceItemId)) {
      record(
        type = "BookFileDeleted",
        bookId = book.id,
        seriesId = book.seriesId,
        properties =
          mapOf(
            "reason" to "File was deleted by user request",
            "name" to book.relativePath,
          ),
      )
    }
    scanEmitter.scanLibrary(library.id, priority = TaskPriority.HIGHEST)
  }

  fun deleteSeries(id: SeriesId) {
    val item = series.findByIdOrNull(id) ?: return
    if (item.deletedAtMillis != null) return
    val library = libraries.findById(item.libraryId)
    val source = mutation(library)
    books.findAllBySeriesId(item.id)
      .filter { it.deletedAtMillis == null }
      .forEach { book ->
        if (source.delete(library.root.itemId, book.sourceItemId)) {
          record(
            type = "BookFileDeleted",
            bookId = book.id,
            seriesId = book.seriesId,
            properties =
              mapOf(
                "reason" to "File was deleted by user request",
                "name" to book.relativePath,
              ),
          )
        }
      }
    scanEmitter.scanLibrary(library.id, priority = TaskPriority.HIGHEST)
  }

  fun importBook(command: BookImportCommand, copyMode: SourceCopyMode) {
    val target =
      series.findByIdOrNull(command.seriesId)
        ?.takeIf { it.deletedAtMillis == null }
        ?: return
    val library = libraries.findById(target.libraryId)
    val upgrade =
      command.upgradeBookId?.let { id ->
        requireNotNull(books.findByIdOrNull(id)) { "Upgrade book does not exist" }
          .also { require(it.seriesId == target.id) { "Upgrade book must belong to target series" } }
      }
    val source = mutation(library)
    val importedItemId =
      source.import(
        rootItemId = library.root.itemId,
        destinationParentItemId = target.sourceItemId,
        request =
          SourceImportRequest(
            sourceFile = command.sourceFile,
            destinationName =
              command.destinationName
                ?: upgrade?.relativePath?.substringAfterLast('/'),
            copyMode = copyMode,
            replaceExisting = upgrade != null,
          ),
      )
    if (upgrade != null && upgrade.sourceItemId != importedItemId) {
      source.delete(library.root.itemId, upgrade.sourceItemId)
    }
    record(
      type = "BookImported",
      seriesId = target.id,
      properties =
        mapOf(
          "name" to importedItemId,
          "source" to command.sourceFile,
          "upgrade" to if (upgrade == null) "No" else "Yes",
        ),
    )
    scanEmitter.scanLibrary(library.id, priority = TaskPriority.HIGHEST)
  }

  private fun record(
    type: String,
    bookId: BookId? = null,
    seriesId: SeriesId? = null,
    properties: Map<String, String>,
  ) {
    val repository = history ?: return
    val now = currentTimeMillis()
    require(now >= 0) { "Historical event timestamp must not be negative" }
    repository.insert(
      HistoricalEvent(
        id = historyIdFactory(),
        type = type,
        timestampMillis = now,
        bookId = bookId,
        seriesId = seriesId,
        properties = properties,
      ),
    )
  }

  private fun mutation(library: Library): SourceMutationAccess =
    mutationsBySourceId[library.root.sourceId]
      ?: throw IllegalArgumentException(
        "No source mutation access registered for: ${library.root.sourceId}",
      )
}

class DeleteBookFileTaskHandler(
  private val lifecycle: CatalogSourceFileLifecycle,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    lifecycle.deleteBook(BookId(task.requiredString(DurableCatalogFileLifecycleRequester.BOOK_ID_FIELD, json)))
  }

  companion object {
    const val TASK_TYPE = "DELETE_BOOK_FILE"

    fun taskId(id: BookId): String = "DELETE_BOOK_FILE_${id.value}"
  }
}

class DeleteSeriesFileTaskHandler(
  private val lifecycle: CatalogSourceFileLifecycle,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    lifecycle.deleteSeries(
      SeriesId(task.requiredString(DurableCatalogFileLifecycleRequester.SERIES_ID_FIELD, json)),
    )
  }

  companion object {
    const val TASK_TYPE = "DELETE_SERIES_FILE"

    fun taskId(id: SeriesId): String = "DELETE_SERIES_FILE_${id.value}"
  }
}

class ImportBookTaskHandler(
  private val lifecycle: CatalogSourceFileLifecycle,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val payload = json.parseToJsonElement(task.payloadJson).jsonObject
    val copyMode =
      payload[DurableCatalogFileLifecycleRequester.COPY_MODE_FIELD]
        ?.jsonPrimitive
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.let { runCatching { SourceCopyMode.valueOf(it) }.getOrNull() }
        ?: throw IllegalArgumentException("IMPORT_BOOK payload must contain a valid copyMode")
    lifecycle.importBook(
      command =
        BookImportCommand(
          sourceFile =
            task.requiredString(
              DurableCatalogFileLifecycleRequester.SOURCE_FILE_FIELD,
              json,
            ),
          seriesId =
            SeriesId(
              task.requiredString(
                DurableCatalogFileLifecycleRequester.SERIES_ID_FIELD,
                json,
              ),
            ),
          upgradeBookId =
            payload.optionalString(DurableCatalogFileLifecycleRequester.UPGRADE_BOOK_ID_FIELD)
              ?.let(::BookId),
          destinationName =
            payload.optionalString(DurableCatalogFileLifecycleRequester.DESTINATION_NAME_FIELD),
        ),
      copyMode = copyMode,
    )
  }

  companion object {
    const val TASK_TYPE = "IMPORT_BOOK"
  }
}

private fun DurableTask.requiredString(
  field: String,
  json: Json,
): String =
  json.parseToJsonElement(payloadJson).jsonObject.optionalString(field)
    ?: throw IllegalArgumentException("$type payload must contain a non-blank $field")

private fun kotlinx.serialization.json.JsonObject.optionalString(field: String): String? =
  get(field)
    ?.jsonPrimitive
    ?.takeIf { it.isString }
    ?.contentOrNull
    ?.takeIf(String::isNotBlank)
