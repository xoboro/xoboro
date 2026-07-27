package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.domain.BookId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AnalyzeBookTaskHandler(
  private val analyzeBook: (BookId) -> Unit,
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
    analyzeBook(BookId(bookId))
  }

  companion object {
    const val TASK_TYPE: String = "ANALYZE_BOOK"
    private const val BOOK_ID_FIELD = "bookId"
  }
}
