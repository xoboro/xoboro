package io.xoboro.server.tasks

import io.xoboro.core.application.DurableTask

interface TaskHandler {
  val taskType: String

  fun handle(task: DurableTask)
}

class UnknownTaskTypeException(
  taskType: String,
) : IllegalArgumentException("No handler registered for task type: $taskType")
