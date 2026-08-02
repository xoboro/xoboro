package io.xoboro.core.application

import io.xoboro.core.domain.PageHashMatch

/**
 * Executes a previously recorded duplicate-page delete decision.
 *
 * Split out of [CompatibilityMaintenanceRequester] so that a caller which only needs to execute a
 * removal - the native surface, in particular - does not have to depend on a type named
 * "Compatibility" to do it. The Komga-compatible surface keeps using the wider interface unchanged.
 */
fun interface DuplicatePageRemovalRequester {
  /**
   * Queues removal of every match in [matches] for the given [hash] and returns how many were
   * queued. The caller is responsible for having already established that [hash] carries a
   * recorded delete decision; this method removes whatever matches it is given.
   */
  fun deleteDuplicatePages(
    hash: String,
    matches: List<PageHashMatch>,
  ): Int
}
