package io.xoboro.server.persistence

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.HistoricalEvent
import io.xoboro.core.domain.HistoricalEventPage
import io.xoboro.core.domain.HistoricalEventPageRequest
import io.xoboro.core.domain.HistoricalEventRepository
import io.xoboro.core.domain.HistoricalEventSortField
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SortDirection
import org.jooq.Record

class JooqHistoricalEventRepository(
  private val database: XoboroDatabase,
) : HistoricalEventRepository {
  override fun findAll(request: HistoricalEventPageRequest): HistoricalEventPage {
    val total =
      database.dsl
        .fetchOne("SELECT count(*) AS event_count FROM historical_event")
        ?.get("event_count", Long::class.java)
        ?: 0L
    val order =
      "${request.sortField.columnName} ${request.direction.sql}, " +
        "timestamp_ms ${request.direction.sql}, id ${request.direction.sql}"
    val pagination = if (request.unpaged) "" else "LIMIT ? OFFSET ?"
    val bindings: Array<Any?> =
      if (request.unpaged) {
        emptyArray<Any?>()
      } else {
        arrayOf<Any?>(request.size, request.page.toLong() * request.size)
      }
    val records =
      database.dsl.fetch(
        "$SELECT_EVENT ORDER BY $order $pagination",
        *bindings,
      )
    val properties =
      records
        .map { it.requiredString("id") }
        .chunked(PROPERTY_QUERY_CHUNK)
        .flatMap { ids ->
          val placeholders = List(ids.size) { "?" }.joinToString()
          database.dsl.fetch(
            """
            SELECT event_id, key, value
            FROM historical_event_property
            WHERE event_id IN ($placeholders)
            ORDER BY event_id, key
            """.trimIndent(),
            *ids.toTypedArray(),
          )
        }.groupBy(
          keySelector = { it.requiredString("event_id") },
          valueTransform = { it.requiredString("key") to it.requiredString("value") },
        ).mapValues { (_, values) -> values.toMap() }
    return HistoricalEventPage(
      content = records.map { it.toHistoricalEvent(properties[it.requiredString("id")].orEmpty()) },
      totalElements = total,
      request = request,
    )
  }

  /**
   * Property rows go with their event by `ON DELETE CASCADE`, so this deletes only the parent. An
   * orphaned property row would be reattached to whatever event later reused the id.
   */
  override fun deleteOlderThan(timestampMillis: Long): Int =
    database.dsl.execute(
      "DELETE FROM historical_event WHERE timestamp_ms < ?",
      timestampMillis,
    )

  override fun insert(event: HistoricalEvent) {
    database.transaction { transaction ->
      transaction.execute(
        """
        INSERT INTO historical_event (
          id, type, timestamp_ms, book_id, series_id
        ) VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
        event.id,
        event.type,
        event.timestampMillis,
        event.bookId?.value,
        event.seriesId?.value,
      )
      event.properties.toSortedMap().forEach { (key, value) ->
        transaction.execute(
          """
          INSERT INTO historical_event_property (event_id, key, value)
          VALUES (?, ?, ?)
          """.trimIndent(),
          event.id,
          key,
          value,
        )
      }
    }
  }

  private fun Record.toHistoricalEvent(properties: Map<String, String>): HistoricalEvent =
    HistoricalEvent(
      id = requiredString("id"),
      type = requiredString("type"),
      timestampMillis = requiredLongText("timestamp_ms_64"),
      bookId = get("book_id", String::class.java)?.let(::BookId),
      seriesId = get("series_id", String::class.java)?.let(::SeriesId),
      properties = properties,
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) {
      "Database field '$field' must not be null"
    }

  private fun Record.requiredLongText(field: String): Long =
    requiredString(field).toLong()

  private val HistoricalEventSortField.columnName: String
    get() =
      when (this) {
        HistoricalEventSortField.TYPE -> "type"
        HistoricalEventSortField.BOOK_ID -> "book_id"
        HistoricalEventSortField.SERIES_ID -> "series_id"
        HistoricalEventSortField.TIMESTAMP -> "timestamp_ms"
      }

  private val SortDirection.sql: String
    get() =
      when (this) {
        SortDirection.ASCENDING -> "ASC"
        SortDirection.DESCENDING -> "DESC"
      }

  companion object {
    private const val PROPERTY_QUERY_CHUNK = 500
    private const val SELECT_EVENT =
      """
      SELECT historical_event.*,
        CAST(timestamp_ms AS TEXT) AS timestamp_ms_64
      FROM historical_event
      """
  }
}
