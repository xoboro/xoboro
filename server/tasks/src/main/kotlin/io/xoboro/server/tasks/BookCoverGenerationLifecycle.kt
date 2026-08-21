package io.xoboro.server.tasks

import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesRepository
import java.io.ByteArrayOutputStream
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Produces a media item's cover after metadata refresh, and keeps its series' cover pointed at the
 * first item in reading order.
 *
 * Builds entirely on [ArtworkLifecycle.replaceGenerated] rather than a parallel storage path: that
 * is the API that already keeps a `GENERATED` artwork from accumulating across re-analysis and
 * already defers to an operator's `USER_UPLOADED` artwork for which one is selected. Nothing here
 * duplicates that logic.
 *
 * A missing cover is always a normal outcome, never a failure: a corrupt page, an EPUB with no
 * declared cover, or a series whose first item has not been analyzed yet all leave the owner
 * without a `GENERATED` artwork rather than throwing. [generateForBook] and [generateForSeries]
 * catch everything for that reason. Cover generation runs in the durable metadata-refresh task,
 * after analysis has succeeded, so transient store contention can retry enrichment without making
 * the media item unreadable again.
 */
class BookCoverGenerationLifecycle(
  private val books: BookRepository,
  private val media: BookMediaRepository,
  private val bookMetadata: BookMetadataRepository,
  private val series: SeriesRepository,
  private val content: BookContentAccess,
  private val artwork: ArtworkLifecycle,
  /**
   * Read per cover rather than captured once, so an operator who changes the setting sees it apply
   * to the next cover instead of after a restart. Analysis runs for hours on a large library, which
   * is exactly when the setting gets changed.
   */
  private val maximumCoverDimension: () -> Int,
  /**
   * Whether a failure is the store being momentarily busy rather than this item having no cover.
   *
   * Injected because recognising it means reading a driver result code, which belongs to the
   * persistence module and not here. Defaults to "never", which is right for wiring with no write
   * lock to contend over.
   */
  private val isStoreBusy: (Throwable) -> Boolean = { false },
) {
  fun generateForBook(bookId: BookId) {
    runCatching { generate(bookId) }
      .onFailure { rethrowIfBusy(it) }
      .onFailure { logSwallowed("media item ${bookId.value}", it) }
  }

  fun generateForSeries(seriesId: SeriesId) {
    runCatching { refreshSeriesCover(seriesId) }
      .onFailure { rethrowIfBusy(it) }
      .onFailure { logSwallowed("series ${seriesId.value}", it) }
  }

  /**
   * Lets store contention out, where every other failure is swallowed.
   *
   * "This item has no cover" and "ask again in a moment" are not the same answer, and only the
   * first one is permanent. Swallowing contention left media items with no artwork for good, with a
   * single `WARNING` as the only record - observed during a scan of 145,105 archives, whose write
   * lock made `DELETE FROM artwork_thumbnail` fail for a run of items.
   *
   * Propagating fails the metadata-refresh task that called this, which the worker then retries.
   * Analysis remains complete and the media item stays readable while enrichment is retried.
   */
  private fun rethrowIfBusy(error: Throwable) {
    if (isStoreBusy(error)) throw error
  }

  /**
   * Records a failure this class deliberately does not propagate.
   *
   * Swallowing without recording is how a library ends up with no artwork and nothing to explain
   * why - the caller's analysis succeeded, so no task is dead-lettered and no other trace is left.
   * Follows [DurableTaskWorker]'s decision on what to write: the message truncated, and the
   * [Throwable] itself deliberately not attached, so a stack trace cannot print an untruncated
   * message past that cap.
   */
  private fun logSwallowed(
    owner: String,
    error: Throwable,
  ) {
    val reason =
      error.message?.takeIf(String::isNotBlank)?.take(FAILURE_LOG_LIMIT)
        ?: error::class.simpleName
        ?: "cover generation failed"
    logger.log(Level.WARNING, "Left $owner without generated artwork: $reason")
  }

  private fun generate(bookId: BookId) {
    val book = books.findByIdOrNull(bookId)?.takeIf { it.deletedAtMillis == null } ?: return
    val analyzed =
      media.findByBookIdOrNull(bookId)?.takeIf { it.status == MediaStatus.READY } ?: return
    val opened = openCoverSource(book.mediaKind, bookId, analyzed) ?: return
    val bytes =
      try {
        opened.readBounded(ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES)
      } finally {
        opened.close()
      }
    artwork.replaceGenerated(ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, bookId.value), bytes)
  }

  /**
   * The bytes a cover should be generated from, for a media kind that has one.
   *
   * A comic or PDF uses its first page, exactly like the compatibility sweep's
   * [GenerateBookArtworkTaskHandler]. An EPUB uses the cover the OPF manifest declared - see
   * [EpubMediaAnalyzer] - and only that: an EPUB with no declared cover yields `null` here rather
   * than rendering one of its XHTML pages, which would need a browser engine this server does not
   * have.
   */
  private fun openCoverSource(
    mediaKind: MediaKind,
    bookId: BookId,
    analyzed: BookMedia,
  ): MediaContentStream? =
    when (mediaKind) {
      MediaKind.COMIC_ARCHIVE, MediaKind.PDF ->
        content.openPage(
          bookId,
          pageNumber = 1,
          request =
            PageImageRequest(
              format = PageImageFormat.JPEG,
              maximumDimension = maximumCoverDimension(),
            ),
        )
      MediaKind.EPUB ->
        analyzed.files
          .firstOrNull { it.kind == MediaFileKind.EPUB_COVER }
          ?.let { content.openResource(bookId, it.fileName) }
    }

  /**
   * Copies the first item's selected cover onto the series, deriving rather than duplicating where
   * it can: [ArtworkRepository] and [ArtworkOwner] have no reference/alias concept, an artwork
   * always owns its own stored bytes, so there is no way to point the series at the item's artwork
   * without a second copy. Storing the already-processed bytes again is the only option
   * [ArtworkLifecycle] offers, so that is what this does.
   */
  private fun refreshSeriesCover(seriesId: SeriesId) {
    val item =
      series.findByIdOrNull(seriesId)?.takeIf { it.deletedAtMillis == null && !it.oneshot }
        ?: return
    val firstBookId = firstBookInReadingOrder(seriesId) ?: return
    val cover =
      artwork.selectedContentOrNull(ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, firstBookId.value))
        ?: return
    artwork.replaceGenerated(ArtworkOwner(ArtworkOwnerKind.SERIES, seriesId.value), cover.bytes)
  }

  private fun firstBookInReadingOrder(seriesId: SeriesId): BookId? {
    val candidates = books.findAllBySeriesId(seriesId).filter { it.deletedAtMillis == null }
    if (candidates.isEmpty()) return null
    val numberSortByBookId =
      bookMetadata.findAllByBookIds(candidates.map { it.id }).associate { it.bookId to it.numberSort }
    return candidates.minByOrNull { numberSortByBookId[it.id] ?: Float.MAX_VALUE }?.id
  }

  private fun MediaContentStream.readBounded(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1_024)
    var total = 0
    while (true) {
      val read = read(buffer)
      if (read < 0) break
      if (read == 0) continue
      total += read
      require(total <= maximumBytes) { "Generated artwork exceeds the size limit" }
      output.write(buffer, 0, read)
    }
    return output.toByteArray()
  }

  companion object {
    private const val FAILURE_LOG_LIMIT = 500
    private val logger = Logger.getLogger(BookCoverGenerationLifecycle::class.java.name)
  }
}
