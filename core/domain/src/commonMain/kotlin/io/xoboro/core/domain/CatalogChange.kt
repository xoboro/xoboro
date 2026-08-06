package io.xoboro.core.domain

/** What kind of thing a [CatalogChange] is about. */
enum class CatalogChangeEntityKind {
  MEDIA_ITEM,
  SERIES,
}

/** What happened to it. */
enum class CatalogChangeMutation {
  ADDED,
  UPDATED,
  DELETED,
}

/**
 * One catalogue mutation, durable and numbered.
 *
 * [sequence] is the cursor. It only ever goes up, so "everything after N" is a range scan and never
 * depends on a clock, on two writers agreeing about the time, or on a tiebreak between events in the
 * same millisecond. [occurredAtMillis] is for showing a reader when something happened and is never
 * used to order or to page.
 */
data class CatalogChange(
  val sequence: Long,
  val entityKind: CatalogChangeEntityKind,
  val entityId: String,
  val mutation: CatalogChangeMutation,
  val libraryId: LibraryId,
  val occurredAtMillis: Long,
) {
  init {
    require(sequence > 0) { "Catalog change sequence must be positive" }
    require(entityId.isNotBlank()) { "Catalog change entity ID must not be blank" }
    require(occurredAtMillis >= 0) { "Catalog change timestamp must not be negative" }
  }
}

/**
 * An answer to "what changed after my cursor".
 *
 * [resyncRequired] is the field that makes the feed safe to build a local copy on. An empty [changes]
 * list otherwise has two meanings a client cannot separate - nothing changed, or what changed was
 * swept by retention before it asked - and the second silently loses deletions, leaving rows for items
 * that no longer exist with no way to find out. When it is set the client must discard its copy and
 * read the catalogue again; nothing else it can do is correct.
 *
 * [nextCursor] is the sequence to pass next time. It is the last row's sequence, or the requested
 * cursor unchanged when there was nothing after it - so an idle client's cursor does not drift.
 */
data class CatalogChangePage(
  val changes: List<CatalogChange>,
  val nextCursor: Long,
  /** The highest sequence retention has removed through; a cursor at or below it cannot be served. */
  val floorSequence: Long,
  val resyncRequired: Boolean,
) {
  init {
    require(nextCursor >= 0) { "Catalog change cursor must not be negative" }
    require(floorSequence >= 0) { "Catalog change floor must not be negative" }
    require(!resyncRequired || changes.isEmpty()) {
      "A page that requires a resync must not also carry changes to apply"
    }
  }
}

interface CatalogChangeRepository {
  /**
   * Reads changes after [afterSequence] for [libraryIds], oldest first.
   *
   * [libraryIds] null means every library, matching `CatalogAccess`; an empty set means none are
   * visible, which is a real answer of "nothing" rather than a request for everything. Conflating the
   * two would show a reader with no granted library the whole catalogue's history.
   *
   * Answers `resyncRequired` instead of changes when [afterSequence] is below the swept floor, because
   * anything between the two is gone and a partial answer would look complete.
   */
  fun findAfter(
    afterSequence: Long,
    libraryIds: Set<LibraryId>?,
    limit: Int,
  ): CatalogChangePage

  /**
   * Deletes changes at or below [throughSequence] and records that it did, in one transaction.
   *
   * Returns how many rows went. The watermark is what makes the deletion safe to observe: a sweep that
   * removed rows without raising the floor would leave a cursor pointing into a gap it cannot detect.
   */
  fun sweepThrough(throughSequence: Long): Int
}
