package io.xoboro.core.application

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.SourceLocation

/**
 * Re-checks whether a library's storage is reachable and records the answer.
 *
 * Until this existed, [LibraryAvailabilityLifecycle] was only ever driven by `ScanLibraryTask`, so
 * a library marked unavailable stayed that way until a full scan happened to succeed. That is the
 * wrong granularity for two operator questions: "is the mount back?" and "can I delete this library
 * normally again?" - a scan of a large library is expensive, and its failure path is what set the
 * flag in the first place.
 *
 * The predicate is deliberately the same one `SourceInventory` implementations apply before walking
 * a root - a readable directory - so a probe that reports available cannot be immediately
 * contradicted by the next scan marking it unavailable again.
 *
 * An unknown source id (an adapter that is no longer installed) counts as unavailable rather than
 * propagating [UnknownLibrarySourceException]: the storage genuinely cannot be read, and an operator
 * asking "is this reachable?" is owed an answer rather than a 500.
 */
class LibraryAvailabilityProbe(
  private val libraries: LibraryRepository,
  private val rootAccess: LibraryRootAccess,
  private val availability: LibraryAvailabilityLifecycle,
) {
  /** Returns the library with its refreshed availability, or null when [id] does not exist. */
  fun probe(id: LibraryId): Library? {
    val library = libraries.findByIdOrNull(id) ?: return null
    return if (library.root.isReachable()) {
      availability.markAvailable(id)
    } else {
      availability.markUnavailable(id)
    }
  }

  private fun SourceLocation.isReachable(): Boolean =
    try {
      rootAccess.typeOf(this) == RootType.DIRECTORY && rootAccess.isReadable(this)
    } catch (failure: UnknownLibrarySourceException) {
      false
    }
}
