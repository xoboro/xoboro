package io.xoboro.core.application

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryAvailabilityLifecycleTest {
  @Test
  fun `marks the first outage once and clears it after recovery`() {
    val repository = InMemoryLibraryRepository(library())
    val events = mutableListOf<LibraryEvent>()
    var now = 20L
    val lifecycle =
      LibraryAvailabilityLifecycle(
        libraries = repository,
        currentTimeMillis = { now },
        eventPublisher = LibraryEventPublisher(events::add),
      )

    assertEquals(20, lifecycle.markUnavailable(LIBRARY_ID)?.unavailableAtMillis)
    now = 30
    assertEquals(20, lifecycle.markUnavailable(LIBRARY_ID)?.unavailableAtMillis)
    assertEquals(1, events.size)

    assertNull(lifecycle.markAvailable(LIBRARY_ID)?.unavailableAtMillis)
    assertNull(lifecycle.markAvailable(LIBRARY_ID)?.unavailableAtMillis)
    assertEquals(2, events.size)
    assertEquals(30, repository.findById(LIBRARY_ID).updatedAtMillis)
  }

  @Test
  fun `ignores a library removed while a scan was queued`() {
    val lifecycle =
      LibraryAvailabilityLifecycle(
        libraries = InMemoryLibraryRepository(),
        currentTimeMillis = { 20 },
      )

    assertNull(lifecycle.markUnavailable(LIBRARY_ID))
    assertNull(lifecycle.markAvailable(LIBRARY_ID))
  }

  private fun library(): Library =
    Library(
      id = LIBRARY_ID,
      name = "Synthetic library",
      root = SourceLocation("synthetic", "synthetic://root"),
      createdAtMillis = 10,
    )

  private class InMemoryLibraryRepository(
    vararg initial: Library,
  ) : io.xoboro.core.domain.LibraryRepository {
    private val items = initial.associateByTo(linkedMapOf(), Library::id)

    override fun findById(id: LibraryId): Library = requireNotNull(items[id])

    override fun findByIdOrNull(id: LibraryId): Library? = items[id]

    override fun findAll(): List<Library> = items.values.toList()

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> =
      ids.mapNotNull(items::get)

    override fun insert(library: Library) {
      check(items.putIfAbsent(library.id, library) == null)
    }

    override fun update(library: Library) {
      require(items.containsKey(library.id))
      items[library.id] = library
    }

    override fun delete(id: LibraryId) {
      items.remove(id)
    }

    override fun deleteAll() {
      items.clear()
    }

    override fun count(): Long = items.size.toLong()
  }

  companion object {
    private val LIBRARY_ID = LibraryId("library-1")
  }
}
