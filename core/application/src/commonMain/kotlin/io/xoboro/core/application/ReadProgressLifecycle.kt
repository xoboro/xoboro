package io.xoboro.core.application

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.ReadProgressRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.UserId

class ReadProgressLifecycle(
  private val books: BookRepository,
  private val series: SeriesRepository,
  private val media: BookMediaRepository,
  private val progresses: ReadProgressRepository,
  private val currentTimeMillis: () -> Long,
) {
  fun updateBook(
    bookId: BookId,
    userId: UserId,
    page: Int?,
    completed: Boolean?,
  ): ReadProgress? {
    val book = books.findByIdOrNull(bookId) ?: return null
    require(book.deletedAtMillis == null) { "Cannot update progress for a deleted book" }
    require(page != null || completed == true) {
      "page must be specified if completed is false or null"
    }
    val analyzed = media.findByBookIdOrNull(book.id)
      ?: throw IllegalArgumentException("Book media is not ready")
    val targetPage =
      if (completed == true) {
        analyzed.pageCount
      } else {
        requireNotNull(page)
      }
    if (completed != true) {
      require(targetPage in 1..analyzed.pageCount) {
        "Page argument ($targetPage) must be within 1 and book page count (${analyzed.pageCount})"
      }
    }
    val now = now()
    val existing = progresses.findByBookIdAndUserIdOrNull(book.id, userId)
    val progress =
      ReadProgress(
        bookId = book.id,
        userId = userId,
        page = targetPage,
        completed = completed == true || targetPage == analyzed.pageCount,
        readAtMillis = now,
        deviceId = existing?.deviceId.orEmpty(),
        deviceName = existing?.deviceName.orEmpty(),
        locatorJson = existing?.locatorJson,
        createdAtMillis = existing?.createdAtMillis ?: now,
        updatedAtMillis = now,
      )
    progresses.upsert(progress)
    return progresses.findByBookIdAndUserIdOrNull(book.id, userId)
  }

  fun deleteBook(
    bookId: BookId,
    userId: UserId,
  ): Boolean {
    val book = books.findByIdOrNull(bookId) ?: return false
    progresses.delete(book.id, userId)
    return true
  }

  fun markSeriesCompleted(
    seriesId: SeriesId,
    userId: UserId,
  ): Boolean {
    val item = series.findByIdOrNull(seriesId) ?: return false
    require(item.deletedAtMillis == null) { "Cannot update progress for a deleted series" }
    val now = now()
    val itemBooks = books.findAllBySeriesId(item.id).filter { it.deletedAtMillis == null }
    val existing =
      progresses
        .findAllByBookIdsAndUserId(itemBooks.map { it.id }, userId)
        .associateBy(ReadProgress::bookId)
    val updates =
      itemBooks.mapNotNull { book ->
        val analyzed = media.findByBookIdOrNull(book.id) ?: return@mapNotNull null
        ReadProgress(
          bookId = book.id,
          userId = userId,
          page = analyzed.pageCount,
          completed = true,
          readAtMillis = now,
          deviceId = existing[book.id]?.deviceId.orEmpty(),
          deviceName = existing[book.id]?.deviceName.orEmpty(),
          locatorJson = existing[book.id]?.locatorJson,
          createdAtMillis = existing[book.id]?.createdAtMillis ?: now,
          updatedAtMillis = now,
        )
      }
    progresses.upsertAll(updates)
    return true
  }

  fun markSeriesUnread(
    seriesId: SeriesId,
    userId: UserId,
  ): Boolean {
    if (series.findByIdOrNull(seriesId) == null) return false
    progresses.deleteBySeriesIdAndUserId(seriesId, userId)
    return true
  }

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Read timestamp must not be negative" } }
}
