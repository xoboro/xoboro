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
        root = SourceLocation(sourceId = "local", itemId = "file:///synthetic/comics"),
        createdAtMillis = 1L,
      )

    assertEquals("library-1", library.id.value)
    assertEquals("local", library.root.sourceId)
    assertEquals(ScanInterval.EVERY_6H, library.settings.scanInterval)
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

  @Test
  fun `invalid timestamps and blank settings are rejected`() {
    assertFailsWith<IllegalArgumentException> {
      Library(
        id = LibraryId("library-1"),
        name = "Comics",
        root = SourceLocation(sourceId = "local", itemId = "file:///synthetic/comics"),
        createdAtMillis = 2L,
        updatedAtMillis = 1L,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      LibrarySettings(scanDirectoryExclusions = setOf(""))
    }
    assertFailsWith<IllegalArgumentException> {
      LibrarySettings(oneshotsDirectory = " ")
    }
  }
}
