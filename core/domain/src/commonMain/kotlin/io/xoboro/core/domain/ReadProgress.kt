package io.xoboro.core.domain

data class ReadProgress(
  val bookId: BookId,
  val userId: UserId,
  val page: Int,
  val completed: Boolean,
  val readAtMillis: Long,
  val deviceId: String = "",
  val deviceName: String = "",
  val locatorJson: String? = null,
  val createdAtMillis: Long = readAtMillis,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(page >= 0) { "Read progress page must not be negative" }
    require(readAtMillis >= 0) { "Read timestamp must not be negative" }
    require(locatorJson == null || locatorJson.isNotBlank()) {
      "Read locator must be null or non-blank"
    }
    require(createdAtMillis >= 0) { "Progress creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Progress update timestamp must not precede creation"
    }
  }
}

data class SeriesReadProgress(
  val seriesId: SeriesId,
  val userId: UserId,
  val booksReadCount: Int,
  val booksInProgressCount: Int,
  val lastReadAtMillis: Long,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(booksReadCount >= 0) { "Read book count must not be negative" }
    require(booksInProgressCount >= 0) { "In-progress book count must not be negative" }
    require(lastReadAtMillis >= 0) { "Last read timestamp must not be negative" }
    require(createdAtMillis >= 0) { "Progress creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Progress update timestamp must not precede creation"
    }
  }
}

interface ReadProgressRepository {
  fun findByBookIdAndUserIdOrNull(
    bookId: BookId,
    userId: UserId,
  ): ReadProgress?

  fun findAllByBookIdsAndUserId(
    bookIds: Collection<BookId>,
    userId: UserId,
  ): List<ReadProgress>

  fun findSeriesByIdAndUserIdOrNull(
    seriesId: SeriesId,
    userId: UserId,
  ): SeriesReadProgress?

  fun findAllSeriesByIdsAndUserId(
    seriesIds: Collection<SeriesId>,
    userId: UserId,
  ): List<SeriesReadProgress> =
    seriesIds.distinct().mapNotNull { findSeriesByIdAndUserIdOrNull(it, userId) }

  fun upsert(progress: ReadProgress)

  fun upsertAll(progresses: Collection<ReadProgress>)

  fun delete(
    bookId: BookId,
    userId: UserId,
  )

  fun deleteBySeriesIdAndUserId(
    seriesId: SeriesId,
    userId: UserId,
  )
}
