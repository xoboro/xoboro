package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.domain.BookId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalyzeBookTaskHandlerTest {
  @Test
  fun `decodes the reconciliation payload and invokes analysis`() {
    var received: BookId? = null
    val handler = AnalyzeBookTaskHandler(analyzeBook = { received = it })

    handler.handle(task("""{"bookId":"book-1"}"""))

    assertEquals(BookId("book-1"), received)
  }

  @Test
  fun `rejects malformed missing and wrong task payloads`() {
    val handler = AnalyzeBookTaskHandler(analyzeBook = {})

    listOf("not-json", "{}", """{"bookId":""}""", """{"bookId":1}""").forEach { payload ->
      assertFailsWith<IllegalArgumentException> {
        handler.handle(task(payload))
      }
    }
    assertFailsWith<IllegalArgumentException> {
      handler.handle(task("{}", type = "OTHER"))
    }
  }

  private fun task(
    payload: String,
    type: String = AnalyzeBookTaskHandler.TASK_TYPE,
  ): DurableTask =
    DurableTask(
      id = "task-1",
      type = type,
      payloadJson = payload,
      availableAtMillis = 1,
    )
}
