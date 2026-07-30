package io.xoboro.server.persistence

import io.xoboro.core.application.MetadataOrganizationWriter
import io.xoboro.core.application.OrganizationEvent
import io.xoboro.core.application.OrganizationEventPublisher
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.SeriesId
import org.jooq.DSLContext

/**
 * Writes the collections and read lists implied by book metadata.
 *
 * Both entry points deliberately keep the name lookup **outside** the write transaction and make
 * the transaction's first statement a write. Reading first and writing later inside one transaction
 * is the shape that fails with `SQLITE_BUSY_SNAPSHOT`: SQLite refuses to upgrade a transaction to a
 * writer once another connection has committed against the read snapshot it already took, and
 * `busy_timeout` does not wait for a mid-transaction lock upgrade the way it waits for a fresh
 * transaction's first write. Metadata organization runs from book analysis, so several books being
 * analysed concurrently drive exactly that contention.
 *
 * Moving the lookup out means it can be stale - another connection may create the same name in
 * between. `INSERT OR IGNORE` reports that (zero rows), and the create paths fall back to the
 * existing-row path with the write lock already held, so a lost race costs one wasted id from the
 * id factory rather than a failure.
 *
 * The membership inserts fold their duplicate check and position choice into the statement itself
 * for the same reason - they would otherwise have to read before writing. They use plain `INSERT`
 * rather than `INSERT OR IGNORE`: the `WHERE NOT EXISTS` guard already absorbs the "already a
 * member" case, so anything left for `OR IGNORE` to swallow would be a genuine constraint violation
 * that should surface.
 */
class JooqMetadataOrganizationWriter(
  private val database: XoboroDatabase,
  private val collectionIdFactory: () -> String,
  private val readListIdFactory: () -> String,
  private val currentTimeMillis: () -> Long,
  private val eventPublisher: OrganizationEventPublisher = OrganizationEventPublisher {},
) : MetadataOrganizationWriter {
  private val collections = JooqSeriesCollectionRepository(database)
  private val readLists = JooqReadListRepository(database)

  override fun addBookToReadList(
    name: String,
    bookId: BookId,
    number: Int?,
  ) {
    val normalizedName = name.trim().also { require(it.isNotEmpty()) }
    val knownId = findReadListIdOrNull(normalizedName)
    val mutation =
      database.transaction { transaction ->
        val now = now()
        if (knownId == null) {
          transaction.createReadListWithBook(normalizedName, bookId, number, now)
        } else {
          transaction.addBookToKnownReadList(ReadListId(knownId), bookId, number, now)
        }
      }
    if (!mutation.changed) return
    val readList = requireNotNull(readLists.findByIdOrNull(mutation.id))
    eventPublisher.publish(
      if (mutation.added) {
        OrganizationEvent.ReadListAdded(readList)
      } else {
        OrganizationEvent.ReadListUpdated(readList)
      },
    )
  }

  override fun addSeriesToCollection(
    name: String,
    seriesId: SeriesId,
  ) {
    val normalizedName = name.trim().also { require(it.isNotEmpty()) }
    val knownId = findCollectionIdOrNull(normalizedName)
    val mutation =
      database.transaction { transaction ->
        val now = now()
        if (knownId == null) {
          transaction.createCollectionWithSeries(normalizedName, seriesId, now)
        } else {
          transaction.addSeriesToKnownCollection(CollectionId(knownId), seriesId, now)
        }
      }
    if (!mutation.changed) return
    val collection = requireNotNull(collections.findByIdOrNull(mutation.id))
    eventPublisher.publish(
      if (mutation.added) {
        OrganizationEvent.CollectionAdded(collection)
      } else {
        OrganizationEvent.CollectionUpdated(collection)
      },
    )
  }

  private fun DSLContext.createReadListWithBook(
    name: String,
    bookId: BookId,
    number: Int?,
    now: Long,
  ): OrganizationMutation {
    val candidateId = ReadListId(readListIdFactory())
    val created =
      execute(
        """
        INSERT OR IGNORE INTO read_list
          (id, name, summary, ordered, created_at_ms, updated_at_ms)
        VALUES (?, ?, '', 1, ?, ?)
        """.trimIndent(),
        candidateId.value,
        name,
        now,
        now,
      ) == 1
    val id = if (created) candidateId else ReadListId(requireNotNull(findReadListId(name)))
    val mutation = addBookToKnownReadList(id, bookId, number, now)
    return if (created) mutation.copy(added = true) else mutation
  }

  private fun DSLContext.addBookToKnownReadList(
    id: ReadListId,
    bookId: BookId,
    number: Int?,
    now: Long,
  ): OrganizationMutation {
    val requestedPosition = number.validPositionOrNull() ?: NO_REQUESTED_POSITION
    val inserted =
      execute(
        """
        INSERT INTO read_list_member (read_list_id, book_id, position)
        SELECT ?, ?, coalesce(
          (
            SELECT ?
            WHERE ? >= 0
              AND NOT EXISTS (
                SELECT 1 FROM read_list_member WHERE read_list_id = ? AND position = ?
              )
          ),
          (SELECT coalesce(max(position) + 1, 0) FROM read_list_member WHERE read_list_id = ?)
        )
        WHERE NOT EXISTS (
          SELECT 1 FROM read_list_member WHERE read_list_id = ? AND book_id = ?
        )
        """.trimIndent(),
        id.value,
        bookId.value,
        requestedPosition,
        requestedPosition,
        id.value,
        requestedPosition,
        id.value,
        id.value,
        bookId.value,
      ) == 1
    if (!inserted) return OrganizationMutation(id, added = false, changed = false)
    execute("UPDATE read_list SET updated_at_ms = ? WHERE id = ?", now, id.value)
    return OrganizationMutation(id, added = false, changed = true)
  }

  private fun DSLContext.createCollectionWithSeries(
    name: String,
    seriesId: SeriesId,
    now: Long,
  ): CollectionMutation {
    val candidateId = CollectionId(collectionIdFactory())
    val created =
      execute(
        """
        INSERT OR IGNORE INTO series_collection
          (id, name, ordered, created_at_ms, updated_at_ms)
        VALUES (?, ?, 0, ?, ?)
        """.trimIndent(),
        candidateId.value,
        name,
        now,
        now,
      ) == 1
    val id = if (created) candidateId else CollectionId(requireNotNull(findCollectionId(name)))
    val mutation = addSeriesToKnownCollection(id, seriesId, now)
    return if (created) mutation.copy(added = true) else mutation
  }

  private fun DSLContext.addSeriesToKnownCollection(
    id: CollectionId,
    seriesId: SeriesId,
    now: Long,
  ): CollectionMutation {
    val inserted =
      execute(
        """
        INSERT INTO series_collection_member (collection_id, series_id, position)
        SELECT ?, ?, (
          SELECT coalesce(max(position) + 1, 0)
          FROM series_collection_member
          WHERE collection_id = ?
        )
        WHERE NOT EXISTS (
          SELECT 1 FROM series_collection_member WHERE collection_id = ? AND series_id = ?
        )
        """.trimIndent(),
        id.value,
        seriesId.value,
        id.value,
        id.value,
        seriesId.value,
      ) == 1
    if (!inserted) return CollectionMutation(id, added = false, changed = false)
    execute("UPDATE series_collection SET updated_at_ms = ? WHERE id = ?", now, id.value)
    return CollectionMutation(id, added = false, changed = true)
  }

  private fun findReadListIdOrNull(name: String): String? = database.dsl.findReadListId(name)

  private fun findCollectionIdOrNull(name: String): String? = database.dsl.findCollectionId(name)

  private fun DSLContext.findReadListId(name: String): String? =
    fetchOne("SELECT id FROM read_list WHERE name = ? COLLATE NOCASE", name)
      ?.get("id", String::class.java)

  private fun DSLContext.findCollectionId(name: String): String? =
    fetchOne("SELECT id FROM series_collection WHERE name = ? COLLATE NOCASE", name)
      ?.get("id", String::class.java)

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Timestamp must not be negative" } }

  private fun Int?.validPositionOrNull(): Long? = this?.takeIf { it >= 0 }?.toLong()

  private data class OrganizationMutation(
    val id: ReadListId,
    val added: Boolean,
    val changed: Boolean,
  )

  private data class CollectionMutation(
    val id: CollectionId,
    val added: Boolean,
    val changed: Boolean,
  )

  private companion object {
    /**
     * Stands in for "no position requested" in the membership insert. A sentinel rather than a null
     * bind so every binding stays a non-null Long and the `? >= 0` guard in the statement decides,
     * instead of relying on jOOQ inferring a type for an untyped null.
     */
    const val NO_REQUESTED_POSITION = -1L
  }
}
