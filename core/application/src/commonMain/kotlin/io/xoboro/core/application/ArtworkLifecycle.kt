package io.xoboro.core.application

import io.xoboro.core.domain.Artwork
import io.xoboro.core.domain.ArtworkContent
import io.xoboro.core.domain.ArtworkId
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkRepository
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesRepository

data class ProcessedArtwork(
  val bytes: ByteArray,
  val mediaType: String,
  val width: Int,
  val height: Int,
)

fun interface ArtworkProcessor {
  fun process(input: ByteArray): ProcessedArtwork
}

sealed interface ArtworkEvent {
  val artwork: Artwork

  data class Added(
    override val artwork: Artwork,
  ) : ArtworkEvent

  data class Deleted(
    override val artwork: Artwork,
  ) : ArtworkEvent
}

fun interface ArtworkEventPublisher {
  fun publish(event: ArtworkEvent)
}

class ArtworkLifecycle(
  private val artwork: ArtworkRepository,
  private val processor: ArtworkProcessor,
  private val idFactory: () -> String,
  private val currentTimeMillis: () -> Long,
  private val eventPublisher: ArtworkEventPublisher = ArtworkEventPublisher {},
) {
  fun findAll(owner: ArtworkOwner): List<Artwork> = artwork.findAll(owner)

  fun selectedContentOrNull(owner: ArtworkOwner): ArtworkContent? =
    artwork.findSelectedOrNull(owner)?.let { item ->
      artwork.content(owner, item.id)?.let { ArtworkContent(item, it) }
    }

  fun contentOrNull(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): ArtworkContent? =
    artwork.findByIdOrNull(owner, id)?.let { item ->
      artwork.content(owner, id)?.let { ArtworkContent(item, it) }
    }

  fun addUploaded(
    owner: ArtworkOwner,
    input: ByteArray,
    selected: Boolean,
  ): Artwork {
    require(input.isNotEmpty()) { "Uploaded artwork must not be empty" }
    require(input.size <= MAXIMUM_UPLOAD_BYTES) { "Uploaded artwork exceeds the size limit" }
    val processed = processor.process(input)
    require(processed.bytes.isNotEmpty()) { "Processed artwork must not be empty" }
    val now = now()
    val item =
      Artwork(
        id = ArtworkId(idFactory()),
        owner = owner,
        type = ArtworkType.USER_UPLOADED,
        selected = selected,
        mediaType = processed.mediaType,
        fileSize = processed.bytes.size.toLong(),
        width = processed.width,
        height = processed.height,
        createdAtMillis = now,
      )
    artwork.insert(ArtworkContent(item, processed.bytes))
    return requireNotNull(artwork.findByIdOrNull(owner, item.id))
      .also { eventPublisher.publish(ArtworkEvent.Added(it)) }
  }

  fun replaceGenerated(
    owner: ArtworkOwner,
    input: ByteArray,
  ): Artwork {
    require(input.isNotEmpty()) { "Generated artwork must not be empty" }
    require(input.size <= MAXIMUM_UPLOAD_BYTES) { "Generated artwork exceeds the size limit" }
    val processed = processor.process(input)
    val currentSelected = artwork.findSelectedOrNull(owner)
    val replaced = artwork.findAll(owner).filter { it.type == ArtworkType.GENERATED }
    val now = now()
    val item =
      Artwork(
        id = ArtworkId(idFactory()),
        owner = owner,
        type = ArtworkType.GENERATED,
        selected = currentSelected == null || currentSelected.type == ArtworkType.GENERATED,
        mediaType = processed.mediaType,
        fileSize = processed.bytes.size.toLong(),
        width = processed.width,
        height = processed.height,
        createdAtMillis = now,
      )
    artwork.replaceGenerated(ArtworkContent(item, processed.bytes))
    replaced.forEach { eventPublisher.publish(ArtworkEvent.Deleted(it)) }
    return requireNotNull(artwork.findByIdOrNull(owner, item.id))
      .also { eventPublisher.publish(ArtworkEvent.Added(it)) }
  }

  fun replaceSidecars(
    owner: ArtworkOwner,
    inputs: List<ByteArray>,
  ): List<Artwork> {
    inputs.forEach { input ->
      require(input.isNotEmpty()) { "Sidecar artwork must not be empty" }
      require(input.size <= MAXIMUM_UPLOAD_BYTES) { "Sidecar artwork exceeds the size limit" }
    }
    val selected = artwork.findSelectedOrNull(owner)
    val replaced = artwork.findAll(owner).filter { it.type == ArtworkType.SIDECAR }
    val selectFirst = selected == null || selected.type != ArtworkType.USER_UPLOADED
    val now = now()
    val processedInputs =
      inputs.mapNotNull { input ->
        runCatching { processor.process(input) }.getOrNull()
      }
    val contents =
      processedInputs.mapIndexed { index, processed ->
        val item =
          Artwork(
            id = ArtworkId(idFactory()),
            owner = owner,
            type = ArtworkType.SIDECAR,
            selected = selectFirst && index == 0,
            mediaType = processed.mediaType,
            fileSize = processed.bytes.size.toLong(),
            width = processed.width,
            height = processed.height,
            createdAtMillis = now,
          )
        ArtworkContent(item, processed.bytes)
      }
    artwork.replaceSidecars(owner, contents)
    replaced.forEach { eventPublisher.publish(ArtworkEvent.Deleted(it)) }
    return artwork.findAll(owner)
      .filter { it.type == ArtworkType.SIDECAR }
      .also { items ->
        items.forEach { eventPublisher.publish(ArtworkEvent.Added(it)) }
      }
  }

  fun markSelected(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Boolean {
    val changed = artwork.markSelected(owner, id, now())
    if (changed) {
      artwork.findByIdOrNull(owner, id)
        ?.let { eventPublisher.publish(ArtworkEvent.Added(it)) }
    }
    return changed
  }

  fun deleteUploaded(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Boolean {
    val item = artwork.findByIdOrNull(owner, id) ?: return false
    require(item.type == ArtworkType.USER_UPLOADED) { "Only uploaded artwork can be deleted" }
    return artwork.delete(owner, id)
      .also { deleted ->
        if (deleted) eventPublisher.publish(ArtworkEvent.Deleted(item))
      }
  }

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Artwork timestamp must not be negative" } }

  companion object {
    const val MAXIMUM_UPLOAD_BYTES: Int = 20 * 1_024 * 1_024
  }
}

data class SourceArtwork(
  val name: String,
  val bytes: ByteArray,
) {
  init {
    require(name.isNotBlank()) { "Source artwork name must not be blank" }
    require(bytes.isNotEmpty()) { "Source artwork must not be empty" }
  }
}

interface SourceArtworkAccess {
  val sourceId: String

  fun findBookArtwork(
    rootItemId: String,
    bookItemId: String,
    maximumBytes: Int,
  ): List<SourceArtwork>

  fun findSeriesArtwork(
    rootItemId: String,
    seriesItemId: String,
    maximumBytes: Int,
  ): List<SourceArtwork>
}

class LocalArtworkRefreshLifecycle(
  private val libraries: LibraryRepository,
  private val books: BookRepository,
  private val series: SeriesRepository,
  private val artwork: ArtworkLifecycle,
  accesses: Collection<SourceArtworkAccess>,
) {
  private val accessesBySourceId = accesses.associateBy(SourceArtworkAccess::sourceId)

  init {
    require(accesses.none { it.sourceId.isBlank() }) { "Artwork source IDs must not be blank" }
    require(accessesBySourceId.size == accesses.size) { "Artwork source IDs must be unique" }
  }

  fun refreshBook(bookId: BookId): Int {
    val book = books.findByIdOrNull(bookId)?.takeIf { it.deletedAtMillis == null } ?: return 0
    val library = libraries.findById(book.libraryId)
    if (!library.settings.importLocalArtwork) return 0
    val access = accessesBySourceId[library.root.sourceId] ?: return 0
    return artwork.replaceSidecars(
      owner = ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, book.id.value),
      inputs =
        access
          .findBookArtwork(
            library.root.itemId,
            book.sourceItemId,
            ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES,
          ).map(SourceArtwork::bytes),
    ).size
  }

  fun refreshSeries(seriesId: SeriesId): Int {
    val item =
      series.findByIdOrNull(seriesId)
        ?.takeIf { it.deletedAtMillis == null && !it.oneshot }
        ?: return 0
    val library = libraries.findById(item.libraryId)
    if (!library.settings.importLocalArtwork) return 0
    val access = accessesBySourceId[library.root.sourceId] ?: return 0
    return artwork.replaceSidecars(
      owner = ArtworkOwner(ArtworkOwnerKind.SERIES, item.id.value),
      inputs =
        access
          .findSeriesArtwork(
            library.root.itemId,
            item.sourceItemId,
            ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES,
          ).map(SourceArtwork::bytes),
    ).size
  }
}
