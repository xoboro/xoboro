package io.xoboro.core.application

import io.xoboro.core.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutingLibraryRootAccessTest {
  @Test
  fun `routes operations to the matching source inspector`() {
    val local = RecordingInspector("local", RootType.DIRECTORY)
    val remote = RecordingInspector("remote", RootType.FILE)
    val access = RoutingLibraryRootAccess(listOf(local, remote))

    assertEquals(
      RootType.FILE,
      access.typeOf(SourceLocation(sourceId = "remote", itemId = "remote-item")),
    )
    assertTrue(
      access.isSameOrAncestor(
        SourceLocation(sourceId = "local", itemId = "parent"),
        SourceLocation(sourceId = "local", itemId = "parent/child"),
      ),
    )
    assertEquals(listOf("remote-item"), remote.inspectedItems)
    assertEquals(listOf("parent" to "parent/child"), local.comparisons)
  }

  @Test
  fun `different sources can never have an ancestry relationship`() {
    val access = RoutingLibraryRootAccess(listOf(RecordingInspector("local", RootType.DIRECTORY)))

    assertFalse(
      access.isSameOrAncestor(
        SourceLocation(sourceId = "local", itemId = "same"),
        SourceLocation(sourceId = "remote", itemId = "same"),
      ),
    )
  }

  @Test
  fun `rejects unknown blank and duplicate source registrations`() {
    val access = RoutingLibraryRootAccess(listOf(RecordingInspector("local", RootType.DIRECTORY)))
    assertFailsWith<UnknownLibrarySourceException> {
      access.typeOf(SourceLocation(sourceId = "remote", itemId = "item"))
    }
    assertFailsWith<IllegalArgumentException> {
      RoutingLibraryRootAccess(listOf(RecordingInspector("", RootType.DIRECTORY)))
    }
    assertFailsWith<IllegalArgumentException> {
      RoutingLibraryRootAccess(
        listOf(
          RecordingInspector("local", RootType.DIRECTORY),
          RecordingInspector("local", RootType.DIRECTORY),
        ),
      )
    }
  }

  private class RecordingInspector(
    override val sourceId: String,
    private val rootType: RootType,
  ) : LibraryRootInspector {
    val inspectedItems = mutableListOf<String>()
    val comparisons = mutableListOf<Pair<String, String>>()

    override fun typeOf(itemId: String): RootType {
      inspectedItems += itemId
      return rootType
    }

    override fun isSameOrAncestor(
      possibleAncestorItemId: String,
      possibleDescendantItemId: String,
    ): Boolean {
      comparisons += possibleAncestorItemId to possibleDescendantItemId
      return possibleDescendantItemId.startsWith(possibleAncestorItemId)
    }
  }
}
