package io.xoboro.server.persistence

import io.xoboro.core.application.MetadataOrganizationWriter
import io.xoboro.core.application.OrganizationEvent
import io.xoboro.core.application.OrganizationEventPublisher
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.SeriesId
import org.jooq.DSLContext

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
    val mutation =
      database.transaction { transaction ->
        val existingId =
          transaction
            .fetchOne(
              "SELECT id FROM read_list WHERE name = ? COLLATE NOCASE",
              normalizedName,
            )?.get("id", String::class.java)
        val now = now()
        if (existingId == null) {
          val id = ReadListId(readListIdFactory())
          transaction.execute(
            """
            INSERT INTO read_list
              (id, name, summary, ordered, created_at_ms, updated_at_ms)
            VALUES (?, ?, '', 1, ?, ?)
            """.trimIndent(),
            id.value,
            normalizedName,
            now,
            now,
          )
          transaction.execute(
            """
            INSERT INTO read_list_member (read_list_id, book_id, position)
            VALUES (?, ?, ?)
            """.trimIndent(),
            id.value,
            bookId.value,
            number.validPositionOrZero(),
          )
          OrganizationMutation(id, added = true, changed = true)
        } else {
          val id = ReadListId(existingId)
          if (transaction.readListContains(id, bookId)) {
            OrganizationMutation(id, added = false, changed = false)
          } else {
            val position =
              number.validPositionOrNull()
                ?.takeUnless { transaction.readListPositionExists(id, it) }
                ?: transaction.nextReadListPosition(id)
            transaction.execute(
              """
              INSERT INTO read_list_member (read_list_id, book_id, position)
              VALUES (?, ?, ?)
              """.trimIndent(),
              id.value,
              bookId.value,
              position,
            )
            transaction.execute(
              "UPDATE read_list SET updated_at_ms = ? WHERE id = ?",
              now,
              id.value,
            )
            OrganizationMutation(id, added = false, changed = true)
          }
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
    val mutation =
      database.transaction { transaction ->
        val existingId =
          transaction
            .fetchOne(
              "SELECT id FROM series_collection WHERE name = ? COLLATE NOCASE",
              normalizedName,
            )?.get("id", String::class.java)
        val now = now()
        if (existingId == null) {
          val id = CollectionId(collectionIdFactory())
          transaction.execute(
            """
            INSERT INTO series_collection
              (id, name, ordered, created_at_ms, updated_at_ms)
            VALUES (?, ?, 0, ?, ?)
            """.trimIndent(),
            id.value,
            normalizedName,
            now,
            now,
          )
          transaction.execute(
            """
            INSERT INTO series_collection_member (collection_id, series_id, position)
            VALUES (?, ?, 0)
            """.trimIndent(),
            id.value,
            seriesId.value,
          )
          CollectionMutation(id, added = true, changed = true)
        } else {
          val id = CollectionId(existingId)
          if (transaction.collectionContains(id, seriesId)) {
            CollectionMutation(id, added = false, changed = false)
          } else {
            transaction.execute(
              """
              INSERT INTO series_collection_member (collection_id, series_id, position)
              VALUES (?, ?, ?)
              """.trimIndent(),
              id.value,
              seriesId.value,
              transaction.nextCollectionPosition(id),
            )
            transaction.execute(
              "UPDATE series_collection SET updated_at_ms = ? WHERE id = ?",
              now,
              id.value,
            )
            CollectionMutation(id, added = false, changed = true)
          }
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

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Timestamp must not be negative" } }

  private fun DSLContext.readListContains(
    id: ReadListId,
    bookId: BookId,
  ): Boolean =
    fetchExists(
      "SELECT 1 FROM read_list_member WHERE read_list_id = ? AND book_id = ?",
      id.value,
      bookId.value,
    )

  private fun DSLContext.readListPositionExists(
    id: ReadListId,
    position: Long,
  ): Boolean =
    fetchExists(
      "SELECT 1 FROM read_list_member WHERE read_list_id = ? AND position = ?",
      id.value,
      position,
    )

  private fun DSLContext.nextReadListPosition(id: ReadListId): Long =
    (fetchValue(
      "SELECT max(position) FROM read_list_member WHERE read_list_id = ?",
      id.value,
    ) as? Number)?.toLong()?.plus(1) ?: 0

  private fun DSLContext.collectionContains(
    id: CollectionId,
    seriesId: SeriesId,
  ): Boolean =
    fetchExists(
      "SELECT 1 FROM series_collection_member WHERE collection_id = ? AND series_id = ?",
      id.value,
      seriesId.value,
    )

  private fun DSLContext.nextCollectionPosition(id: CollectionId): Long =
    (fetchValue(
      "SELECT max(position) FROM series_collection_member WHERE collection_id = ?",
      id.value,
    ) as? Number)?.toLong()?.plus(1) ?: 0

  private fun DSLContext.fetchExists(
    sql: String,
    vararg bindings: Any,
  ): Boolean = fetchOne(sql, *bindings) != null

  private fun Int?.validPositionOrNull(): Long? = this?.takeIf { it >= 0 }?.toLong()

  private fun Int?.validPositionOrZero(): Long = validPositionOrNull() ?: 0

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
}
