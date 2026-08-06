package io.xoboro.server.persistence

import io.xoboro.core.domain.CatalogChange
import io.xoboro.core.domain.CatalogChangeEntityKind
import io.xoboro.core.domain.CatalogChangeMutation
import io.xoboro.core.domain.CatalogChangePage
import io.xoboro.core.domain.CatalogChangeRepository
import io.xoboro.core.domain.LibraryId
import org.jooq.DSLContext
import org.jooq.Record

/**
 * Reads and sweeps the durable catalogue change feed.
 *
 * Appending is deliberately not here. A change row has to be written in the same transaction as the
 * mutation it describes, and that transaction belongs to whoever performed the mutation - so the
 * append lives with them (see `appendCatalogChanges`). A repository method taking its own connection
 * would be a second transaction, and a crash between the two loses a change while reporting success.
 */
class JooqCatalogChangeRepository(
  private val database: XoboroDatabase,
) : CatalogChangeRepository {
  override fun findAfter(
    afterSequence: Long,
    libraryIds: Set<LibraryId>?,
    limit: Int,
  ): CatalogChangePage {
    require(afterSequence >= 0) { "Catalog change cursor must not be negative" }
    require(limit in 1..10_000) { "Catalog change page size must be between 1 and 10000" }
    val floor = database.dsl.readFloor()
    // Read the floor before the rows, not after. The other order can raise the floor past the cursor
    // between the two reads and still answer with rows, which reports a complete page over a gap.
    if (afterSequence < floor) {
      return CatalogChangePage(
        changes = emptyList(),
        nextCursor = afterSequence,
        floorSequence = floor,
        resyncRequired = true,
      )
    }
    if (libraryIds != null && libraryIds.isEmpty()) {
      // No visible library is not an error and not a resync: there is genuinely nothing to report, and
      // the cursor must survive so a later grant does not replay the whole feed. Distinct from null,
      // which is "no restriction" - answering everything here would hand the catalogue's history to a
      // reader granted none of it.
      return CatalogChangePage(emptyList(), afterSequence, floor, resyncRequired = false)
    }
    val scope = libraryIds?.let { " AND library_id IN (${it.placeholders()})" }.orEmpty()
    val changes =
      database.dsl
        .fetch(
          """
          SELECT
            CAST(sequence AS TEXT) AS sequence_64,
            entity_kind,
            entity_id,
            mutation,
            library_id,
            CAST(occurred_at_ms AS TEXT) AS occurred_at_ms_64
          FROM catalog_change
          WHERE sequence > ?$scope
          ORDER BY sequence
          LIMIT ?
          """.trimIndent(),
          *buildList<Any> {
            add(afterSequence)
            libraryIds?.forEach { add(it.value) }
            add(limit)
          }.toTypedArray(),
        ).map { it.toCatalogChange() }
    return CatalogChangePage(
      changes = changes,
      // Unchanged when nothing followed, so an idle client's cursor does not drift forward past
      // changes it has not been given.
      nextCursor = changes.lastOrNull()?.sequence ?: afterSequence,
      floorSequence = floor,
      resyncRequired = false,
    )
  }

  override fun sweepThrough(throughSequence: Long): Int {
    require(throughSequence >= 0) { "Catalog change sweep bound must not be negative" }
    return database.transaction { transaction ->
      val removed =
        transaction.execute("DELETE FROM catalog_change WHERE sequence <= ?", throughSequence)
      // `max` rather than assignment: sweeps are not guaranteed to arrive in order, and a floor that
      // could move backwards would start serving cursors it has already invalidated.
      transaction.execute(
        """
        UPDATE catalog_change_floor
        SET swept_through_sequence = max(swept_through_sequence, ?)
        WHERE id = 1
        """.trimIndent(),
        throughSequence,
      )
      removed
    }
  }

  private fun DSLContext.readFloor(): Long =
    fetchOne(
      "SELECT CAST(swept_through_sequence AS TEXT) AS floor_64 FROM catalog_change_floor WHERE id = 1",
    )?.get("floor_64", String::class.java)?.toLong() ?: 0L

  private fun Record.toCatalogChange(): CatalogChange =
    CatalogChange(
      sequence = requiredLongText("sequence_64"),
      entityKind = CatalogChangeEntityKind.valueOf(requiredString("entity_kind")),
      entityId = requiredString("entity_id"),
      mutation = CatalogChangeMutation.valueOf(requiredString("mutation")),
      libraryId = LibraryId(requiredString("library_id")),
      occurredAtMillis = requiredLongText("occurred_at_ms_64"),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Catalog change $field must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)?.toLong()) {
      "Catalog change $field must not be null"
    }

  private fun Collection<*>.placeholders(): String = joinToString(",") { "?" }
}
