package io.xoboro.core.application

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.ReadProgressRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesRepository
import io.xoboro.core.domain.UserId

sealed interface ReadProgressEvent {
  val userId: UserId

  data class Changed(
    val progress: ReadProgress,
  ) : ReadProgressEvent {
    override val userId: UserId = progress.userId
  }

  data class Deleted(
    val bookId: BookId,
    override val userId: UserId,
  ) : ReadProgressEvent

  data class SeriesChanged(
    val seriesId: SeriesId,
    override val userId: UserId,
  ) : ReadProgressEvent

  data class SeriesDeleted(
    val seriesId: SeriesId,
    override val userId: UserId,
  ) : ReadProgressEvent
}

fun interface ReadProgressEventPublisher {
  fun publish(event: ReadProgressEvent)
}

/**
 * Outcome of a progression update.
 *
 * [Applied] normally implies a [ReadProgressEvent.Changed] publication, but not always: when the
 * accepted row is deleted concurrently the update is still reported as applied while no event is
 * published. Callers must not treat [Applied] as a guarantee that an event was emitted.
 *
 * [Stale.stored] is the progress currently held by the server. In the same concurrent-delete case
 * the row no longer exists and the rejected candidate is returned in its place, so callers should
 * not echo it to clients as authoritative server state.
 */
sealed interface ReadProgressUpdate {
  data object MediaItemNotFound : ReadProgressUpdate

  data class Applied(
    val progress: ReadProgress,
  ) : ReadProgressUpdate

  data class Stale(
    val stored: ReadProgress,
  ) : ReadProgressUpdate
}

class ReadProgressLifecycle(
  private val books: BookRepository,
  private val series: SeriesRepository,
  private val media: BookMediaRepository,
  private val progresses: ReadProgressRepository,
  private val currentTimeMillis: () -> Long,
  private val eventPublisher: ReadProgressEventPublisher = ReadProgressEventPublisher {},
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
      ?.also { eventPublisher.publish(ReadProgressEvent.Changed(it)) }
  }

  fun deleteBook(
    bookId: BookId,
    userId: UserId,
  ): Boolean {
    val book = books.findByIdOrNull(bookId) ?: return false
    val existing = progresses.findByBookIdAndUserIdOrNull(book.id, userId)
    progresses.delete(book.id, userId)
    if (existing != null) {
      eventPublisher.publish(ReadProgressEvent.Deleted(book.id, userId))
    }
    return true
  }

  fun findBook(
    bookId: BookId,
    userId: UserId,
  ): ReadProgress? =
    books.findByIdOrNull(bookId)
      ?.takeIf { it.deletedAtMillis == null }
      ?.let { progresses.findByBookIdAndUserIdOrNull(it.id, userId) }

  fun updateBookProgression(
    bookId: BookId,
    userId: UserId,
    page: Int,
    modifiedAtMillis: Long,
    deviceId: String,
    deviceName: String,
    locatorJson: String,
  ): ReadProgressUpdate {
    val book = books.findByIdOrNull(bookId) ?: return ReadProgressUpdate.MediaItemNotFound
    require(book.deletedAtMillis == null) { "Cannot update progress for a deleted book" }
    require(modifiedAtMillis >= 0) { "Progression timestamp must not be negative" }
    require(locatorJson.isNotBlank()) { "Progression locator must not be blank" }
    val analyzed =
      media.findByBookIdOrNull(book.id)
        ?: throw IllegalArgumentException("Book media is not ready")
    require(page in 1..analyzed.pageCount) {
      "Page argument ($page) must be within 1 and book page count (${analyzed.pageCount})"
    }
    val existing = progresses.findByBookIdAndUserIdOrNull(book.id, userId)
    val now = now()
    val candidate =
      ReadProgress(
        bookId = book.id,
        userId = userId,
        page = page,
        completed = page == analyzed.pageCount,
        readAtMillis = modifiedAtMillis,
        deviceId = deviceId,
        deviceName = deviceName,
        locatorJson = locatorJson,
        createdAtMillis = existing?.createdAtMillis ?: now,
        updatedAtMillis = now,
      )
    if (!progresses.upsertIfNewer(candidate)) {
      return ReadProgressUpdate.Stale(
        progresses.findByBookIdAndUserIdOrNull(book.id, userId) ?: candidate,
      )
    }
    val stored = progresses.findByBookIdAndUserIdOrNull(book.id, userId)
    return ReadProgressUpdate.Applied(stored ?: candidate)
      .also { if (stored != null) eventPublisher.publish(ReadProgressEvent.Changed(stored)) }
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
    eventPublisher.publish(ReadProgressEvent.SeriesChanged(seriesId, userId))
    return true
  }

  fun markSeriesUnread(
    seriesId: SeriesId,
    userId: UserId,
  ): Boolean {
    if (series.findByIdOrNull(seriesId) == null) return false
    progresses.deleteBySeriesIdAndUserId(seriesId, userId)
    eventPublisher.publish(ReadProgressEvent.SeriesDeleted(seriesId, userId))
    return true
  }

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Read timestamp must not be negative" } }
}
