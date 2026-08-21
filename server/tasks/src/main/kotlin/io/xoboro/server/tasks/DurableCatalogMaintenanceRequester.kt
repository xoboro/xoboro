package io.xoboro.server.tasks

import io.xoboro.core.application.CatalogMaintenanceRequester
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId

class DurableCatalogMaintenanceRequester(
  private val analysis: AnalyzeBookTaskEmitter,
  private val metadata: RefreshMetadataTaskEmitter,
  private val queue: DurableTaskQueue,
) : CatalogMaintenanceRequester {
  override fun analyzeBook(id: BookId): Boolean =
    analysis.analyzeBook(id, TaskPriority.HIGHEST)

  override fun analyzeSeries(id: SeriesId): Int =
    analysis.analyzeSeries(id, TaskPriority.HIGH)

  override fun refreshBookMetadata(id: BookId): Boolean =
    metadata.refreshBook(id, TaskPriority.HIGH)

  override fun refreshSeriesMetadata(id: SeriesId): Int =
    metadata.refreshSeries(id, TaskPriority.HIGH)

  override fun clearUnclaimedTasks(): Int = queue.clearUnclaimed()
}
