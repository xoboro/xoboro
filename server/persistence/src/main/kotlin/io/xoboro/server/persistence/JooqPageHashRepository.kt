package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.MediaItemId
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.PageHashMatch
import io.xoboro.core.domain.UnknownPageHash
import org.jooq.Record

class JooqPageHashRepository(
  private val database: XoboroDatabase,
) : PageHashRepository {
  override fun findKnownOrNull(hash: String): KnownPageHash? =
    database.dsl
      .fetchOne(
        """
        SELECT known.*,
          CAST(known.file_size AS TEXT) AS file_size_64,
          CAST(known.created_at_ms AS TEXT) AS created_at_ms_64,
          CAST(known.updated_at_ms AS TEXT) AS updated_at_ms_64,
          (
            SELECT count(*)
            FROM book_page page
            WHERE page.file_hash = known.hash
          ) AS match_count
        FROM page_hash_known known
        WHERE known.hash = ?
        """.trimIndent(),
        hash,
      )?.toKnown()

  override fun findKnown(
    actions: Set<PageHashAction>,
    page: CatalogPageRequest,
  ): CatalogPage<KnownPageHash> {
    val bindings = mutableListOf<Any?>()
    val where =
      if (actions.isEmpty()) {
        "1 = 1"
      } else {
        bindings.addAll(actions.map(PageHashAction::name))
        "known.action IN (${actions.placeholders()})"
      }
    val total =
      database.dsl
        .fetchOne(
          "SELECT count(*) AS item_count FROM page_hash_known known WHERE $where",
          *bindings.toTypedArray(),
        ).requiredLong("item_count")
    val queryBindings = bindings.toMutableList()
    val records =
      database.dsl.fetch(
        """
        SELECT known.*,
          CAST(known.file_size AS TEXT) AS file_size_64,
          CAST(known.created_at_ms AS TEXT) AS created_at_ms_64,
          CAST(known.updated_at_ms AS TEXT) AS updated_at_ms_64,
          (
            SELECT count(*)
            FROM book_page matched_page
            WHERE matched_page.file_hash = known.hash
          ) AS match_count
        FROM page_hash_known known
        WHERE $where
        ORDER BY ${knownOrder(page.sorts)}
        ${page.limitClause(queryBindings)}
        """.trimIndent(),
        *queryBindings.toTypedArray(),
      )
    return page(records.map { it.toKnown() }, page, total)
  }

  override fun findUnknown(page: CatalogPageRequest): CatalogPage<UnknownPageHash> {
    val grouped =
      """
      SELECT matched.file_hash, max(matched.file_size) AS file_size, count(*) AS match_count
      FROM book_page matched
      WHERE matched.file_hash <> ''
        AND NOT EXISTS (
          SELECT 1
          FROM page_hash_known known
          WHERE known.hash = matched.file_hash
        )
      GROUP BY matched.file_hash
      HAVING count(*) > 1
      """.trimIndent()
    val total =
      database.dsl
        .fetchOne("SELECT count(*) AS item_count FROM ($grouped) unknown_hash")
        .requiredLong("item_count")
    val bindings = mutableListOf<Any?>()
    val records =
      database.dsl.fetch(
        """
        SELECT unknown_hash.*,
          CAST(unknown_hash.file_size AS TEXT) AS file_size_64
        FROM ($grouped) unknown_hash
        ORDER BY ${unknownOrder(page.sorts)}
        ${page.limitClause(bindings)}
        """.trimIndent(),
        *bindings.toTypedArray(),
      )
    return page(
      records.map { record ->
        UnknownPageHash(
          hash = record.requiredString("file_hash"),
          size = record.optionalLongText("file_size_64"),
          matchCount = record.requiredInt("match_count"),
        )
      },
      page,
      total,
    )
  }

  override fun findMatches(
    hash: String,
    page: CatalogPageRequest,
  ): CatalogPage<PageHashMatch> {
    require(hash.isNotBlank()) { "Page hash must not be blank" }
    val total =
      database.dsl
        .fetchOne(
          "SELECT count(*) AS item_count FROM book_page WHERE file_hash = ?",
          hash,
        ).requiredLong("item_count")
    val bindings = mutableListOf<Any?>(hash)
    val records =
      database.dsl.fetch(
        """
        SELECT matched.book_id, book.source_item_id, matched.number, matched.file_name,
          CAST(matched.file_size AS TEXT) AS file_size_64, matched.media_type
        FROM book_page matched
        JOIN book ON book.id = matched.book_id
        WHERE matched.file_hash = ?
        ORDER BY ${matchOrder(page.sorts)}
        ${page.limitClause(bindings)}
        """.trimIndent(),
        *bindings.toTypedArray(),
      )
    return page(
      records.map { record ->
        PageHashMatch(
          mediaItemId = MediaItemId(record.requiredString("book_id")),
          sourceItemId = record.requiredString("source_item_id"),
          pageNumber = record.requiredInt("number"),
          fileName = record.requiredString("file_name"),
          fileSize = record.optionalLongText("file_size_64") ?: 0,
          mediaType = record.requiredString("media_type"),
        )
      },
      page,
      total,
    )
  }

  override fun upsert(known: KnownPageHash) {
    database.dsl.execute(
      """
      INSERT INTO page_hash_known (
        hash, file_size, action, delete_count, created_at_ms, updated_at_ms
      ) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(hash) DO UPDATE SET
        file_size = excluded.file_size,
        action = excluded.action,
        delete_count = excluded.delete_count,
        updated_at_ms = excluded.updated_at_ms
      """.trimIndent(),
      known.hash,
      known.size,
      known.action.name,
      known.deleteCount,
      known.createdAtMillis,
      known.updatedAtMillis,
    )
  }

  override fun incrementDeleteCount(
    hash: String,
    count: Int,
  ) {
    require(hash.isNotBlank()) { "Page hash must not be blank" }
    require(count > 0) { "Page hash delete increment must be positive" }
    database.dsl.execute(
      """
      UPDATE page_hash_known
      SET delete_count = delete_count + ?
      WHERE hash = ?
      """.trimIndent(),
      count,
      hash,
    )
  }

  private fun knownOrder(sorts: List<CatalogSort>): String =
    order(
      sorts,
      mapOf(
        "hash" to "known.hash",
        "matchCount" to "match_count",
        "deleteCount" to "known.delete_count",
        "deleteSize" to "(coalesce(known.file_size, 0) * known.delete_count)",
        "fileSize" to "known.file_size",
        "createdDate" to "known.created_at_ms",
        "lastModifiedDate" to "known.updated_at_ms",
      ),
      "known.created_at_ms ASC, known.hash ASC",
    )

  private fun unknownOrder(sorts: List<CatalogSort>): String =
    order(
      sorts,
      mapOf(
        "hash" to "unknown_hash.file_hash",
        "fileSize" to "unknown_hash.file_size",
        "matchCount" to "unknown_hash.match_count",
        "totalSize" to "(coalesce(unknown_hash.file_size, 0) * unknown_hash.match_count)",
      ),
      "unknown_hash.match_count DESC, unknown_hash.file_hash ASC",
    )

  private fun matchOrder(sorts: List<CatalogSort>): String =
    order(
      sorts,
      mapOf(
        "url" to "book.source_item_id COLLATE NOCASE",
        "bookId" to "matched.book_id",
        "pageNumber" to "matched.number",
        "fileSize" to "matched.file_size",
      ),
      "book.source_item_id COLLATE NOCASE ASC, matched.number ASC, matched.book_id ASC",
    )

  private fun order(
    sorts: List<CatalogSort>,
    fields: Map<String, String>,
    fallback: String,
  ): String =
    if (sorts.isEmpty()) {
      fallback
    } else {
      sorts.joinToString(", ") { sort ->
        val field =
          fields[sort.property]
            ?: throw IllegalArgumentException("Unsupported page hash sort: ${sort.property}")
        val direction =
          when (sort.direction) {
            CatalogSortDirection.ASC -> "ASC"
            CatalogSortDirection.DESC -> "DESC"
          }
        "$field $direction"
      }
    }

  private fun Record.toKnown(): KnownPageHash =
    KnownPageHash(
      hash = requiredString("hash"),
      size = optionalLongText("file_size_64"),
      action = PageHashAction.valueOf(requiredString("action")),
      deleteCount = requiredInt("delete_count"),
      matchCount = requiredInt("match_count"),
      createdAtMillis = requiredString("created_at_ms_64").toLong(),
      updatedAtMillis = requiredString("updated_at_ms_64").toLong(),
    )

  private fun <T> page(
    content: List<T>,
    request: CatalogPageRequest,
    total: Long,
  ): CatalogPage<T> =
    CatalogPage(
      content = content,
      page = if (request.unpaged) 0 else request.page,
      size = if (request.unpaged) content.size.coerceAtLeast(1) else request.size,
      totalElements = total,
      unpaged = request.unpaged,
    )

  private fun CatalogPageRequest.limitClause(bindings: MutableList<Any?>): String {
    if (unpaged) return ""
    bindings += size
    bindings += page.toLong() * size
    return "LIMIT ? OFFSET ?"
  }

  private fun Collection<*>.placeholders(): String = joinToString(",") { "?" }

  private fun Record?.requiredLong(field: String): Long =
    (requireNotNull(requireNotNull(this).get(field)) as Number).toLong()

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredInt(field: String): Int =
    (requireNotNull(get(field)) as Number).toInt()

  private fun Record.optionalLongText(field: String): Long? =
    get(field, String::class.java)?.toLong()
}
