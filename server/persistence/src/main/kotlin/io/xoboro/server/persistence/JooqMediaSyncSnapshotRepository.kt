package io.xoboro.server.persistence

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaSyncSnapshot
import io.xoboro.core.domain.MediaSyncSnapshotRepository
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.SyncMediaItemState
import io.xoboro.core.domain.SyncPointId
import io.xoboro.core.domain.SyncProgressState
import io.xoboro.core.domain.SyncReadListState
import org.jooq.DSLContext
import org.jooq.Record

class JooqMediaSyncSnapshotRepository(
  private val database: XoboroDatabase,
) : MediaSyncSnapshotRepository {
  override fun findBySyncPointIdOrNull(id: SyncPointId): MediaSyncSnapshot? {
    val exists =
      database.dsl.fetchExists(
        database.dsl.selectOne().from("sync_point").where("id = ?", id.value),
      )
    if (!exists) return null
    val mediaItems =
      database.dsl
        .fetch(
          """
          SELECT *,
            CAST(created_at_ms AS TEXT) AS created_at_ms_64,
            CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
          FROM media_sync_item
          WHERE sync_point_id = ?
          ORDER BY media_item_id
          """.trimIndent(),
          id.value,
        ).map { it.toMediaItemState() }
    val readListItems =
      database.dsl
        .fetch(
          """
          SELECT read_list_id, media_item_id
          FROM media_sync_read_list_item
          WHERE sync_point_id = ?
          ORDER BY read_list_id, ordinal
          """.trimIndent(),
          id.value,
        ).groupBy(
          { it.requiredString("read_list_id") },
          { BookId(it.requiredString("media_item_id")) },
        )
    val readLists =
      database.dsl
        .fetch(
          """
          SELECT *,
            CAST(created_at_ms AS TEXT) AS created_at_ms_64,
            CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
          FROM media_sync_read_list
          WHERE sync_point_id = ?
          ORDER BY read_list_id
          """.trimIndent(),
          id.value,
        ).map { it.toReadListState(readListItems) }
    val progresses =
      database.dsl
        .fetch(
          """
          SELECT media_item_id, revision
          FROM media_sync_progress
          WHERE sync_point_id = ?
          ORDER BY media_item_id
          """.trimIndent(),
          id.value,
        ).map {
          SyncProgressState(
            mediaItemId = BookId(it.requiredString("media_item_id")),
            revision = it.requiredString("revision"),
          )
        }
    return MediaSyncSnapshot(id, mediaItems, readLists, progresses)
  }

  override fun insert(snapshot: MediaSyncSnapshot) {
    database.transaction { transaction ->
      insertMediaItems(transaction, snapshot)
      insertReadLists(transaction, snapshot)
      insertProgresses(transaction, snapshot)
    }
  }

  private fun insertMediaItems(
    transaction: DSLContext,
    snapshot: MediaSyncSnapshot,
  ) {
    snapshot.mediaItems.forEach { item ->
      transaction.execute(
        """
        INSERT INTO media_sync_item (
          sync_point_id, media_item_id, revision, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
        snapshot.syncPointId.value,
        item.mediaItemId.value,
        item.revision,
        item.createdAtMillis,
        item.updatedAtMillis,
      )
    }
  }

  private fun insertReadLists(
    transaction: DSLContext,
    snapshot: MediaSyncSnapshot,
  ) {
    snapshot.readLists.forEach { readList ->
      transaction.execute(
        """
        INSERT INTO media_sync_read_list (
          sync_point_id, read_list_id, name, revision, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        snapshot.syncPointId.value,
        readList.readListId.value,
        readList.name,
        readList.revision,
        readList.createdAtMillis,
        readList.updatedAtMillis,
      )
      readList.mediaItemIds.forEachIndexed { index, mediaItemId ->
        transaction.execute(
          """
          INSERT INTO media_sync_read_list_item (
            sync_point_id, read_list_id, media_item_id, ordinal
          ) VALUES (?, ?, ?, ?)
          """.trimIndent(),
          snapshot.syncPointId.value,
          readList.readListId.value,
          mediaItemId.value,
          index,
        )
      }
    }
  }

  private fun insertProgresses(
    transaction: DSLContext,
    snapshot: MediaSyncSnapshot,
  ) {
    snapshot.progresses.forEach { progress ->
      transaction.execute(
        """
        INSERT INTO media_sync_progress (sync_point_id, media_item_id, revision)
        VALUES (?, ?, ?)
        """.trimIndent(),
        snapshot.syncPointId.value,
        progress.mediaItemId.value,
        progress.revision,
      )
    }
  }

  private fun Record.toMediaItemState(): SyncMediaItemState =
    SyncMediaItemState(
      mediaItemId = BookId(requiredString("media_item_id")),
      revision = requiredString("revision"),
      createdAtMillis = requiredString("created_at_ms_64").toLong(),
      updatedAtMillis = requiredString("updated_at_ms_64").toLong(),
    )

  private fun Record.toReadListState(
    items: Map<String, List<BookId>>,
  ): SyncReadListState =
    SyncReadListState(
      readListId = ReadListId(requiredString("read_list_id")),
      name = requiredString("name"),
      revision = requiredString("revision"),
      mediaItemIds = items[requiredString("read_list_id")].orEmpty(),
      createdAtMillis = requiredString("created_at_ms_64").toLong(),
      updatedAtMillis = requiredString("updated_at_ms_64").toLong(),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) {
      "Database field '$field' must not be null"
    }
}
