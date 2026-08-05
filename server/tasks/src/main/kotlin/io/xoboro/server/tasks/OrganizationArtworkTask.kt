package io.xoboro.server.tasks

import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.TaskPriority
import io.xoboro.core.application.enqueueOrRetry
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkRepository
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.server.media.TiledArtworkComposer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Enqueues artwork generation for every collection and read list that has none.
 *
 * A sweep rather than a hook on each membership change: collections and read lists are mutated from
 * the native API, the compatibility API, and metadata import, and a cover derived from members is not
 * worth wiring into all three. This mirrors `FIND_BOOK_ARTWORK`, which sweeps for the same reason.
 *
 * A group is enqueued when it has no generated artwork, **or** when its `updatedAtMillis` is newer
 * than the generated artwork it already has. Membership changes bump that timestamp, so the sweep
 * re-derives a stale cover without any of the three mutation paths having to know this task exists.
 * Comparing two timestamps the domain already keeps beats recording which members a cover came from:
 * that would be new state to migrate, keep consistent, and get wrong.
 *
 * The comparison is `>`, not `>=`. A grouping created and covered within the same millisecond must
 * not re-enqueue forever, and a real membership change always lands after the cover it invalidates.
 *
 * Groups that are neither uncovered nor stale are skipped, so re-running the sweep is cheap.
 */
class OrganizationArtworkTaskEmitter(
  private val collections: SeriesCollectionRepository,
  private val readLists: ReadListRepository,
  private val artwork: ArtworkRepository,
  private val queue: DurableTaskQueue,
  private val currentTimeMillis: () -> Long,
) {
  fun generateMissing(priority: Int = TaskPriority.LOWEST): Int {
    val now = currentTimeMillis()
    require(now >= 0) { "Organization artwork timestamp must not be negative" }
    val owners =
      collections.findAll().map {
        ArtworkOwner(ArtworkOwnerKind.COLLECTION, it.id.value) to it.updatedAtMillis
      } +
        readLists.findAll().map {
          ArtworkOwner(ArtworkOwnerKind.READ_LIST, it.id.value) to it.updatedAtMillis
        }
    return owners
      .filter { (owner, updatedAtMillis) -> needsArtwork(owner, updatedAtMillis) }
      .map { (owner, _) -> owner }
      .count { owner ->
        queue.enqueueOrRetry(
          DurableTask(
            id = "${OrganizationArtworkTaskHandler.TASK_TYPE}_${owner.kind.name}_${owner.id}",
            type = OrganizationArtworkTaskHandler.TASK_TYPE,
            payloadJson =
              buildJsonObject {
                put(OrganizationArtworkTaskHandler.OWNER_KIND_FIELD, owner.kind.name)
                put(OrganizationArtworkTaskHandler.OWNER_ID_FIELD, owner.id)
              }.toString(),
            priority = priority,
            availableAtMillis = now,
          ),
          now,
        )
      }
  }

  private fun needsArtwork(
    owner: ArtworkOwner,
    updatedAtMillis: Long,
  ): Boolean {
    val generated = artwork.findAll(owner).filter { it.type == ArtworkType.GENERATED }
    if (generated.isEmpty()) return true
    // Newest, not oldest: a regeneration replaces the previous generated artwork, so the newest one is
    // the cover currently in use and the only one whose age says anything.
    return updatedAtMillis > generated.maxOf { it.createdAtMillis }
  }
}

/**
 * Gives a collection or read list a cover derived from its members.
 *
 * Collects up to [TiledArtworkComposer.TILE_COUNT] member covers and hands them to the composer, which
 * tiles a 2×2 mosaic when it has that many and returns a single cover otherwise. Collecting stops at
 * the tile count rather than reading every member: a group can hold thousands, and the covers past the
 * fourth cannot appear in the result.
 *
 * Members are walked in their stored order, and for a collection a series that has no artwork falls
 * through to its own first media item, because series artwork comes only from disk sidecars and is
 * frequently absent while the books underneath it all have generated covers.
 */
class OrganizationArtworkTaskHandler(
  private val collections: SeriesCollectionRepository,
  private val readLists: ReadListRepository,
  private val books: BookRepository,
  private val artwork: ArtworkRepository,
  private val lifecycle: ArtworkLifecycle,
  private val composer: TiledArtworkComposer = TiledArtworkComposer(),
  private val json: Json = Json,
) : TaskHandler {
  override val taskType: String = TASK_TYPE

  override fun handle(task: DurableTask) {
    require(task.type == taskType) { "Unexpected task type: ${task.type}" }
    val payload = json.parseToJsonElement(task.payloadJson).jsonObject
    val kind =
      payload[OWNER_KIND_FIELD]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.let { value -> ArtworkOwnerKind.entries.firstOrNull { it.name == value } }
        ?: error("Organization artwork task must contain a known owner kind")
    val ownerId =
      payload[OWNER_ID_FIELD]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: error("Organization artwork task must contain an owner ID")
    val covers =
      when (kind) {
        ArtworkOwnerKind.COLLECTION -> collectionCovers(CollectionId(ownerId))
        ArtworkOwnerKind.READ_LIST -> readListCovers(ReadListId(ownerId))
        ArtworkOwnerKind.MEDIA_ITEM, ArtworkOwnerKind.SERIES ->
          error("Media item and series artwork is generated by GENERATE_BOOK_ARTWORK")
      }
    val cover = composer.compose(covers) ?: return
    lifecycle.replaceGenerated(ArtworkOwner(kind, ownerId), cover)
  }

  private fun collectionCovers(id: CollectionId): List<ByteArray> =
    collections
      .findByIdOrNull(id)
      ?.seriesIds
      ?.asSequence()
      ?.mapNotNull { seriesId ->
        selectedContent(ArtworkOwner(ArtworkOwnerKind.SERIES, seriesId.value))
          ?: firstBookCover(seriesId)
      }?.take(TiledArtworkComposer.TILE_COUNT)
      ?.toList()
      .orEmpty()

  private fun readListCovers(id: ReadListId): List<ByteArray> =
    readLists
      .findByIdOrNull(id)
      ?.bookIds
      ?.asSequence()
      ?.mapNotNull(::bookCover)
      ?.take(TiledArtworkComposer.TILE_COUNT)
      ?.toList()
      .orEmpty()

  private fun firstBookCover(seriesId: SeriesId): ByteArray? =
    books
      .findAllBySeriesId(seriesId)
      .asSequence()
      .filter { it.deletedAtMillis == null }
      .sortedWith(compareBy({ it.number }, { it.relativePath }))
      .firstNotNullOfOrNull { bookCover(it.id) }

  private fun bookCover(bookId: BookId): ByteArray? =
    selectedContent(ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, bookId.value))

  private fun selectedContent(owner: ArtworkOwner): ByteArray? =
    artwork
      .findSelectedOrNull(owner)
      ?.let { artwork.content(owner, it.id) }
      ?.takeIf { it.isNotEmpty() }

  companion object {
    const val TASK_TYPE = "GENERATE_ORGANIZATION_ARTWORK"
    internal const val OWNER_KIND_FIELD = "ownerKind"
    internal const val OWNER_ID_FIELD = "ownerId"
  }
}
