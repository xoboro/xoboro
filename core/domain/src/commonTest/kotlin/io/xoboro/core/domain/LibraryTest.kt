package io.xoboro.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryTest {
  @Test
  fun `library keeps a source-scoped identity`() {
    val library =
      Library(
        id = LibraryId("library-1"),
        name = "Comics",
        source = SourceLocation(sourceId = "local", itemId = "/media/comics"),
      )

    assertEquals("library-1", library.id.value)
    assertEquals("local", library.source.sourceId)
  }

  @Test
  fun `blank identifiers are rejected`() {
    assertFailsWith<IllegalArgumentException> {
      LibraryId(" ")
    }
    assertFailsWith<IllegalArgumentException> {
      SourceLocation(sourceId = "", itemId = "/media/comics")
    }
  }
}

