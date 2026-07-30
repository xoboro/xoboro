package io.xoboro.core.application

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryAvailabilityProbeTest {
  @Test
  fun `clears an outage when the root is a readable directory`() {
    val repository = InMemoryLibraryRepository(library(unavailableAtMillis = 10))
    val probe = probe(repository, RootType.DIRECTORY, readable = true)

    assertNull(probe.probe(LIBRARY_ID)?.unavailableAtMillis)
    assertNull(repository.findById(LIBRARY_ID).unavailableAtMillis)
  }

  @Test
  fun `records an outage when the root is a directory it cannot read`() {
    // The case a root-type check alone gets wrong. Reporting available here would be contradicted
    // by the next scan, because SourceInventory requires a root that is both a directory and
    // readable - so the probe applies the same predicate.
    val repository = InMemoryLibraryRepository(library())
    val probe = probe(repository, RootType.DIRECTORY, readable = false)

    assertEquals(NOW, probe.probe(LIBRARY_ID)?.unavailableAtMillis)
  }

  @Test
  fun `records an outage when the root is missing or is a file`() {
    listOf(RootType.MISSING, RootType.FILE).forEach { rootType ->
      val repository = InMemoryLibraryRepository(library())
      val probe = probe(repository, rootType, readable = true)

      assertEquals(NOW, probe.probe(LIBRARY_ID)?.unavailableAtMillis, "root type $rootType")
    }
  }

  @Test
  fun `treats an uninstalled source adapter as unavailable`() {
    // RoutingLibraryRootAccess throws for a source id it has no inspector for. An operator asking
    // "is this storage reachable?" is owed the answer no, not a propagated failure - the storage
    // genuinely cannot be read.
    val repository = InMemoryLibraryRepository(library())
    val probe =
      LibraryAvailabilityProbe(
        libraries = repository,
        rootAccess = RoutingLibraryRootAccess(emptyList()),
        availability = availability(repository),
      )

    assertEquals(NOW, probe.probe(LIBRARY_ID)?.unavailableAtMillis)
  }

  @Test
  fun `reports nothing for a library that does not exist`() {
    val repository = InMemoryLibraryRepository()

    assertNull(probe(repository, RootType.DIRECTORY, readable = true).probe(LIBRARY_ID))
  }

  @Test
  fun `keeps the first outage timestamp across repeated probes`() {
    val repository = InMemoryLibraryRepository(library())
    val probe = probe(repository, RootType.MISSING, readable = false)

    assertEquals(NOW, probe.probe(LIBRARY_ID)?.unavailableAtMillis)
    assertEquals(NOW, probe.probe(LIBRARY_ID)?.unavailableAtMillis)
    assertTrue(repository.updates <= 1, "a repeated probe must not rewrite the outage timestamp")
  }

  private fun probe(
    repository: InMemoryLibraryRepository,
    rootType: RootType,
    readable: Boolean,
  ): LibraryAvailabilityProbe =
    LibraryAvailabilityProbe(
      libraries = repository,
      rootAccess = FixedRootAccess(rootType, readable),
      availability = availability(repository),
    )

  private fun availability(repository: LibraryRepository): LibraryAvailabilityLifecycle =
    LibraryAvailabilityLifecycle(
      libraries = repository,
      currentTimeMillis = { NOW },
    )

  private fun library(unavailableAtMillis: Long? = null): Library =
    Library(
      id = LIBRARY_ID,
      name = "Synthetic library",
      root = SourceLocation("synthetic", "synthetic://root"),
      unavailableAtMillis = unavailableAtMillis,
      createdAtMillis = 10,
    )

  private class FixedRootAccess(
    private val rootType: RootType,
    private val readable: Boolean,
  ) : LibraryRootAccess {
    override fun typeOf(root: SourceLocation): RootType = rootType

    override fun isReadable(root: SourceLocation): Boolean = readable

    override fun isSameOrAncestor(
      possibleAncestor: SourceLocation,
      possibleDescendant: SourceLocation,
    ): Boolean = possibleAncestor == possibleDescendant
  }

  private class InMemoryLibraryRepository(
    vararg initial: Library,
  ) : LibraryRepository {
    private val items = initial.associateByTo(linkedMapOf(), Library::id)
    var updates: Int = 0
      private set

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
      updates += 1
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
    private const val NOW = 20L
    private val LIBRARY_ID = LibraryId("library-1")
  }
}
