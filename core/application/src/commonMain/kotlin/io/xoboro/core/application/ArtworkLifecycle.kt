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

  /**
   * Member identifiers of the owning grouping, in the type its owner kind implies: series ids for a
   * [ArtworkOwnerKind.COLLECTION], media-item ids for a [ArtworkOwnerKind.READ_LIST]. Empty for
   * media-item and series owners, whose visibility is decided from the owner id alone.
   *
   * The membership travels **inside** the event because the alternative is worse. A subscriber may
   * only learn that a grouping's cover changed if they can see at least one of its members, and the
   * event carries only `owner.kind` and `owner.id` — so a consumer that wanted to scope the change
   * had to either query the grouping itself or invent a broader-than-true scope. Querying puts a
   * repository read in an event mapper, and the read races the change it describes; inventing a
   * scope looks right and is not. Carrying the membership also keeps the event resolvable after the
   * grouping is deleted, which is exactly when a consumer can no longer look it up.
   *
   * An empty grouping therefore produces no visible scope, and is not announced. That is the same
   * rule the collection and read-list read paths already apply: a grouping with no visible members
   * is not visible, so there is nothing to announce.
   */
  val groupingMembers: List<String>

  data class Added(
    override val artwork: Artwork,
    override val groupingMembers: List<String> = emptyList(),
  ) : ArtworkEvent

  data class Deleted(
    override val artwork: Artwork,
    override val groupingMembers: List<String> = emptyList(),
  ) : ArtworkEvent
}

fun interface ArtworkEventPublisher {
  fun publish(event: ArtworkEvent)
}

/**
 * Resolves the members of a collection or read list that owns artwork.
 *
 * Separate from [ArtworkRepository] because membership belongs to the grouping, not to the artwork,
 * and [ArtworkLifecycle] should not gain a dependency on either grouping repository to publish an
 * event. The default resolves nothing, which reproduces the previous behaviour exactly: grouping
 * artwork changes stay unannounced rather than being announced with a guessed scope.
 */
fun interface ArtworkGroupingMembers {
  fun of(owner: ArtworkOwner): List<String>

  companion object {
    val NONE: ArtworkGroupingMembers = ArtworkGroupingMembers { emptyList() }
  }
}

class ArtworkLifecycle(
  private val artwork: ArtworkRepository,
  private val processor: ArtworkProcessor,
  private val idFactory: () -> String,
  private val currentTimeMillis: () -> Long,
  private val eventPublisher: ArtworkEventPublisher = ArtworkEventPublisher {},
  private val groupingMembers: ArtworkGroupingMembers = ArtworkGroupingMembers.NONE,
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
    val members = groupingMembers.of(owner)
    return requireNotNull(artwork.findByIdOrNull(owner, item.id))
      .also { eventPublisher.publish(ArtworkEvent.Added(it, members)) }
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
    // Resolved once per call, not once per published event: a replacement publishes one Deleted per
    // superseded item plus one Added, and the membership is the same for all of them.
    val members = groupingMembers.of(owner)
    replaced.forEach { eventPublisher.publish(ArtworkEvent.Deleted(it, members)) }
    return requireNotNull(artwork.findByIdOrNull(owner, item.id))
      .also { eventPublisher.publish(ArtworkEvent.Added(it, members)) }
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
    val members = groupingMembers.of(owner)
    replaced.forEach { eventPublisher.publish(ArtworkEvent.Deleted(it, members)) }
    return artwork.findAll(owner)
      .filter { it.type == ArtworkType.SIDECAR }
      .also { items ->
        items.forEach { eventPublisher.publish(ArtworkEvent.Added(it, members)) }
      }
  }

  fun markSelected(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Boolean {
    val changed = artwork.markSelected(owner, id, now())
    if (changed) {
      val members = groupingMembers.of(owner)
      artwork.findByIdOrNull(owner, id)
        ?.let { eventPublisher.publish(ArtworkEvent.Added(it, members)) }
    }
    return changed
  }

  fun deleteUploaded(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Boolean {
    val item = artwork.findByIdOrNull(owner, id) ?: return false
    require(item.type == ArtworkType.USER_UPLOADED) { "Only uploaded artwork can be deleted" }
    // Resolved before the delete: for a grouping this reads the grouping, not the artwork, so the
    // order does not matter here - but keeping it before the mutation matches the removal rule that
    // an event must stay resolvable after the thing it describes is gone.
    val members = groupingMembers.of(owner)
    return artwork.delete(owner, id)
      .also { deleted ->
        if (deleted) eventPublisher.publish(ArtworkEvent.Deleted(item, members))
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
