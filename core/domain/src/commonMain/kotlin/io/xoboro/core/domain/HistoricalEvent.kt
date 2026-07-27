package io.xoboro.core.domain

enum class HistoricalEventSortField {
  TYPE,
  BOOK_ID,
  SERIES_ID,
  TIMESTAMP,
}

data class HistoricalEvent(
  val id: String,
  val type: String,
  val timestampMillis: Long,
  val bookId: BookId? = null,
  val seriesId: SeriesId? = null,
  val properties: Map<String, String> = emptyMap(),
) {
  init {
    require(id.isNotBlank()) { "Historical event ID must not be blank" }
    require(type.isNotBlank()) { "Historical event type must not be blank" }
    require(timestampMillis >= 0) { "Historical event timestamp must not be negative" }
    require(properties.keys.none(String::isBlank)) {
      "Historical event property keys must not be blank"
    }
  }
}

data class HistoricalEventPageRequest(
  val page: Int = 0,
  val size: Int = 20,
  val sortField: HistoricalEventSortField = HistoricalEventSortField.TIMESTAMP,
  val direction: SortDirection = SortDirection.DESCENDING,
  val unpaged: Boolean = false,
) {
  init {
    require(page >= 0) { "History page must not be negative" }
    require(size in 1..10_000) { "History page size must be between 1 and 10000" }
  }
}

data class HistoricalEventPage(
  val content: List<HistoricalEvent>,
  val totalElements: Long,
  val request: HistoricalEventPageRequest,
) {
  init {
    require(totalElements >= 0) { "History total must not be negative" }
  }
}

interface HistoricalEventRepository {
  fun findAll(request: HistoricalEventPageRequest): HistoricalEventPage

  fun insert(event: HistoricalEvent)
}
