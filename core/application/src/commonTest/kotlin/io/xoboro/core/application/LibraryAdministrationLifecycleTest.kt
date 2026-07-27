package io.xoboro.core.application

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryAdministrationLifecycleTest {
  @Test
  fun `creates updates and deletes through the validated lifecycle`() {
    val repository = InMemoryLibraryRepository()
    val maintenance = RecordingMaintenanceQueue()
    val events = mutableListOf<LibraryEvent>()
    var now = 1_000L
    val administration =
      LibraryAdministrationLifecycle(
        libraries = repository,
        lifecycle =
          LibraryLifecycle(
            repository = repository,
            rootAccess = DirectoryRootAccess,
            maintenanceQueue = maintenance,
            eventPublisher = events::add,
          ),
        libraryIdFactory = { "library-1" },
        currentTimeMillis = { now },
      )

    val created =
      administration.create(
        name = "Synthetic library",
        root = SourceLocation("synthetic", "root-1"),
        settings = LibrarySettings(scanOnStartup = true),
      )
    assertEquals(LibraryId("library-1"), created.id)
    assertEquals(1_000, created.createdAtMillis)
    assertEquals(listOf(created.id), maintenance.scans)
    assertTrue(events.single() is LibraryEvent.Added)

    now = 2_000
    val updated =
      administration.update(created.id) {
        it.copy(
          name = "Renamed synthetic library",
          settings = it.settings.copy(scanInterval = io.xoboro.core.domain.ScanInterval.DAILY),
        )
      }
    assertEquals("Renamed synthetic library", updated?.name)
    assertEquals(1_000, updated?.createdAtMillis)
    assertEquals(2_000, updated?.updatedAtMillis)
    assertEquals(listOf(created.id), maintenance.rescheduled)

    assertTrue(administration.delete(created.id))
    assertNull(administration.findByIdOrNull(created.id))
    assertEquals(false, administration.delete(created.id))
    assertTrue(events.last() is LibraryEvent.Deleted)
  }

  private object DirectoryRootAccess : LibraryRootAccess {
    override fun typeOf(root: SourceLocation): RootType = RootType.DIRECTORY

    override fun isSameOrAncestor(
      possibleAncestor: SourceLocation,
      possibleDescendant: SourceLocation,
    ): Boolean = possibleDescendant.itemId.startsWith("${possibleAncestor.itemId}/")
  }

  private class RecordingMaintenanceQueue : LibraryMaintenanceQueue {
    val scans = mutableListOf<LibraryId>()
    val rescheduled = mutableListOf<LibraryId>()

    override fun scanLibrary(id: LibraryId) {
      scans += id
    }

    override fun reschedulePeriodicScan(library: Library) {
      rescheduled += library.id
    }

    override fun hashBooksWithoutFileHash(id: LibraryId) = Unit

    override fun hashBooksWithoutKoreaderHash(id: LibraryId) = Unit

    override fun hashBooksWithMissingPageHash(id: LibraryId) = Unit

    override fun repairExtensions(id: LibraryId) = Unit

    override fun convertBooksToCbz(id: LibraryId) = Unit
  }

  private class InMemoryLibraryRepository : LibraryRepository {
    private val values = linkedMapOf<LibraryId, Library>()

    override fun findById(id: LibraryId): Library =
      requireNotNull(values[id])

    override fun findByIdOrNull(id: LibraryId): Library? = values[id]

    override fun findAll(): List<Library> = values.values.toList()

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> =
      ids.mapNotNull(values::get)

    override fun insert(library: Library) {
      values[library.id] = library
    }

    override fun update(library: Library) {
      values[library.id] = library
    }

    override fun delete(id: LibraryId) {
      values.remove(id)
    }

    override fun deleteAll() {
      values.clear()
    }

    override fun count(): Long = values.size.toLong()
  }
}
