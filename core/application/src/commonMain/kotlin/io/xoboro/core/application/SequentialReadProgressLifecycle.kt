package io.xoboro.core.application

import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.UserId

data class IndexedReadProgress(
  val booksCount: Int,
  val booksReadCount: Int,
  val booksUnreadCount: Int,
  val booksInProgressCount: Int,
  val lastReadContinuousIndex: Int,
)

data class NumberedReadProgress(
  val booksCount: Int,
  val booksReadCount: Int,
  val booksUnreadCount: Int,
  val booksInProgressCount: Int,
  val lastReadContinuousNumberSort: Float,
  val maxNumberSort: Float,
)

class SequentialReadProgressLifecycle(
  private val catalog: CatalogReadRepository,
  private val readLists: ReadListRepository,
  private val progress: ReadProgressLifecycle,
) {
  fun findReadList(
    id: ReadListId,
    access: CatalogAccess,
    allowEmpty: Boolean,
  ): IndexedReadProgress? {
    val books = visibleReadListBooks(id, access, allowEmpty) ?: return null
    val counts = books.counts()
    return IndexedReadProgress(
      booksCount = books.size,
      booksReadCount = counts.read,
      booksUnreadCount = counts.unread,
      booksInProgressCount = counts.inProgress,
      lastReadContinuousIndex = books.takeWhile { it.readProgress?.completed == true }.size,
    )
  }

  fun updateReadList(
    id: ReadListId,
    access: CatalogAccess,
    userId: UserId,
    lastBookRead: Int,
    allowEmpty: Boolean,
  ): Boolean {
    require(lastBookRead >= 0) { "lastBookRead must be positive or zero" }
    val books = visibleReadListBooks(id, access, allowEmpty) ?: return false
    books.take(lastBookRead).forEach { book ->
      if (book.readProgress?.completed != true) {
        progress.updateBook(book.book.id, userId, page = null, completed = true)
      }
    }
    return true
  }

  fun findSeries(
    id: SeriesId,
    access: CatalogAccess,
  ): NumberedReadProgress? {
    if (catalog.findSeriesByIdOrNull(id, access) == null) return null
    val books = seriesBooks(id, access)
    val counts = books.counts()
    return NumberedReadProgress(
      booksCount = books.size,
      booksReadCount = counts.read,
      booksUnreadCount = counts.unread,
      booksInProgressCount = counts.inProgress,
      lastReadContinuousNumberSort =
        books
          .takeWhile { it.readProgress?.completed == true }
          .lastOrNull()
          ?.metadata
          ?.numberSort
          ?: 0F,
      maxNumberSort = books.maxOfOrNull { it.metadata.numberSort } ?: 0F,
    )
  }

  fun updateSeries(
    id: SeriesId,
    access: CatalogAccess,
    userId: UserId,
    lastBookNumberSortRead: Float,
  ): Boolean {
    require(lastBookNumberSortRead.isFinite()) {
      "lastBookNumberSortRead must be finite"
    }
    if (catalog.findSeriesByIdOrNull(id, access) == null) return false
    seriesBooks(id, access)
      .filter { it.metadata.numberSort <= lastBookNumberSortRead }
      .forEach { book ->
        if (book.readProgress?.completed != true) {
          progress.updateBook(book.book.id, userId, page = null, completed = true)
        }
      }
    return true
  }

  private fun visibleReadListBooks(
    id: ReadListId,
    access: CatalogAccess,
    allowEmpty: Boolean,
  ): List<CatalogBook>? {
    val readList = readLists.findByIdOrNull(id) ?: return null
    val visible = readList.bookIds.mapNotNull { catalog.findBookByIdOrNull(it, access) }
    return visible.takeIf { it.isNotEmpty() || (allowEmpty && readList.bookIds.isEmpty()) }
  }

  private fun seriesBooks(
    id: SeriesId,
    access: CatalogAccess,
  ): List<CatalogBook> =
    catalog.findBooks(
      query = BookCatalogQuery(seriesId = id),
      access = access,
      page =
        CatalogPageRequest(
          sorts = listOf(CatalogSort("numberSort")),
          unpaged = true,
        ),
    ).content

  private fun List<CatalogBook>.counts(): ProgressCounts =
    ProgressCounts(
      read = count { it.readProgress?.completed == true },
      unread = count { it.readProgress == null },
      inProgress = count { it.readProgress != null && !it.readProgress.completed },
    )

  private data class ProgressCounts(
    val read: Int,
    val unread: Int,
    val inProgress: Int,
  )
}
