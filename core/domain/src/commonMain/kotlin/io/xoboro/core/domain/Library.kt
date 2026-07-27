package io.xoboro.core.domain

data class LibraryId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Library ID must not be blank" }
  }
}

data class Library(
  val id: LibraryId,
  val name: String,
  val root: SourceLocation,
  val settings: LibrarySettings = LibrarySettings(),
  val unavailableAtMillis: Long? = null,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(name.isNotBlank()) { "Library name must not be blank" }
    require(createdAtMillis >= 0) { "Created timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Updated timestamp must not precede created timestamp"
    }
    require(unavailableAtMillis == null || unavailableAtMillis >= 0) {
      "Unavailable timestamp must not be negative"
    }
  }
}

data class LibrarySettings(
  val importComicInfoBook: Boolean = true,
  val importComicInfoSeries: Boolean = true,
  val importComicInfoCollection: Boolean = true,
  val importComicInfoReadList: Boolean = true,
  val importComicInfoSeriesAppendVolume: Boolean = true,
  val importEpubBook: Boolean = true,
  val importEpubSeries: Boolean = true,
  val importMylarSeries: Boolean = true,
  val importLocalArtwork: Boolean = true,
  val importBarcodeIsbn: Boolean = true,
  val scanForceModifiedTime: Boolean = false,
  val scanOnStartup: Boolean = false,
  val scanInterval: ScanInterval = ScanInterval.EVERY_6H,
  val scanCbx: Boolean = true,
  val scanPdf: Boolean = true,
  val scanEpub: Boolean = true,
  val scanDirectoryExclusions: Set<String> = emptySet(),
  val repairExtensions: Boolean = false,
  val convertToCbz: Boolean = false,
  val emptyTrashAfterScan: Boolean = false,
  val seriesCover: SeriesCover = SeriesCover.FIRST,
  val hashFiles: Boolean = true,
  val hashPages: Boolean = false,
  val hashKoreader: Boolean = false,
  val analyzeDimensions: Boolean = true,
  val oneshotsDirectory: String? = null,
) {
  init {
    require(scanDirectoryExclusions.none(String::isBlank)) {
      "Scan directory exclusions must not contain blank entries"
    }
    require(oneshotsDirectory == null || oneshotsDirectory.isNotBlank()) {
      "Oneshots directory must be null or non-blank"
    }
  }
}

enum class ScanInterval {
  DISABLED,
  HOURLY,
  EVERY_6H,
  EVERY_12H,
  DAILY,
  WEEKLY,
}

enum class SeriesCover {
  FIRST,
  FIRST_UNREAD_OR_FIRST,
  FIRST_UNREAD_OR_LAST,
  LAST,
}

data class SourceLocation(
  val sourceId: String,
  val itemId: String,
) {
  init {
    require(sourceId.isNotBlank()) { "Source ID must not be blank" }
    require(itemId.isNotBlank()) { "Source item ID must not be blank" }
  }
}

enum class MediaKind {
  COMIC_ARCHIVE,
  PDF,
  EPUB,
}

interface LibraryRepository {
  fun findById(id: LibraryId): Library

  fun findByIdOrNull(id: LibraryId): Library?

  fun findAll(): List<Library>

  fun findAllByIds(ids: Collection<LibraryId>): List<Library>

  fun insert(library: Library)

  fun update(library: Library)

  fun delete(id: LibraryId)

  fun deleteAll()

  fun count(): Long
}

sealed class LibraryValidationException(
  message: String,
) : IllegalArgumentException(message)

class LibraryRootMissingException(
  root: SourceLocation,
) : LibraryValidationException("Library root does not exist: $root")

class LibraryRootNotDirectoryException(
  root: SourceLocation,
) : LibraryValidationException("Library root is not a directory: $root")

class DuplicateLibraryNameException(
  name: String,
) : LibraryValidationException("Library name already exists: $name")

class OverlappingLibraryRootException(
  candidate: SourceLocation,
  existing: Library,
) : LibraryValidationException(
    "Library root $candidate overlaps existing library ${existing.name}: ${existing.root}",
  )
