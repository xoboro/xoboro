package io.xoboro.core.application

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository

class LibraryAvailabilityLifecycle(
  private val libraries: LibraryRepository,
  private val currentTimeMillis: () -> Long,
  private val eventPublisher: LibraryEventPublisher = LibraryEventPublisher {},
) {
  fun markUnavailable(id: LibraryId): Library? =
    update(id) { library, now ->
      if (library.unavailableAtMillis != null) {
        library
      } else {
        library.copy(
          unavailableAtMillis = now,
          updatedAtMillis = now,
        )
      }
    }

  fun markAvailable(id: LibraryId): Library? =
    update(id) { library, now ->
      if (library.unavailableAtMillis == null) {
        library
      } else {
        library.copy(
          unavailableAtMillis = null,
          updatedAtMillis = now,
        )
      }
    }

  private fun update(
    id: LibraryId,
    transition: (Library, Long) -> Library,
  ): Library? {
    val current = libraries.findByIdOrNull(id) ?: return null
    val now =
      currentTimeMillis().also {
        require(it >= current.updatedAtMillis) {
          "Availability timestamp must not precede the current library update"
        }
      }
    val updated = transition(current, now)
    if (updated != current) {
      libraries.update(updated)
      eventPublisher.publish(LibraryEvent.Updated(updated))
    }
    return updated
  }
}
