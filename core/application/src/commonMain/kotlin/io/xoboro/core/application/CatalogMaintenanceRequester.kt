package io.xoboro.core.application

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId

interface CatalogMaintenanceRequester {
  fun analyzeBook(id: BookId): Boolean

  fun analyzeSeries(id: SeriesId): Int

  fun refreshBookMetadata(id: BookId): Boolean

  fun refreshSeriesMetadata(id: SeriesId): Int

  fun clearUnclaimedTasks(): Int
}
