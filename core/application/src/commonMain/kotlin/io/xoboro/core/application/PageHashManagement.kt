package io.xoboro.core.application

import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.PageHashMatch
import io.xoboro.core.domain.UnknownPageHash

interface PageHashRepository {
  fun findKnownOrNull(hash: String): KnownPageHash?

  fun findKnown(
    actions: Set<PageHashAction>,
    page: CatalogPageRequest,
  ): CatalogPage<KnownPageHash>

  fun findUnknown(page: CatalogPageRequest): CatalogPage<UnknownPageHash>

  fun findMatches(
    hash: String,
    page: CatalogPageRequest,
  ): CatalogPage<PageHashMatch>

  fun upsert(known: KnownPageHash)

  fun incrementDeleteCount(
    hash: String,
    count: Int,
  ) {
    error("Page hash delete-count updates are not supported")
  }
}

class PageHashLifecycle(
  private val hashes: PageHashRepository,
  private val currentTimeMillis: () -> Long,
) {
  fun markKnown(
    hash: String,
    size: Long?,
    action: PageHashAction,
  ): KnownPageHash {
    require(hash.isNotBlank()) { "Page hash must not be blank" }
    require(size == null || size >= 0) { "Page hash size must not be negative" }
    val now = currentTimeMillis()
    require(now >= 0) { "Page hash timestamp must not be negative" }
    val current = hashes.findKnownOrNull(hash)
    val known =
      KnownPageHash(
        hash = hash,
        size = size,
        action = action,
        deleteCount = current?.deleteCount ?: 0,
        matchCount = current?.matchCount ?: 0,
        createdAtMillis = current?.createdAtMillis ?: now,
        updatedAtMillis = now,
      )
    hashes.upsert(known)
    return requireNotNull(hashes.findKnownOrNull(hash))
  }
}
