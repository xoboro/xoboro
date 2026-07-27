package io.xoboro.server.persistence

import io.xoboro.core.application.ReadListImportBookMatch
import io.xoboro.core.application.ReadListImportBookMatches
import io.xoboro.core.application.ReadListImportBookRequest
import io.xoboro.core.application.ReadListImportMatcher
import io.xoboro.core.application.ReadListImportSeriesMatch

class JooqReadListImportMatcher(
  private val database: XoboroDatabase,
) : ReadListImportMatcher {
  override fun match(
    requests: List<ReadListImportBookRequest>,
  ): List<ReadListImportBookMatches> {
    val indexedAliases =
      requests.flatMapIndexed { index, request ->
        request.series.map { series -> AliasRequest(index, series, request.number) }
      }
    val rows =
      indexedAliases.chunked(VALUES_CHUNK_SIZE).flatMap { chunk ->
        val values = List(chunk.size) { "(?, ?, ?)" }.joinToString()
        val bindings: List<Any?> =
          chunk.flatMap { request ->
            listOf<Any?>(request.index, request.series, request.number)
          }
        database.dsl.fetch(
          """
          WITH request(request_index, series, number) AS (VALUES $values)
          SELECT
            request.request_index,
            sm.series_id,
            sm.title AS series_title,
            MIN(bm.release_date) OVER (PARTITION BY sm.series_id) AS release_date,
            bm.book_id,
            bm.number AS book_number,
            bm.title AS book_title,
            bm.number_sort
          FROM request
          JOIN series_metadata sm
            ON sm.title = request.series COLLATE NOCASE
          JOIN series s
            ON s.id = sm.series_id AND s.deleted_at_ms IS NULL
          JOIN book b
            ON b.series_id = sm.series_id AND b.deleted_at_ms IS NULL
          JOIN book_metadata bm
            ON bm.book_id = b.id
           AND ltrim(bm.number, '0') = ltrim(request.number, '0') COLLATE NOCASE
          ORDER BY request.request_index, sm.title COLLATE NOCASE, bm.number_sort, bm.book_id
          """.trimIndent(),
          *bindings.toTypedArray(),
        )
      }
    val byRequest =
      rows.groupBy { requireNotNull(it.get("request_index", Int::class.java)) }
    return requests.mapIndexed { index, request ->
      val seriesMatches =
        byRequest[index]
          .orEmpty()
          .groupBy { requireNotNull(it.get("series_id", String::class.java)) }
          .values
          .map { records ->
            val first = records.first()
            ReadListImportSeriesMatch(
              seriesId = requireNotNull(first.get("series_id", String::class.java)),
              title = requireNotNull(first.get("series_title", String::class.java)),
              releaseDate = first.get("release_date", String::class.java),
              books =
                records
                  .distinctBy { requireNotNull(it.get("book_id", String::class.java)) }
                  .map { record ->
                    ReadListImportBookMatch(
                      bookId = requireNotNull(record.get("book_id", String::class.java)),
                      number = requireNotNull(record.get("book_number", String::class.java)),
                      title = requireNotNull(record.get("book_title", String::class.java)),
                    )
                  },
            )
          }
      ReadListImportBookMatches(request, seriesMatches)
    }
  }

  private data class AliasRequest(
    val index: Int,
    val series: String,
    val number: String,
  )

  companion object {
    private const val VALUES_CHUNK_SIZE = 250
  }
}
