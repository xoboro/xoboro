package io.xoboro.core.application

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.SourceLocation

class LibraryAdministrationLifecycle(
  private val libraries: LibraryRepository,
  private val lifecycle: LibraryLifecycle,
  private val libraryIdFactory: () -> String,
  private val currentTimeMillis: () -> Long,
) {
  fun findAll(): List<Library> = libraries.findAll()

  fun findByIdOrNull(id: LibraryId): Library? = libraries.findByIdOrNull(id)

  fun create(
    name: String,
    root: SourceLocation,
    settings: LibrarySettings,
  ): Library {
    val now = now()
    return lifecycle.add(
      Library(
        id =
          LibraryId(
            libraryIdFactory().also {
              require(it.isNotBlank()) { "Generated library ID must not be blank" }
            },
          ),
        name = name,
        root = root,
        settings = settings,
        createdAtMillis = now,
      ),
    )
  }

  fun update(
    id: LibraryId,
    transform: (Library) -> Library,
  ): Library? {
    val existing = libraries.findByIdOrNull(id) ?: return null
    val updated =
      transform(existing).copy(
        id = id,
        createdAtMillis = existing.createdAtMillis,
        updatedAtMillis = now(),
      )
    lifecycle.update(updated)
    return libraries.findById(id)
  }

  fun delete(id: LibraryId): Boolean {
    if (libraries.findByIdOrNull(id) == null) return false
    lifecycle.delete(id)
    return true
  }

  private fun now(): Long =
    currentTimeMillis().also {
      require(it >= 0) { "Library administration timestamp must not be negative" }
    }
}
