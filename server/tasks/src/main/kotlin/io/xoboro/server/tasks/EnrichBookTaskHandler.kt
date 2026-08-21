package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.domain.BookId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Runs full analysis and optional enrichment after a readable manifest has been persisted. */
class EnrichBookTaskHandler(
  private val enrichBook: (BookId) -> Unit,
  private val afterEnrich: (BookId) -> Unit = {},
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
        ?: throw IllegalArgumentException("$TASK_TYPE payload must contain a non-blank bookId")
    BookId(bookId).also { id ->
      enrichBook(id)
      afterEnrich(id)
    }
  }

  companion object {
    const val TASK_TYPE: String = "ENRICH_BOOK"
    internal const val BOOK_ID_FIELD: String = "bookId"
  }
}
