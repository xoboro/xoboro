package io.xoboro.core.application

/**
 * What one batch of the series aggregation sweep did.
 *
 * The sweep used to answer a single boolean — "is there more" — and throw away the one thing
 * anybody downstream needed: **which** series it rebuilt. Authors and tags on a series come out of
 * that aggregation, so until it runs the series reads as having none; a screen loaded in that
 * window shows a work with no author while the route it asked would now answer with two. Measured
 * on a running server rather than reasoned about.
 *
 * Nothing here says how the rebuild should be announced. That is the scheduler's decision, because
 * it is the only place that sees a whole tick: the batches are 500 rows each and up to four run per
 * minute, so announcing per batch would announce the same series twice as often as it changed.
 *
 * @property rebuilt one event per series rebuilt, ready to publish. They are
 *   [CatalogMutationEvent.Series] rather than bare ids because a subscriber's access is decided by
 *   `libraryId`, so an id alone cannot be delivered to anyone.
 * @property moreRemaining whether dirty rows are still waiting. A sweep that could not take the
 *   write lock reports `true` with nothing rebuilt, which is the same answer it has always given:
 *   the backlog is still there, and the next caller can try.
 */
data class SeriesAggregationSweep(
  val rebuilt: List<CatalogMutationEvent.Series>,
  val moreRemaining: Boolean,
) {
  init {
    require(rebuilt.all { it.kind == CatalogMutationKind.UPDATED }) {
      "A rebuilt aggregation is an update; a sweep neither creates nor deletes a series"
    }
  }
}
