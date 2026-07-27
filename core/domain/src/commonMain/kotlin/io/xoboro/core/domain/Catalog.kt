package io.xoboro.core.domain

data class SeriesId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Series ID must not be blank" }
  }
}

data class MediaItemId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Media item ID must not be blank" }
  }
}

typealias BookId = MediaItemId

enum class MediaItemType {
  COMIC,
  NOVEL,
  BOOK,
  VIDEO,
  AUDIO,
}

enum class MediaCapability {
  PAGE_SEQUENCE,
  REFLOWABLE_TEXT,
  TIMELINE,
  IMAGE_CONTENT,
  VIDEO_CONTENT,
  AUDIO_CONTENT,
}

interface MediaItemCommon {
  val id: MediaItemId
  val libraryId: LibraryId
  val name: String
  val relativePath: String
  val sourceItemId: String
  val sourceIdentity: String?
  val fileModifiedAtMillis: Long
  val fileSize: Long
  val deletedAtMillis: Long?
  val createdAtMillis: Long
  val updatedAtMillis: Long
}

sealed interface MediaItem : MediaItemCommon {
  val type: MediaItemType
  val capabilities: Set<MediaCapability>

  fun supports(capability: MediaCapability): Boolean = capability in capabilities
}

sealed interface SeriesMediaItem : MediaItem {
  val seriesId: SeriesId
  val number: Int
  val oneshot: Boolean
}

data class MediaItemCore(
  override val id: MediaItemId,
  override val libraryId: LibraryId,
  override val name: String,
  override val relativePath: String,
  override val sourceItemId: String,
  override val sourceIdentity: String? = null,
  override val fileModifiedAtMillis: Long,
  override val fileSize: Long = 0,
  override val deletedAtMillis: Long? = null,
  override val createdAtMillis: Long,
  override val updatedAtMillis: Long = createdAtMillis,
) : MediaItemCommon {
  init {
    require(name.isNotBlank()) { "Media item name must not be blank" }
    require(relativePath.isNotBlank()) { "Media item relative path must not be blank" }
    require(sourceItemId.isNotBlank()) { "Media item source ID must not be blank" }
    require(sourceIdentity == null || sourceIdentity.isNotBlank()) {
      "Media item source identity must be null or non-blank"
    }
    require(fileModifiedAtMillis >= 0) { "Media item file timestamp must not be negative" }
    require(fileSize >= 0) { "Media item file size must not be negative" }
    require(deletedAtMillis == null || deletedAtMillis >= 0) {
      "Media item deletion timestamp must not be negative"
    }
    require(createdAtMillis >= 0) { "Media item creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Media item update timestamp must not precede creation"
    }
  }
}

data class Series(
  val id: SeriesId,
  val libraryId: LibraryId,
  val name: String,
  val relativePath: String,
  val sourceItemId: String,
  val fileModifiedAtMillis: Long,
  val bookCount: Int = 0,
  val deletedAtMillis: Long? = null,
  val oneshot: Boolean = false,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(name.isNotBlank()) { "Series name must not be blank" }
    require(relativePath.isNotBlank()) { "Series relative path must not be blank" }
    require(sourceItemId.isNotBlank()) { "Series source item ID must not be blank" }
    require(fileModifiedAtMillis >= 0) { "Series file timestamp must not be negative" }
    require(bookCount >= 0) { "Series book count must not be negative" }
    require(deletedAtMillis == null || deletedAtMillis >= 0) {
      "Series deletion timestamp must not be negative"
    }
    require(createdAtMillis >= 0) { "Series creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Series update timestamp must not precede creation"
    }
  }
}

data class Book(
  override val id: BookId,
  override val libraryId: LibraryId,
  override val seriesId: SeriesId,
  override val name: String,
  override val relativePath: String,
  override val sourceItemId: String,
  override val sourceIdentity: String? = null,
  val mediaKind: MediaKind,
  override val fileModifiedAtMillis: Long,
  override val fileSize: Long = 0,
  val fileHash: String = "",
  val fileHashKoreader: String = "",
  override val number: Int = 0,
  override val deletedAtMillis: Long? = null,
  override val oneshot: Boolean = false,
  override val createdAtMillis: Long,
  override val updatedAtMillis: Long = createdAtMillis,
) : SeriesMediaItem {
  override val type: MediaItemType = MediaItemType.BOOK
  override val capabilities: Set<MediaCapability> = BOOK_CAPABILITIES

  init {
    require(name.isNotBlank()) { "Book name must not be blank" }
    require(relativePath.isNotBlank()) { "Book relative path must not be blank" }
    require(sourceItemId.isNotBlank()) { "Book source item ID must not be blank" }
    require(sourceIdentity == null || sourceIdentity.isNotBlank()) {
      "Book source identity must be null or non-blank"
    }
    require(fileModifiedAtMillis >= 0) { "Book file timestamp must not be negative" }
    require(fileSize >= 0) { "Book file size must not be negative" }
    require(number >= 0) { "Book number must not be negative" }
    require(deletedAtMillis == null || deletedAtMillis >= 0) {
      "Book deletion timestamp must not be negative"
    }
    require(createdAtMillis >= 0) { "Book creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Book update timestamp must not precede creation"
    }
  }

  companion object {
    private val BOOK_CAPABILITIES = setOf(MediaCapability.PAGE_SEQUENCE)
  }
}

data class Comic(
  val storage: Book,
) : SeriesMediaItem by storage {
  override val type: MediaItemType = MediaItemType.COMIC
  override val capabilities: Set<MediaCapability> = COMIC_CAPABILITIES
  override fun supports(capability: MediaCapability): Boolean = capability in capabilities

  companion object {
    private val COMIC_CAPABILITIES =
      setOf(MediaCapability.PAGE_SEQUENCE, MediaCapability.IMAGE_CONTENT)
  }
}

data class Novel(
  val storage: Book,
) : SeriesMediaItem by storage {
  override val type: MediaItemType = MediaItemType.NOVEL
  override val capabilities: Set<MediaCapability> = NOVEL_CAPABILITIES
  override fun supports(capability: MediaCapability): Boolean = capability in capabilities

  companion object {
    private val NOVEL_CAPABILITIES = setOf(MediaCapability.REFLOWABLE_TEXT)
  }
}

data class Video(
  val core: MediaItemCore,
  val durationMillis: Long? = null,
) : MediaItem,
  MediaItemCommon by core {
  init {
    require(durationMillis == null || durationMillis >= 0) {
      "Video duration must not be negative"
    }
  }

  override val type: MediaItemType = MediaItemType.VIDEO
  override val capabilities: Set<MediaCapability> = VIDEO_CAPABILITIES

  companion object {
    private val VIDEO_CAPABILITIES =
      setOf(
        MediaCapability.TIMELINE,
        MediaCapability.VIDEO_CONTENT,
        MediaCapability.AUDIO_CONTENT,
      )
  }
}

data class Audio(
  val core: MediaItemCore,
  val durationMillis: Long? = null,
) : MediaItem,
  MediaItemCommon by core {
  init {
    require(durationMillis == null || durationMillis >= 0) {
      "Audio duration must not be negative"
    }
  }

  override val type: MediaItemType = MediaItemType.AUDIO
  override val capabilities: Set<MediaCapability> = AUDIO_CAPABILITIES

  companion object {
    private val AUDIO_CAPABILITIES =
      setOf(MediaCapability.TIMELINE, MediaCapability.AUDIO_CONTENT)
  }
}

fun Book.classifyForLibrary(): SeriesMediaItem =
  when (mediaKind) {
    MediaKind.COMIC_ARCHIVE -> Comic(this)
    MediaKind.EPUB -> Novel(this)
    MediaKind.PDF -> this
  }

interface MediaItemRepository {
  fun findByIdOrNull(id: MediaItemId): MediaItem?

  fun findAllByLibraryId(libraryId: LibraryId): List<MediaItem>
}

interface SeriesRepository {
  fun findByIdOrNull(id: SeriesId): Series?

  fun findAllByLibraryId(libraryId: LibraryId): List<Series>

  fun findByLibraryIdAndRelativePath(
    libraryId: LibraryId,
    relativePath: String,
  ): Series?

  fun insert(series: Series)

  fun insertAll(series: Collection<Series>)

  fun update(series: Series)

  fun updateAll(series: Collection<Series>)

  fun delete(id: SeriesId)

  fun count(): Long
}

interface BookRepository {
  fun findByIdOrNull(id: BookId): Book?

  fun findAllByLibraryId(libraryId: LibraryId): List<Book>

  fun findAllBySeriesId(seriesId: SeriesId): List<Book>

  fun findByLibraryIdAndRelativePath(
    libraryId: LibraryId,
    relativePath: String,
  ): Book?

  fun insert(book: Book)

  fun insertAll(books: Collection<Book>)

  fun update(book: Book)

  fun updateAll(books: Collection<Book>)

  fun delete(id: BookId)

  fun count(): Long
}
