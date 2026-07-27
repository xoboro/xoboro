package io.xoboro.server.persistence

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaSyncSnapshot
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.SyncMediaItemState
import io.xoboro.core.domain.SyncPoint
import io.xoboro.core.domain.SyncPointId
import io.xoboro.core.domain.SyncProgressState
import io.xoboro.core.domain.SyncReadListState
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqMediaSyncSnapshotRepositoryTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `persists ordered snapshots cursors and cascade deletion`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("sync-snapshot.sqlite"))).use {
        database ->
      seedIdentity(database)
      val points = JooqSyncPointRepository(database)
      val point =
        SyncPoint(
          id = SyncPointId("sync-1"),
          userId = USER_ID,
          apiKeyId = API_KEY_ID,
          createdAtMillis = 10,
        )
      points.insert(point)
      val snapshots = JooqMediaSyncSnapshotRepository(database)
      val expected =
        MediaSyncSnapshot(
          syncPointId = point.id,
          mediaItems =
            listOf(
              SyncMediaItemState(
                mediaItemId = BookId("book-2"),
                revision = "revision-2",
                createdAtMillis = 2,
                updatedAtMillis = 3,
              ),
              SyncMediaItemState(
                mediaItemId = BookId("book-1"),
                revision = "revision-1",
                createdAtMillis = 1,
                updatedAtMillis = 2,
              ),
            ),
          readLists =
            listOf(
              SyncReadListState(
                readListId = ReadListId("list-1"),
                name = "Synthetic shelf",
                revision = "list-revision",
                mediaItemIds = listOf(BookId("book-2"), BookId("book-1")),
                createdAtMillis = 4,
                updatedAtMillis = 5,
              ),
            ),
          progresses =
            listOf(
              SyncProgressState(BookId("book-1"), "progress-revision"),
            ),
        )
      snapshots.insert(expected)

      val restored = requireNotNull(snapshots.findBySyncPointIdOrNull(point.id))
      assertEquals(expected.mediaItems.sortedBy { it.mediaItemId.value }, restored.mediaItems)
      assertEquals(expected.readLists, restored.readLists)
      assertEquals(expected.progresses, restored.progresses)

      points.updateCursor(point.id, 7)
      assertEquals(7, points.findByIdOrNull(point.id)?.cursor)
      points.delete(point.id)
      assertNull(points.findByIdOrNull(point.id))
      assertNull(snapshots.findBySyncPointIdOrNull(point.id))
    }
  }

  private fun seedIdentity(database: XoboroDatabase) {
    JooqUserRepository(database).insert(
      User(
        id = USER_ID,
        email = "reader@example.invalid",
        passwordHash = "synthetic-hash",
        roles = setOf(UserRole.KOBO_SYNC),
        createdAtMillis = 1,
      ),
    )
    JooqApiKeyRepository(database).insert(
      ApiKey(
        id = API_KEY_ID,
        userId = USER_ID,
        keyHash = "synthetic-key-hash",
        comment = "Synthetic Kobo",
        createdAtMillis = 2,
      ),
    )
  }

  private companion object {
    val USER_ID = UserId("user-1")
    val API_KEY_ID = ApiKeyId("key-1")
  }
}
