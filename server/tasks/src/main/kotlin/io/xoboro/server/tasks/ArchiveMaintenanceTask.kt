package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.SourceMutationAccess
import io.xoboro.core.application.SourceMutationResult
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaKind
import io.xoboro.server.media.ArchiveFormatDetector
import io.xoboro.server.media.RarToCbzConverter
import io.xoboro.server.media.SourceMediaAccess
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ArchiveMaintenanceTaskEmitter(
  private val books: BookRepository,
  private val queue: DurableTaskQueue,
  private val currentTimeMillis: () -> Long,
) {
  fun maintainLibrary(
    libraryId: LibraryId,
    repairExtensions: Boolean,
    convertToCbz: Boolean,
    priority: Int = TaskPriority.LOW,
  ): Int {
    if (!repairExtensions && !convertToCbz) return 0
    val now = currentTimeMillis()
    require(now >= 0) { "Archive maintenance timestamp must not be negative" }
    return books
      .findAllByLibraryId(libraryId)
      .asSequence()
      .filter { it.deletedAtMillis == null && it.mediaKind == MediaKind.COMIC_ARCHIVE }
      .count { book ->
        queue.enqueue(
          DurableTask(
            id = taskId(book.id),
            type = ArchiveMaintenanceTaskHandler.TASK_TYPE,
            payloadJson =
              buildJsonObject {
                put(ArchiveMaintenanceTaskHandler.BOOK_ID_FIELD, book.id.value)
                put(ArchiveMaintenanceTaskHandler.REPAIR_FIELD, repairExtensions)
                put(ArchiveMaintenanceTaskHandler.CONVERT_FIELD, convertToCbz)
              }.toString(),
            priority = priority,
            groupId = book.seriesId.value,
            availableAtMillis = now,
          ),
          now,
        )
      }
  }

  companion object {
    fun taskId(bookId: BookId): String = "MAINTAIN_ARCHIVE_${bookId.value}"
  }
}

class ArchiveMaintenanceTaskHandler(
  private val books: BookRepository,
  private val libraries: LibraryRepository,
  private val media: BookMediaRepository,
  accesses: Collection<SourceMediaAccess>,
  mutations: Collection<SourceMutationAccess>,
  private val converter: RarToCbzConverter,
  private val analysisEmitter: AnalyzeBookTaskEmitter,
  private val scanEmitter: ScanLibraryTaskEmitter,
  private val currentTimeMillis: () -> Long,
  private val detector: ArchiveFormatDetector = ArchiveFormatDetector(),
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE
  private val accessesBySourceId = accesses.associateBy(SourceMediaAccess::sourceId)
  private val mutationsBySourceId = mutations.associateBy(SourceMutationAccess::sourceId)

  init {
    require(accesses.none { it.sourceId.isBlank() }) { "Media source IDs must not be blank" }
    require(accessesBySourceId.size == accesses.size) { "Media source IDs must be unique" }
    require(mutations.none { it.sourceId.isBlank() }) { "Mutation source IDs must not be blank" }
    require(mutationsBySourceId.size == mutations.size) { "Mutation source IDs must be unique" }
  }

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val payload = json.parseToJsonElement(task.payloadJson).jsonObject
    val bookId =
      payload[BOOK_ID_FIELD]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?.let(::BookId)
        ?: error("Archive maintenance task must contain a bookId")
    val repair =
      payload[REPAIR_FIELD]?.jsonPrimitive?.booleanOrNull
        ?: error("Archive maintenance task must contain a repair flag")
    val convert =
      payload[CONVERT_FIELD]?.jsonPrimitive?.booleanOrNull
        ?: error("Archive maintenance task must contain a convert flag")
    if (!repair && !convert) return
    val book =
      books.findByIdOrNull(bookId)
        ?.takeIf { it.deletedAtMillis == null && it.mediaKind == MediaKind.COMIC_ARCHIVE }
        ?: return
    val library = libraries.findById(book.libraryId)
    val access =
      accessesBySourceId[library.root.sourceId]
        ?: error("No source media access registered for: ${library.root.sourceId}")
    val mutation =
      mutationsBySourceId[library.root.sourceId]
        ?: error("No source mutation access registered for: ${library.root.sourceId}")
    val outcome =
      access.materialize(library.root.itemId, book.sourceItemId).use { materialized ->
        val format = detector.detect(materialized.path) ?: return
        when {
          convert && format.isRar -> {
            val converted = Files.createTempFile("xoboro-rar-", ".cbz")
            try {
              converter.convert(materialized.path, converted)
              MaintenanceOutcome(
                result =
                  mutation.replaceWithFile(
                    rootItemId = library.root.itemId,
                    itemId = book.sourceItemId,
                    replacementFile = converted.toString(),
                    extension = "cbz",
                  ),
                contentChanged = true,
              )
            } finally {
              Files.deleteIfExists(converted)
            }
          }
          repair && book.extension() != format.canonicalExtension ->
            MaintenanceOutcome(
              result =
                mutation.renameExtension(
                  rootItemId = library.root.itemId,
                  itemId = book.sourceItemId,
                  extension = format.canonicalExtension,
                ),
              contentChanged = false,
            )
          else -> null
        }
      } ?: return
    val now = currentTimeMillis()
    require(now >= 0) { "Archive maintenance timestamp must not be negative" }
    books.update(book.withMutation(outcome, now))
    if (outcome.contentChanged) media.deleteByBookId(book.id)
    analysisEmitter.analyzeBook(book.id, TaskPriority.HIGH)
    scanEmitter.scanLibrary(book.libraryId, priority = TaskPriority.HIGH)
  }

  private fun Book.withMutation(
    outcome: MaintenanceOutcome,
    now: Long,
  ): Book {
    val result = outcome.result
    val leaf = result.name
    val newName = leaf.substringBeforeLast('.', missingDelimiterValue = leaf)
    return copy(
      name = newName,
      relativePath = result.relativePath,
      sourceItemId = result.itemId,
      sourceIdentity = result.identity,
      fileSize = result.size,
      fileModifiedAtMillis = result.modifiedAtMillis,
      fileHash = if (outcome.contentChanged) "" else fileHash,
      fileHashKoreader = if (outcome.contentChanged) "" else fileHashKoreader,
      updatedAtMillis = maxOf(now, createdAtMillis),
    )
  }

  private fun Book.extension(): String =
    relativePath.substringAfterLast('.', missingDelimiterValue = "").lowercase()

  private data class MaintenanceOutcome(
    val result: SourceMutationResult,
    val contentChanged: Boolean,
  )

  companion object {
    const val TASK_TYPE = "MAINTAIN_ARCHIVE"
    internal const val BOOK_ID_FIELD = "bookId"
    internal const val REPAIR_FIELD = "repairExtensions"
    internal const val CONVERT_FIELD = "convertToCbz"
  }
}
