package io.xoboro.core.application

import io.xoboro.core.domain.DuplicateLibraryNameException
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.LibraryRootMissingException
import io.xoboro.core.domain.LibraryRootNotDirectoryException
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.OverlappingLibraryRootException
import io.xoboro.core.domain.ScanInterval
import io.xoboro.core.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryLifecycleTest {
  @Test
  fun `adds a valid library then queues its first scan and event`() {
    val fixture = Fixture()
    val library = libraryFixture()

    val result = fixture.lifecycle.add(library)

    assertEquals(library, result)
    assertEquals(library, fixture.repository.findById(library.id))
    assertEquals(
      listOf<Maintenance>(Maintenance.Scan(library.id)),
      fixture.maintenance.commands,
    )
    assertEquals(listOf<LibraryEvent>(LibraryEvent.Added(library)), fixture.events)
  }

  @Test
  fun `rejects missing and non-directory roots before persistence`() {
    val missing = Fixture(rootType = RootType.MISSING)
    assertFailsWith<LibraryRootMissingException> {
      missing.lifecycle.add(libraryFixture())
    }
    val file = Fixture(rootType = RootType.FILE)
    assertFailsWith<LibraryRootNotDirectoryException> {
      file.lifecycle.add(libraryFixture())
    }

    assertEquals(0L, missing.repository.count())
    assertEquals(0L, file.repository.count())
    assertTrue(missing.maintenance.commands.isEmpty())
    assertTrue(file.maintenance.commands.isEmpty())
  }

  @Test
  fun `rejects duplicate names and parent or child roots on the same source`() {
    val fixture = Fixture()
    fixture.repository.insert(libraryFixture(name = "Existing", rootItem = "/catalog"))

    assertFailsWith<DuplicateLibraryNameException> {
      fixture.lifecycle.add(
        libraryFixture(id = "duplicate", name = "Existing", rootItem = "/other"),
      )
    }
    assertFailsWith<OverlappingLibraryRootException> {
      fixture.lifecycle.add(
        libraryFixture(id = "child", name = "Child", rootItem = "/catalog/child"),
      )
    }
    assertFailsWith<OverlappingLibraryRootException> {
      fixture.lifecycle.add(
        libraryFixture(id = "parent", name = "Parent", rootItem = "/"),
      )
    }

    assertEquals(1L, fixture.repository.count())
  }

  @Test
  fun `allows equivalent paths from independent sources`() {
    val fixture = Fixture()
    fixture.repository.insert(libraryFixture(rootItem = "/catalog"))
    val remote =
      libraryFixture(
        id = "remote",
        name = "Remote",
        sourceId = "remote-source",
        rootItem = "/catalog",
      )

    fixture.lifecycle.add(remote)

    assertEquals(2L, fixture.repository.count())
  }

  @Test
  fun `updates the library and queues every newly enabled maintenance operation`() {
    val fixture = Fixture()
    val original =
      libraryFixture(
        settings =
          LibrarySettings(
            hashFiles = false,
            scanCbx = false,
          ),
      )
    fixture.repository.insert(original)
    val updated =
      original.copy(
        root = original.root.copy(itemId = "/updated"),
        settings =
          original.settings.copy(
            scanInterval = ScanInterval.DAILY,
            scanCbx = true,
            hashFiles = true,
            hashKoreader = true,
            hashPages = true,
            repairExtensions = true,
            convertToCbz = true,
          ),
        updatedAtMillis = 2L,
      )

    fixture.lifecycle.update(updated)

    assertEquals(updated, fixture.repository.findById(updated.id))
    assertEquals(
      listOf(
        Maintenance.Reschedule(updated),
        Maintenance.Scan(updated.id),
        Maintenance.HashFiles(updated.id),
        Maintenance.HashKoreader(updated.id),
        Maintenance.HashPages(updated.id),
        Maintenance.RepairExtensions(updated.id),
        Maintenance.ConvertToCbz(updated.id),
      ),
      fixture.maintenance.commands,
    )
    assertEquals(listOf<LibraryEvent>(LibraryEvent.Updated(updated)), fixture.events)
  }

  @Test
  fun `does not enqueue maintenance for presentation-only changes`() {
    val fixture = Fixture()
    val original = libraryFixture()
    fixture.repository.insert(original)

    fixture.lifecycle.update(
      original.copy(name = "Renamed", updatedAtMillis = 2L),
    )

    assertTrue(fixture.maintenance.commands.isEmpty())
  }

  @Test
  fun `deletes an existing library and publishes its previous value`() {
    val fixture = Fixture()
    val library = libraryFixture()
    fixture.repository.insert(library)

    fixture.lifecycle.delete(library.id)

    assertEquals(0L, fixture.repository.count())
    assertEquals(listOf<LibraryEvent>(LibraryEvent.Deleted(library)), fixture.events)
  }

  private class Fixture(
    rootType: RootType = RootType.DIRECTORY,
  ) {
    val repository = InMemoryLibraryRepository()
    val rootAccess = FakeRootAccess(rootType)
    val maintenance = RecordingMaintenanceQueue()
    val events = mutableListOf<LibraryEvent>()
    val lifecycle =
      LibraryLifecycle(
        repository = repository,
        rootAccess = rootAccess,
        maintenanceQueue = maintenance,
        eventPublisher = LibraryEventPublisher(events::add),
      )
  }

  private class FakeRootAccess(
    private val rootType: RootType,
  ) : LibraryRootAccess {
    override fun typeOf(root: SourceLocation): RootType = rootType

    override fun isSameOrAncestor(
      possibleAncestor: SourceLocation,
      possibleDescendant: SourceLocation,
    ): Boolean {
      if (possibleAncestor.sourceId != possibleDescendant.sourceId) return false
      val ancestor = possibleAncestor.itemId.trimEnd('/')
      val descendant = possibleDescendant.itemId.trimEnd('/')
      return descendant == ancestor || descendant.startsWith("$ancestor/")
    }
  }

  private class InMemoryLibraryRepository : LibraryRepository {
    private val libraries = linkedMapOf<LibraryId, Library>()

    override fun findById(id: LibraryId): Library =
      findByIdOrNull(id) ?: throw NoSuchElementException("Library not found: ${id.value}")

    override fun findByIdOrNull(id: LibraryId): Library? = libraries[id]

    override fun findAll(): List<Library> = libraries.values.toList()

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> =
      ids.mapNotNull(libraries::get)

    override fun insert(library: Library) {
      check(libraries.putIfAbsent(library.id, library) == null)
    }

    override fun update(library: Library) {
      check(libraries.replace(library.id, library) != null)
    }

    override fun delete(id: LibraryId) {
      libraries.remove(id)
    }

    override fun deleteAll() {
      libraries.clear()
    }

    override fun count(): Long = libraries.size.toLong()
  }

  private sealed interface Maintenance {
    data class Scan(
      val id: LibraryId,
    ) : Maintenance

    data class Reschedule(
      val library: Library,
    ) : Maintenance

    data class HashFiles(
      val id: LibraryId,
    ) : Maintenance

    data class HashKoreader(
      val id: LibraryId,
    ) : Maintenance

    data class HashPages(
      val id: LibraryId,
    ) : Maintenance

    data class RepairExtensions(
      val id: LibraryId,
    ) : Maintenance

    data class ConvertToCbz(
      val id: LibraryId,
    ) : Maintenance
  }

  private class RecordingMaintenanceQueue : LibraryMaintenanceQueue {
    val commands = mutableListOf<Maintenance>()

    override fun scanLibrary(id: LibraryId) {
      commands += Maintenance.Scan(id)
    }

    override fun reschedulePeriodicScan(library: Library) {
      commands += Maintenance.Reschedule(library)
    }

    override fun hashBooksWithoutFileHash(id: LibraryId) {
      commands += Maintenance.HashFiles(id)
    }

    override fun hashBooksWithoutKoreaderHash(id: LibraryId) {
      commands += Maintenance.HashKoreader(id)
    }

    override fun hashBooksWithMissingPageHash(id: LibraryId) {
      commands += Maintenance.HashPages(id)
    }

    override fun repairExtensions(id: LibraryId) {
      commands += Maintenance.RepairExtensions(id)
    }

    override fun convertBooksToCbz(id: LibraryId) {
      commands += Maintenance.ConvertToCbz(id)
    }
  }

  private fun libraryFixture(
    id: String = "library-1",
    name: String = "Synthetic library",
    sourceId: String = "local",
    rootItem: String = "/catalog",
    settings: LibrarySettings = LibrarySettings(),
  ): Library =
    Library(
      id = LibraryId(id),
      name = name,
      root = SourceLocation(sourceId = sourceId, itemId = rootItem),
      settings = settings,
      createdAtMillis = 1L,
    )
}
