package io.xoboro.core.application

import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.PageHashMatch
import io.xoboro.core.domain.UnknownPageHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageHashLifecycleTest {
  @Test
  fun `creates and updates a known hash without resetting counters`() {
    val repository = InMemoryPageHashRepository()
    val lifecycle = PageHashLifecycle(repository) { 20 }

    lifecycle.markKnown("hash-1", 12, PageHashAction.DELETE_MANUAL)
    repository.value =
      requireNotNull(repository.value).copy(
        deleteCount = 3,
        matchCount = 4,
      )
    val updated = lifecycle.markKnown("hash-1", 12, PageHashAction.IGNORE)

    assertEquals(PageHashAction.IGNORE, updated.action)
    assertEquals(3, updated.deleteCount)
    assertEquals(4, updated.matchCount)
    assertEquals(20, updated.createdAtMillis)
    assertEquals(20, updated.updatedAtMillis)
  }

  @Test
  fun `rejects invalid known hash input`() {
    val lifecycle = PageHashLifecycle(InMemoryPageHashRepository()) { 1 }

    assertFailsWith<IllegalArgumentException> {
      lifecycle.markKnown("", null, PageHashAction.IGNORE)
    }
    assertFailsWith<IllegalArgumentException> {
      lifecycle.markKnown("hash-1", -1, PageHashAction.IGNORE)
    }
  }

  private class InMemoryPageHashRepository : PageHashRepository {
    var value: KnownPageHash? = null

    override fun findKnownOrNull(hash: String): KnownPageHash? = value?.takeIf { it.hash == hash }

    override fun findKnown(
      actions: Set<PageHashAction>,
      page: CatalogPageRequest,
    ): CatalogPage<KnownPageHash> = error("Not used")

    override fun findUnknown(page: CatalogPageRequest): CatalogPage<UnknownPageHash> =
      error("Not used")

    override fun findMatches(
      hash: String,
      page: CatalogPageRequest,
    ): CatalogPage<PageHashMatch> = error("Not used")

    override fun upsert(known: KnownPageHash) {
      value = known
    }
  }
}
