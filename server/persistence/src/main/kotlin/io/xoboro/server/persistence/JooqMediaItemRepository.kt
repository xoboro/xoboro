package io.xoboro.server.persistence

import io.xoboro.core.domain.Audio
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.Comic
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaItem
import io.xoboro.core.domain.MediaItemCore
import io.xoboro.core.domain.MediaItemId
import io.xoboro.core.domain.MediaItemRepository
import io.xoboro.core.domain.MediaItemType
import io.xoboro.core.domain.Novel
import io.xoboro.core.domain.TimelineMediaItem
import io.xoboro.core.domain.TimelineMediaItemRepository
import io.xoboro.core.domain.Video
import org.jooq.Record

class JooqMediaItemRepository(
  private val database: XoboroDatabase,
  private val books: JooqBookRepository = JooqBookRepository(database),
) : MediaItemRepository,
  TimelineMediaItemRepository {
  override fun findByIdOrNull(id: MediaItemId): MediaItem? {
    val record =
      database.dsl.fetchOne(
        "$SELECT_MEDIA_ITEM WHERE id = ?",
        id.value,
      ) ?: return null
    return record.toMediaItem()
  }

  override fun findAllByLibraryId(libraryId: LibraryId): List<MediaItem> {
    val records =
      database.dsl.fetch(
        "$SELECT_MEDIA_ITEM WHERE library_id = ? ORDER BY relative_uri, id",
        libraryId.value,
      )
    if (records.isEmpty()) return emptyList()
    val documentIds =
      records
        .asSequence()
        .filter { it.requiredType().isDocument }
        .map { it.requiredString("id") }
        .toSet()
    val documents =
      if (documentIds.isEmpty()) {
        emptyMap()
      } else {
        books.findAllByLibraryId(libraryId)
          .asSequence()
          .filter { it.id.value in documentIds }
          .associateBy { it.id.value }
      }
    return records.map { record -> record.toMediaItem(documents) }
  }

  override fun insert(item: TimelineMediaItem) {
    database.dsl.execute(
      """
      INSERT INTO media_item (
        id, library_id, media_item_type, relative_uri, source_item_id,
        source_identity, name, file_size, file_modified_ms, duration_ms,
        deleted_at_ms, created_at_ms, updated_at_ms
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      *item.bindValues(),
    )
  }

  override fun update(item: TimelineMediaItem) {
    val updated =
      database.dsl.execute(
        """
        UPDATE media_item SET
          library_id = ?,
          media_item_type = ?,
          relative_uri = ?,
          source_item_id = ?,
          source_identity = ?,
          name = ?,
          file_size = ?,
          file_modified_ms = ?,
          duration_ms = ?,
          deleted_at_ms = ?,
          created_at_ms = ?,
          updated_at_ms = ?
        WHERE id = ? AND media_item_type IN ('VIDEO', 'AUDIO')
        """.trimIndent(),
        item.libraryId.value,
        item.type.name,
        item.relativePath,
        item.sourceItemId,
        item.sourceIdentity,
        item.name,
        item.fileSize,
        item.fileModifiedAtMillis,
        item.durationMillis,
        item.deletedAtMillis,
        item.createdAtMillis,
        item.updatedAtMillis,
        item.id.value,
      )
    if (updated == 0) {
      throw NoSuchElementException("Timeline media item not found: ${item.id.value}")
    }
  }

  override fun delete(id: MediaItemId): Boolean =
    database.dsl.execute(
      """
      DELETE FROM media_item
      WHERE id = ? AND media_item_type IN ('VIDEO', 'AUDIO')
      """.trimIndent(),
      id.value,
    ) == 1

  private fun Record.toMediaItem(documents: Map<String, Book> = emptyMap()): MediaItem {
    val type = requiredType()
    val id = requiredString("id")
    return when (type) {
      MediaItemType.COMIC ->
        Comic(documents[id] ?: requireNotNull(books.findByIdOrNull(MediaItemId(id))))
      MediaItemType.NOVEL ->
        Novel(documents[id] ?: requireNotNull(books.findByIdOrNull(MediaItemId(id))))
      MediaItemType.BOOK ->
        documents[id] ?: requireNotNull(books.findByIdOrNull(MediaItemId(id)))
      MediaItemType.VIDEO -> Video(toCore(), nullableLongText("duration_ms_64"))
      MediaItemType.AUDIO -> Audio(toCore(), nullableLongText("duration_ms_64"))
    }
  }

  private fun Record.toCore(): MediaItemCore =
    MediaItemCore(
      id = MediaItemId(requiredString("id")),
      libraryId = LibraryId(requiredString("library_id")),
      name = requiredString("name"),
      relativePath = requiredString("relative_uri"),
      sourceItemId = requiredString("source_item_id"),
      sourceIdentity = get("source_identity", String::class.java),
      fileModifiedAtMillis = requiredLongText("file_modified_ms_64"),
      fileSize = requiredLongText("file_size_64"),
      deletedAtMillis = nullableLongText("deleted_at_ms_64"),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private fun TimelineMediaItem.bindValues(): Array<Any?> =
    arrayOf(
      id.value,
      libraryId.value,
      type.name,
      relativePath,
      sourceItemId,
      sourceIdentity,
      name,
      fileSize,
      fileModifiedAtMillis,
      durationMillis,
      deletedAtMillis,
      createdAtMillis,
      updatedAtMillis,
    )

  private fun Record.requiredType(): MediaItemType =
    MediaItemType.valueOf(requiredString("media_item_type"))

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.nullableLongText(field: String): Long? =
    get(field, String::class.java)?.toLong()

  private val MediaItemType.isDocument: Boolean
    get() = this == MediaItemType.COMIC || this == MediaItemType.NOVEL || this == MediaItemType.BOOK

  private companion object {
    const val SELECT_MEDIA_ITEM =
      """
      SELECT media_item.*,
        CAST(file_size AS TEXT) AS file_size_64,
        CAST(file_modified_ms AS TEXT) AS file_modified_ms_64,
        CAST(duration_ms AS TEXT) AS duration_ms_64,
        CAST(deleted_at_ms AS TEXT) AS deleted_at_ms_64,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64,
        CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
      FROM media_item
      """
  }
}
