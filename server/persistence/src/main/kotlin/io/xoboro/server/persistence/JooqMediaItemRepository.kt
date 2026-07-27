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
import io.xoboro.core.domain.Video

class JooqMediaItemRepository(
  private val database: XoboroDatabase,
  private val books: JooqBookRepository = JooqBookRepository(database),
) : MediaItemRepository {
  override fun findByIdOrNull(id: MediaItemId): MediaItem? {
    val book = books.findByIdOrNull(id) ?: return null
    val type =
      database.dsl
        .fetchOne("SELECT media_item_type FROM book WHERE id = ?", id.value)
        ?.get(0, String::class.java)
        ?.let(MediaItemType::valueOf)
        ?: return null
    return book.toMediaItem(type)
  }

  override fun findAllByLibraryId(libraryId: LibraryId): List<MediaItem> {
    val storedBooks = books.findAllByLibraryId(libraryId)
    if (storedBooks.isEmpty()) return emptyList()
    val types =
      database.dsl
        .fetch(
          """
          SELECT id, media_item_type
          FROM book
          WHERE library_id = ?
          """.trimIndent(),
          libraryId.value,
        ).associate { record ->
          requireNotNull(record.get("id", String::class.java)) to
            MediaItemType.valueOf(
              requireNotNull(record.get("media_item_type", String::class.java)),
            )
        }
    return storedBooks.map { book ->
      book.toMediaItem(requireNotNull(types[book.id.value]))
    }
  }

  private fun Book.toMediaItem(type: MediaItemType): MediaItem =
    when (type) {
      MediaItemType.COMIC -> Comic(this)
      MediaItemType.NOVEL -> Novel(this)
      MediaItemType.BOOK -> this
      MediaItemType.VIDEO -> Video(toCore())
      MediaItemType.AUDIO -> Audio(toCore())
    }

  private fun Book.toCore(): MediaItemCore =
    MediaItemCore(
      id = id,
      libraryId = libraryId,
      name = name,
      relativePath = relativePath,
      sourceItemId = sourceItemId,
      sourceIdentity = sourceIdentity,
      fileModifiedAtMillis = fileModifiedAtMillis,
      fileSize = fileSize,
      deletedAtMillis = deletedAtMillis,
      createdAtMillis = createdAtMillis,
      updatedAtMillis = updatedAtMillis,
    )
}
