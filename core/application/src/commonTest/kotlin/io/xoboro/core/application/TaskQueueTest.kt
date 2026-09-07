package io.xoboro.core.application

import kotlin.test.Test
import kotlin.test.assertFailsWith

class TaskQueueTest {
  @Test
  fun `task invariants reject invalid queue data`() {
    assertFailsWith<IllegalArgumentException> {
      taskFixture(id = "")
    }
    assertFailsWith<IllegalArgumentException> {
      taskFixture(priority = TaskPriority.HIGHEST + 1)
    }
    assertFailsWith<IllegalArgumentException> {
      taskFixture(maxAttempts = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      taskFixture(exclusionKey = "")
    }
  }

  private fun taskFixture(
    id: String = "task-1",
    priority: Int = TaskPriority.DEFAULT,
    maxAttempts: Int = 3,
    exclusionKey: String? = null,
  ): DurableTask =
    DurableTask(
      id = id,
      type = "SYNTHETIC",
      payloadJson = "{}",
      priority = priority,
      exclusionKey = exclusionKey,
      availableAtMillis = 1L,
      maxAttempts = maxAttempts,
    )
}
