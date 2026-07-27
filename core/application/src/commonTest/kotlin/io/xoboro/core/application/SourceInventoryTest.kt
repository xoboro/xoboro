package io.xoboro.core.application

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SourceInventoryTest {
  @Test
  fun `inventory values enforce portable invariants`() {
    assertFailsWith<IllegalArgumentException> {
      SourceFile(
        itemId = "item",
        relativePath = "book.cbz",
        name = "book.cbz",
        extension = "CBZ",
        size = 1L,
        modifiedAtMillis = 1L,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      SourceInventoryFailure(itemId = "item", reason = "")
    }
    assertFailsWith<IllegalArgumentException> {
      SourceInventorySummary(
        visitedDirectories = 0,
        emittedFiles = -1,
        skippedDirectories = 0,
        failedEntries = 0,
      )
    }
  }
}
