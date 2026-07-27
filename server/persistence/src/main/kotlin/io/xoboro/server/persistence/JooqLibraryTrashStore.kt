package io.xoboro.server.persistence

import io.xoboro.core.application.EmptyTrashResult
import io.xoboro.core.application.LibraryTrashStore
import io.xoboro.core.domain.LibraryId

class JooqLibraryTrashStore(
  private val database: XoboroDatabase,
) : LibraryTrashStore {
  override fun emptyTrash(libraryId: LibraryId): EmptyTrashResult =
    database.transaction { transaction ->
      val deletedBooks =
        transaction.execute(
          """
          DELETE FROM book
          WHERE library_id = ? AND deleted_at_ms IS NOT NULL
          """.trimIndent(),
          libraryId.value,
        )
      val deletedSeries =
        transaction.execute(
          """
          DELETE FROM series
          WHERE library_id = ? AND deleted_at_ms IS NOT NULL
            AND NOT EXISTS (
              SELECT 1 FROM book WHERE book.series_id = series.id
            )
          """.trimIndent(),
          libraryId.value,
        )
      EmptyTrashResult(
        deletedBooks = deletedBooks,
        deletedSeries = deletedSeries,
      )
    }
}
