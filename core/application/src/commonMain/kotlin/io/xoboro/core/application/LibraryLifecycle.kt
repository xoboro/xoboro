package io.xoboro.core.application

import io.xoboro.core.domain.DuplicateLibraryNameException
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.LibraryRootMissingException
import io.xoboro.core.domain.LibraryRootNotDirectoryException
import io.xoboro.core.domain.OverlappingLibraryRootException
import io.xoboro.core.domain.SourceLocation

enum class RootType {
  MISSING,
  FILE,
  DIRECTORY,
}

interface LibraryRootAccess {
  fun typeOf(root: SourceLocation): RootType

  /**
   * Whether [root] can currently be read, which [typeOf] deliberately does not answer: a directory
   * that exists but is not readable still reports [RootType.DIRECTORY].
   *
   * [LibraryAvailabilityProbe] needs both, because what decides availability is whether the
   * inventory could start, and `SourceInventory` implementations require a root that is both a
   * directory and readable.
   */
  fun isReadable(root: SourceLocation): Boolean

  fun isSameOrAncestor(
    possibleAncestor: SourceLocation,
    possibleDescendant: SourceLocation,
  ): Boolean
}

interface LibraryRootInspector {
  val sourceId: String

  fun typeOf(itemId: String): RootType

  /** Per-source half of [LibraryRootAccess.isReadable]. */
  fun isReadable(itemId: String): Boolean

  fun isSameOrAncestor(
    possibleAncestorItemId: String,
    possibleDescendantItemId: String,
  ): Boolean
}

class UnknownLibrarySourceException(
  sourceId: String,
) : IllegalArgumentException("Unknown library source: $sourceId")

class RoutingLibraryRootAccess(
  inspectors: Collection<LibraryRootInspector>,
) : LibraryRootAccess {
  private val inspectorsBySourceId = inspectors.associateBy(LibraryRootInspector::sourceId)

  init {
    require(inspectors.none { it.sourceId.isBlank() }) { "Root inspector source IDs must not be blank" }
    require(inspectorsBySourceId.size == inspectors.size) {
      "Root inspector source IDs must be unique"
    }
  }

  override fun typeOf(root: SourceLocation): RootType =
    inspector(root.sourceId).typeOf(root.itemId)

  override fun isReadable(root: SourceLocation): Boolean =
    inspector(root.sourceId).isReadable(root.itemId)

  override fun isSameOrAncestor(
    possibleAncestor: SourceLocation,
    possibleDescendant: SourceLocation,
  ): Boolean {
    if (possibleAncestor.sourceId != possibleDescendant.sourceId) return false
    return inspector(possibleAncestor.sourceId)
      .isSameOrAncestor(possibleAncestor.itemId, possibleDescendant.itemId)
  }

  private fun inspector(sourceId: String): LibraryRootInspector =
    inspectorsBySourceId[sourceId] ?: throw UnknownLibrarySourceException(sourceId)
}

interface LibraryMaintenanceQueue {
  fun scanLibrary(id: LibraryId)

  fun reschedulePeriodicScan(library: Library)

  fun hashBooksWithoutFileHash(id: LibraryId)

  fun hashBooksWithoutKoreaderHash(id: LibraryId)

  fun hashBooksWithMissingPageHash(id: LibraryId)

  fun repairExtensions(id: LibraryId)

  fun convertBooksToCbz(id: LibraryId)
}

sealed interface LibraryEvent {
  val library: Library

  data class Added(
    override val library: Library,
  ) : LibraryEvent

  data class Updated(
    override val library: Library,
  ) : LibraryEvent

  data class Deleted(
    override val library: Library,
  ) : LibraryEvent
}

fun interface LibraryEventPublisher {
  fun publish(event: LibraryEvent)
}

class LibraryLifecycle(
  private val repository: LibraryRepository,
  private val rootAccess: LibraryRootAccess,
  private val maintenanceQueue: LibraryMaintenanceQueue,
  private val eventPublisher: LibraryEventPublisher,
) {
  fun add(library: Library): Library {
    validate(library, repository.findAll())
    repository.insert(library)
    maintenanceQueue.scanLibrary(library.id)
    eventPublisher.publish(LibraryEvent.Added(library))
    return repository.findById(library.id)
  }

  fun update(library: Library) {
    val existing = repository.findById(library.id)
    validate(
      candidate = library,
      existing = repository.findAll().filterNot { it.id == library.id },
    )

    repository.update(library)
    enqueueMaintenanceForChanges(existing, library)
    eventPublisher.publish(LibraryEvent.Updated(library))
  }

  fun delete(id: LibraryId) {
    val existing = repository.findById(id)
    repository.delete(id)
    eventPublisher.publish(LibraryEvent.Deleted(existing))
  }

  private fun validate(
    candidate: Library,
    existing: Collection<Library>,
  ) {
    when (rootAccess.typeOf(candidate.root)) {
      RootType.MISSING -> throw LibraryRootMissingException(candidate.root)
      RootType.FILE -> throw LibraryRootNotDirectoryException(candidate.root)
      RootType.DIRECTORY -> Unit
    }

    if (existing.any { it.name == candidate.name }) {
      throw DuplicateLibraryNameException(candidate.name)
    }

    existing
      .filter { it.root.sourceId == candidate.root.sourceId }
      .firstOrNull { library ->
        rootAccess.isSameOrAncestor(library.root, candidate.root) ||
          rootAccess.isSameOrAncestor(candidate.root, library.root)
      }
      ?.let { throw OverlappingLibraryRootException(candidate.root, it) }
  }

  private fun enqueueMaintenanceForChanges(
    existing: Library,
    updated: Library,
  ) {
    if (existing.settings.scanInterval != updated.settings.scanInterval) {
      maintenanceQueue.reschedulePeriodicScan(updated)
    }
    if (existing.shouldRescanAfter(updated)) {
      maintenanceQueue.scanLibrary(updated.id)
    }
    if (updated.settings.hashFiles && !existing.settings.hashFiles) {
      maintenanceQueue.hashBooksWithoutFileHash(updated.id)
    }
    if (updated.settings.hashKoreader && !existing.settings.hashKoreader) {
      maintenanceQueue.hashBooksWithoutKoreaderHash(updated.id)
    }
    if (updated.settings.hashPages && !existing.settings.hashPages) {
      maintenanceQueue.hashBooksWithMissingPageHash(updated.id)
    }
    if (updated.settings.repairExtensions && !existing.settings.repairExtensions) {
      maintenanceQueue.repairExtensions(updated.id)
    }
    if (updated.settings.convertToCbz && !existing.settings.convertToCbz) {
      maintenanceQueue.convertBooksToCbz(updated.id)
    }
  }

  private fun Library.shouldRescanAfter(updated: Library): Boolean =
    root != updated.root ||
      settings.oneshotsDirectory != updated.settings.oneshotsDirectory ||
      settings.scanCbx != updated.settings.scanCbx ||
      settings.scanPdf != updated.settings.scanPdf ||
      settings.scanEpub != updated.settings.scanEpub ||
      settings.scanForceModifiedTime != updated.settings.scanForceModifiedTime ||
      settings.scanDirectoryExclusions != updated.settings.scanDirectoryExclusions
}
