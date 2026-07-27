package io.xoboro.server.tasks

import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CompatibilityMaintenanceRequester
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.application.SourceMutationAccess
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.PageHashMatch
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DurableCompatibilityMaintenanceRequester(
  private val books: BookRepository,
  private val queue: DurableTaskQueue,
  private val taskIdFactory: () -> String,
  private val currentTimeMillis: () -> Long,
) : CompatibilityMaintenanceRequester {
  override fun regenerateBookArtwork(forBiggerResultOnly: Boolean): Int =
    if (
      enqueue(
        type = FindBookArtworkTaskHandler.TASK_TYPE,
        payload =
          buildJsonObject {
            put(BIGGER_ONLY_FIELD, forBiggerResultOnly)
          }.toString(),
        priority = TaskPriority.LOWEST,
        groupId = BULK_ARTWORK_GROUP,
      )
    ) {
      1
    } else {
      0
    }

  override fun deleteDuplicatePages(
    hash: String,
    matches: List<PageHashMatch>,
  ): Int {
    require(hash.isNotBlank()) { "Page hash must not be blank" }
    return matches
      .groupBy(PageHashMatch::mediaItemId)
      .count { (bookId, bookMatches) ->
        val book = books.findByIdOrNull(BookId(bookId.value)) ?: return@count false
        enqueue(
          type = RemoveDuplicatePagesTaskHandler.TASK_TYPE,
          payload =
            buildJsonObject {
              put(BOOK_ID_FIELD, book.id.value)
              put(PAGE_HASH_FIELD, hash)
              put(
                ENTRY_NAMES_FIELD,
                buildJsonArray {
                  bookMatches
                    .map(PageHashMatch::fileName)
                    .distinct()
                    .sorted()
                    .forEach { add(JsonPrimitive(it)) }
                },
              )
            }.toString(),
          priority = TaskPriority.HIGHEST,
          groupId = book.seriesId.value,
        )
      }
  }

  private fun enqueue(
    type: String,
    payload: String,
    priority: Int,
    groupId: String,
  ): Boolean {
    val now = currentTimeMillis()
    require(now >= 0) { "Compatibility maintenance timestamp must not be negative" }
    val suffix = taskIdFactory().trim()
    require(suffix.isNotEmpty()) { "Compatibility maintenance task ID must not be blank" }
    return queue.enqueue(
      DurableTask(
        id = "${type}_$suffix",
        type = type,
        payloadJson = payload,
        priority = priority,
        groupId = groupId,
        availableAtMillis = now,
      ),
      now,
    )
  }

  companion object {
    const val GENERATED_ARTWORK_MAXIMUM_DIMENSION = 1_600
    internal const val BOOK_ID_FIELD = "bookId"
    internal const val ENTRY_NAMES_FIELD = "entryNames"
    internal const val PAGE_HASH_FIELD = "pageHash"
    internal const val BIGGER_ONLY_FIELD = "forBiggerResultOnly"
    internal const val BULK_ARTWORK_GROUP = "BULK_BOOK_ARTWORK"
  }
}

class FindBookArtworkTaskHandler(
  private val catalog: CatalogReadRepository,
  private val artwork: ArtworkLifecycle,
  private val queue: DurableTaskQueue,
  private val taskIdFactory: () -> String,
  private val currentTimeMillis: () -> Long,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val biggerOnly =
      json.parseToJsonElement(task.payloadJson).jsonObject[
        DurableCompatibilityMaintenanceRequester.BIGGER_ONLY_FIELD
      ]?.jsonPrimitive
        ?.contentOrNull
        ?.toBooleanStrictOrNull()
        ?: false
    var pageNumber = 0
    while (true) {
      val page =
        catalog.findBooks(
          query = BookCatalogQuery(deleted = false),
          access = CatalogAccess(),
          page = CatalogPageRequest(page = pageNumber, size = DISCOVERY_PAGE_SIZE),
        )
      page.content.forEach { candidate ->
        val owner = ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, candidate.book.id.value)
        val generated = artwork.findAll(owner).firstOrNull { it.type == ArtworkType.GENERATED }
        if (
          !biggerOnly ||
          generated == null ||
          maxOf(generated.width, generated.height) <
          DurableCompatibilityMaintenanceRequester.GENERATED_ARTWORK_MAXIMUM_DIMENSION
        ) {
          val now = currentTimeMillis()
          queue.enqueue(
            DurableTask(
              id = "${GenerateBookArtworkTaskHandler.TASK_TYPE}_${taskIdFactory()}",
              type = GenerateBookArtworkTaskHandler.TASK_TYPE,
              payloadJson =
                buildJsonObject {
                  put(
                    DurableCompatibilityMaintenanceRequester.BOOK_ID_FIELD,
                    candidate.book.id.value,
                  )
                }.toString(),
              priority = TaskPriority.LOWEST,
              groupId = candidate.book.seriesId.value,
              availableAtMillis = now,
            ),
            now,
          )
        }
      }
      if (page.content.size < DISCOVERY_PAGE_SIZE) break
      pageNumber += 1
    }
  }

  companion object {
    const val TASK_TYPE = "FIND_BOOK_ARTWORK"
    private const val DISCOVERY_PAGE_SIZE = 500
  }
}

class GenerateBookArtworkTaskHandler(
  private val content: BookContentAccess,
  private val artwork: ArtworkLifecycle,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val bookId = BookId(task.requiredBookId(json))
    val opened =
      requireNotNull(
        content.openPage(
          bookId,
          pageNumber = 1,
          request =
            PageImageRequest(
              format = PageImageFormat.JPEG,
              maximumDimension =
                DurableCompatibilityMaintenanceRequester.GENERATED_ARTWORK_MAXIMUM_DIMENSION,
            ),
        ),
      ) {
        "Book does not have a readable first page: ${bookId.value}"
      }
    val bytes =
      try {
        opened.readBounded(ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES)
      } finally {
        opened.close()
      }
    artwork.replaceGenerated(
      ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, bookId.value),
      bytes,
    )
  }

  companion object {
    const val TASK_TYPE = "GENERATE_BOOK_ARTWORK"
  }
}

class RemoveDuplicatePagesTaskHandler(
  private val books: BookRepository,
  private val libraries: LibraryRepository,
  private val pageHashes: PageHashRepository,
  mutations: Collection<SourceMutationAccess>,
  private val scanEmitter: ScanLibraryTaskEmitter,
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE
  private val mutationsBySourceId = mutations.associateBy(SourceMutationAccess::sourceId)

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val payload = json.parseToJsonElement(task.payloadJson).jsonObject
    val bookId =
      BookId(
        payload[DurableCompatibilityMaintenanceRequester.BOOK_ID_FIELD]
          ?.jsonPrimitive
          ?.contentOrNull
          ?.takeIf(String::isNotBlank)
          ?: error("Duplicate-page task must contain a bookId"),
      )
    val entries =
      payload[DurableCompatibilityMaintenanceRequester.ENTRY_NAMES_FIELD]
        ?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
        ?.toSet()
        .orEmpty()
    require(entries.isNotEmpty()) { "Duplicate-page task must contain entry names" }
    val pageHash =
      payload[DurableCompatibilityMaintenanceRequester.PAGE_HASH_FIELD]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: error("Duplicate-page task must contain a pageHash")
    val book = books.findByIdOrNull(bookId) ?: return
    val library = libraries.findById(book.libraryId)
    val mutation =
      mutationsBySourceId[library.root.sourceId]
        ?: error("No source mutation access registered for: ${library.root.sourceId}")
    val removed = mutation.removeArchiveEntries(library.root.itemId, book.sourceItemId, entries)
    if (removed > 0) {
      pageHashes.incrementDeleteCount(pageHash, removed)
      scanEmitter.scanLibrary(library.id, priority = TaskPriority.HIGHEST)
    }
  }

  companion object {
    const val TASK_TYPE = "REMOVE_DUPLICATE_PAGES"
  }
}

private fun DurableTask.requiredBookId(json: Json): String =
  json.parseToJsonElement(payloadJson).jsonObject[
    DurableCompatibilityMaintenanceRequester.BOOK_ID_FIELD
  ]?.jsonPrimitive
    ?.contentOrNull
    ?.takeIf(String::isNotBlank)
    ?: error("$type task must contain a bookId")

private fun io.xoboro.core.application.MediaContentStream.readBounded(maximumBytes: Int): ByteArray {
  val output = ByteArrayOutputStream()
  val buffer = ByteArray(16 * 1_024)
  var total = 0
  while (true) {
    val read = read(buffer)
    if (read < 0) break
    if (read == 0) continue
    total += read
    require(total <= maximumBytes) { "Generated artwork exceeds the size limit" }
    output.write(buffer, 0, read)
  }
  return output.toByteArray()
}
