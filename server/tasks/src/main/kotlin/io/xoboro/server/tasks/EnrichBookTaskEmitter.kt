package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.application.enqueueOrRetry
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Queues optional full media work behind the reader-ready manifest stage. */
class EnrichBookTaskEmitter(
  private val books: BookRepository,
  private val queue: DurableTaskQueue,
  private val currentTimeMillis: () -> Long,
) {
  fun enrich(
    bookId: BookId,
    priority: Int = TaskPriority.LOW,
  ): Boolean {
    val book = books.findByIdOrNull(bookId)?.takeIf { it.deletedAtMillis == null } ?: return false
    val nowMillis = currentTimeMillis()
    require(nowMillis >= 0) { "Task emission timestamp must not be negative" }
    return queue.enqueueOrRetry(
      task =
        DurableTask(
          id = taskId(bookId),
          type = EnrichBookTaskHandler.TASK_TYPE,
          payloadJson =
            buildJsonObject {
              put(EnrichBookTaskHandler.BOOK_ID_FIELD, bookId.value)
            }.toString(),
          priority = priority,
          groupId = book.seriesId.value,
          availableAtMillis = nowMillis,
        ),
      nowMillis = nowMillis,
    )
  }

  companion object {
    fun taskId(bookId: BookId): String = "${EnrichBookTaskHandler.TASK_TYPE}_${bookId.value}"
  }
}
