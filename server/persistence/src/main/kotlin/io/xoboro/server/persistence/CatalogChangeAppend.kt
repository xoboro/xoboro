package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.domain.CatalogChangeEntityKind
import org.jooq.DSLContext

/**
 * Records mutations in the durable change feed, on the caller's transaction.
 *
 * An extension on [DSLContext] rather than a repository method, because a change row is only worth
 * anything if it commits with the mutation it describes. ADR 0056 publishes reconciliation events after
 * the transaction commits - correct for SSE, which must not announce what might roll back - but a feed
 * written there would lose a change to any crash in that window, and a lost deletion is the one thing a
 * client holding a local copy cannot recover by re-reading. So this takes the transaction it is given
 * and never opens its own.
 *
 * The same [CatalogMutationEvent] list that is published afterwards is what gets written, so the feed
 * and the live stream cannot describe different histories.
 */
internal fun DSLContext.appendCatalogChanges(
  events: List<CatalogMutationEvent>,
  occurredAtMillis: Long,
) {
  require(occurredAtMillis >= 0) { "Catalog change timestamp must not be negative" }
  events.forEach { event ->
    val (kind, entityId) =
      when (event) {
        is CatalogMutationEvent.Book ->
          CatalogChangeEntityKind.MEDIA_ITEM to event.bookId.value
        is CatalogMutationEvent.Series ->
          CatalogChangeEntityKind.SERIES to event.seriesId.value
      }
    execute(
      """
      INSERT INTO catalog_change (
        entity_kind, entity_id, mutation, library_id, occurred_at_ms
      ) VALUES (?, ?, ?, ?, ?)
      """.trimIndent(),
      kind.name,
      entityId,
      // `CatalogMutationKind` and `CatalogChangeMutation` name the same three outcomes. Mapped by name
      // rather than aliased, so adding a kind to one is a compile error here instead of a row the feed
      // silently cannot store.
      event.kind.name,
      event.libraryId.value,
      occurredAtMillis,
    )
  }
}
