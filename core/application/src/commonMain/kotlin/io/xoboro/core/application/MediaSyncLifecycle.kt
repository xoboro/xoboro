package io.xoboro.core.application

import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.MediaSyncSnapshot
import io.xoboro.core.domain.MediaSyncSnapshotRepository
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SyncMediaItemState
import io.xoboro.core.domain.SyncPoint
import io.xoboro.core.domain.SyncPointId
import io.xoboro.core.domain.SyncPointRepository
import io.xoboro.core.domain.SyncProgressState
import io.xoboro.core.domain.SyncReadListState
import io.xoboro.core.domain.UserId

enum class MediaSyncChangeKind {
  MEDIA_ITEM_ADDED,
  MEDIA_ITEM_CHANGED,
  MEDIA_ITEM_REMOVED,
  PROGRESS_CHANGED,
  READ_LIST_ADDED,
  READ_LIST_CHANGED,
  READ_LIST_REMOVED,
}

data class MediaSyncChange(
  val kind: MediaSyncChangeKind,
  val mediaItemId: BookId? = null,
  val readListId: ReadListId? = null,
) {
  init {
    require((mediaItemId == null) != (readListId == null)) {
      "A sync change must target exactly one entity"
    }
  }
}

data class MediaSyncPlan(
  val from: MediaSyncSnapshot?,
  val to: MediaSyncSnapshot,
  val changes: List<MediaSyncChange>,
)

class MediaSyncLifecycle(
  private val syncPoints: SyncPointRepository,
  private val snapshots: MediaSyncSnapshotRepository,
  private val catalog: CatalogReadRepository,
  private val readLists: ReadListRepository,
  private val syncPointIdFactory: () -> String,
  private val currentTimeMillis: () -> Long,
) {
  fun capture(
    userId: UserId,
    apiKeyId: ApiKeyId?,
    access: CatalogAccess,
  ): SyncPoint {
    val point =
      SyncPoint(
        id = SyncPointId(syncPointIdFactory()),
        userId = userId,
        apiKeyId = apiKeyId,
        createdAtMillis = currentTimeMillis(),
      )
    syncPoints.insert(point)
    try {
      snapshots.insert(snapshot(point.id, access))
    } catch (failure: Throwable) {
      syncPoints.delete(point.id)
      throw failure
    }
    return point
  }

  fun plan(
    fromId: SyncPointId?,
    toId: SyncPointId,
    userId: UserId,
  ): MediaSyncPlan {
    val toPoint = verifiedPoint(toId, userId)
    val to = requireNotNull(snapshots.findBySyncPointIdOrNull(toPoint.id)) {
      "Current sync snapshot is missing"
    }
    val from =
      fromId
        ?.let { syncPoints.findByIdOrNull(it) }
        ?.takeIf { it.userId == userId }
        ?.let { snapshots.findBySyncPointIdOrNull(it.id) }
    return MediaSyncPlan(from, to, changes(from, to))
  }

  fun pointOrNull(
    id: SyncPointId?,
    userId: UserId,
  ): SyncPoint? =
    id
      ?.let(syncPoints::findByIdOrNull)
      ?.takeIf { it.userId == userId }

  fun advance(
    id: SyncPointId,
    cursor: Int,
  ) {
    require(cursor >= 0) { "Sync cursor must not be negative" }
    syncPoints.updateCursor(id, cursor)
  }

  fun complete(
    previousId: SyncPointId?,
    currentId: SyncPointId,
  ) {
    if (previousId != null && previousId != currentId) {
      syncPoints.delete(previousId)
    }
  }

  private fun verifiedPoint(
    id: SyncPointId,
    userId: UserId,
  ): SyncPoint =
    requireNotNull(syncPoints.findByIdOrNull(id)?.takeIf { it.userId == userId }) {
      "Sync point does not belong to the user"
    }

  private fun snapshot(
    pointId: SyncPointId,
    access: CatalogAccess,
  ): MediaSyncSnapshot {
    val books =
      catalog
        .findBooks(
          BookCatalogQuery(),
          access,
          CatalogPageRequest(
            size = CatalogPageRequest.MAXIMUM_PAGE_SIZE,
            unpaged = true,
          ),
        ).content
        .filter {
          it.media?.status == MediaStatus.READY &&
            it.media.profile == MediaProfile.EPUB
        }
    val bookIds = books.map { it.book.id }.toSet()
    val states =
      books.map { item ->
        SyncMediaItemState(
          mediaItemId = item.book.id,
          revision =
            listOf(
              item.book.updatedAtMillis,
              item.book.fileModifiedAtMillis,
              item.metadata.updatedAtMillis,
              item.seriesMetadata.updatedAtMillis,
              requireNotNull(item.media).updatedAtMillis,
            ).joinToString("-"),
          createdAtMillis = item.book.createdAtMillis,
          updatedAtMillis =
            maxOf(
              item.book.updatedAtMillis,
              item.metadata.updatedAtMillis,
              item.seriesMetadata.updatedAtMillis,
              item.media.updatedAtMillis,
            ),
        )
      }
    val visibleReadLists =
      readLists.findAll().mapNotNull { readList ->
        val visibleIds = readList.bookIds.filter(bookIds::contains)
        if (visibleIds.isEmpty()) {
          null
        } else {
          SyncReadListState(
            readListId = readList.id,
            name = readList.name,
            revision =
              "${readList.updatedAtMillis}-${visibleIds.joinToString(",") { it.value }}",
            mediaItemIds = visibleIds,
            createdAtMillis = readList.createdAtMillis,
            updatedAtMillis = readList.updatedAtMillis,
          )
        }
      }
    val progresses =
      books.mapNotNull { item ->
        item.readProgress?.let {
          SyncProgressState(
            mediaItemId = item.book.id,
            revision = "${it.updatedAtMillis}-${it.completed}-${it.page}-${it.locatorJson.orEmpty()}",
          )
        }
      }
    return MediaSyncSnapshot(
      syncPointId = pointId,
      mediaItems = states,
      readLists = visibleReadLists,
      progresses = progresses,
    )
  }

  private fun changes(
    from: MediaSyncSnapshot?,
    to: MediaSyncSnapshot,
  ): List<MediaSyncChange> {
    val oldItems = from?.mediaItems.orEmpty().associateBy(SyncMediaItemState::mediaItemId)
    val newItems = to.mediaItems.associateBy(SyncMediaItemState::mediaItemId)
    val oldProgress = from?.progresses.orEmpty().associateBy(SyncProgressState::mediaItemId)
    val newProgress = to.progresses.associateBy(SyncProgressState::mediaItemId)
    val oldLists = from?.readLists.orEmpty().associateBy(SyncReadListState::readListId)
    val newLists = to.readLists.associateBy(SyncReadListState::readListId)

    val added = (newItems.keys - oldItems.keys).sortedBy(BookId::value)
    val changed =
      (newItems.keys intersect oldItems.keys)
        .filter { newItems.getValue(it).revision != oldItems.getValue(it).revision }
        .sortedBy(BookId::value)
    val removed = (oldItems.keys - newItems.keys).sortedBy(BookId::value)
    val progressChanged =
      (newItems.keys intersect oldItems.keys)
        .filter { it !in changed }
        .filter { newProgress[it]?.revision != oldProgress[it]?.revision }
        .sortedBy(BookId::value)
    val listsAdded = (newLists.keys - oldLists.keys).sortedBy(ReadListId::value)
    val listsChanged =
      (newLists.keys intersect oldLists.keys)
        .filter { newLists.getValue(it).revision != oldLists.getValue(it).revision }
        .sortedBy(ReadListId::value)
    val listsRemoved = (oldLists.keys - newLists.keys).sortedBy(ReadListId::value)

    return buildList {
      added.forEach { add(MediaSyncChange(MediaSyncChangeKind.MEDIA_ITEM_ADDED, mediaItemId = it)) }
      changed.forEach { add(MediaSyncChange(MediaSyncChangeKind.MEDIA_ITEM_CHANGED, mediaItemId = it)) }
      removed.forEach { add(MediaSyncChange(MediaSyncChangeKind.MEDIA_ITEM_REMOVED, mediaItemId = it)) }
      progressChanged.forEach {
        add(MediaSyncChange(MediaSyncChangeKind.PROGRESS_CHANGED, mediaItemId = it))
      }
      listsAdded.forEach { add(MediaSyncChange(MediaSyncChangeKind.READ_LIST_ADDED, readListId = it)) }
      listsChanged.forEach {
        add(MediaSyncChange(MediaSyncChangeKind.READ_LIST_CHANGED, readListId = it))
      }
      listsRemoved.forEach {
        add(MediaSyncChange(MediaSyncChangeKind.READ_LIST_REMOVED, readListId = it))
      }
    }
  }
}
