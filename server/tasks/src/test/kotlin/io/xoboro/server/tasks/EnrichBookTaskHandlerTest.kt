package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask
import io.xoboro.core.domain.BookId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnrichBookTaskHandlerTest {
  @Test
  fun `runs full enrichment before its completion effects`() {
    val received = mutableListOf<Pair<String, BookId>>()
    val handler =
      EnrichBookTaskHandler(
        enrichBook = { received += "enrich" to it },
        afterEnrich = { received += "after" to it },
      )

    handler.handle(task("""{"bookId":"book-1"}"""))

    assertEquals(
      listOf("enrich" to BookId("book-1"), "after" to BookId("book-1")),
      received,
    )
  }

  @Test
  fun `rejects malformed missing and wrong task payloads`() {
    val handler = EnrichBookTaskHandler(enrichBook = {})

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
    type: String = EnrichBookTaskHandler.TASK_TYPE,
  ): DurableTask =
    DurableTask(
      id = "task-1",
      type = type,
      payloadJson = payload,
      availableAtMillis = 1,
    )
}
