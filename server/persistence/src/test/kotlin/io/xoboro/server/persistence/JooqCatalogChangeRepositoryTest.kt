package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogMutationEvent
import io.xoboro.core.application.CatalogMutationKind
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CatalogChangeEntityKind
import io.xoboro.core.domain.CatalogChangeMutation
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SeriesId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The durable change feed a client uses to catch up.
 *
 * ADR 0056 decided SSE is "an invalidation channel rather than a durable log; reconnect recovery must
 * refresh current state". That is right for a browser tab and impossible for a device holding a local
 * copy: refreshing current state means re-reading 145,105 items to learn three moved, and it cannot
 * report a deletion at all, because a deleted item is absent from current state exactly like one the
 * client never saw.
 *
 * What these pin is the part that makes a local copy trustworthy rather than merely fast: that the
 * cursor only goes forward, that a reader is never told about a library they were not granted, and
 * that a cursor which fell behind retention is told so instead of being handed an empty page that
 * looks like "nothing changed".
 */
class JooqCatalogChangeRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `reports what changed after a cursor, oldest first`() {
    withFeed("ordered") { database, repository ->
      database.append(bookAdded("item-1"), seriesUpdated("series-1"), bookDeleted("item-2"))

      val page = repository.findAfter(0, setOf(LIBRARY), limit = 10)

      assertEquals(
        listOf("item-1", "series-1", "item-2"),
        page.changes.map { it.entityId },
      )
      assertEquals(
        listOf(
          CatalogChangeEntityKind.MEDIA_ITEM,
          CatalogChangeEntityKind.SERIES,
          CatalogChangeEntityKind.MEDIA_ITEM,
        ),
        page.changes.map { it.entityKind },
      )
      assertEquals(CatalogChangeMutation.DELETED, page.changes.last().mutation)
      assertFalse(page.resyncRequired)

      // The cursor is the last row's sequence, so asking again returns nothing rather than repeating.
      val next = repository.findAfter(page.nextCursor, setOf(LIBRARY), limit = 10)
      assertEquals(emptyList(), next.changes.map { it.entityId })
      assertEquals(
        page.nextCursor,
        next.nextCursor,
        "an empty answer must not move the cursor, or an idle client would skip the next change",
      )
    }
  }

  @Test
  fun `never reports a change in a library the reader was not granted`() {
    withFeed("scoped") { database, repository ->
      database.append(bookAdded("mine"), bookAdded("theirs", OTHER_LIBRARY))

      assertEquals(
        listOf("mine"),
        repository.findAfter(0, setOf(LIBRARY), limit = 10).changes.map { it.entityId },
      )
      // A deletion carries its library on the row precisely so this still holds after the entity is
      // gone: the entity cannot be consulted, and guessing would leak that it ever existed.
      assertEquals(
        emptyList(),
        repository.findAfter(0, emptySet(), limit = 10).changes.map { it.entityId },
      )
      assertEquals(
        listOf("mine", "theirs"),
        repository.findAfter(0, null, limit = 10).changes.map { it.entityId },
        "null is no restriction, which is what an unrestricted reader has",
      )
    }
  }

  /**
   * The one that decides whether a local copy can be trusted at all.
   *
   * Without the floor, a cursor older than what retention swept gets an empty page - identical to
   * "nothing changed" - and the client keeps rows for items that no longer exist, forever, with
   * nothing to tell it so.
   */
  @Test
  fun `tells a cursor that fell behind retention to resync instead of answering it`() {
    withFeed("swept") { database, repository ->
      database.append(bookAdded("item-1"), bookAdded("item-2"), bookAdded("item-3"))
      val stale = repository.findAfter(0, setOf(LIBRARY), limit = 10).changes.first().sequence

      assertEquals(2, repository.sweepThrough(stale + 1))

      val page = repository.findAfter(stale, setOf(LIBRARY), limit = 10)
      assertTrue(page.resyncRequired)
      assertEquals(emptyList(), page.changes.map { it.entityId })
      assertEquals(stale + 1, page.floorSequence)
      // A cursor at or after the floor is still served normally, so sweeping does not invalidate
      // everyone.
      assertFalse(repository.findAfter(stale + 1, setOf(LIBRARY), limit = 10).resyncRequired)
    }
  }

  /**
   * A plain `INTEGER PRIMARY KEY` reuses rowids freed by a delete. After retention swept the tail, a
   * new row could take a number a client had already passed - and that client would never see it,
   * silently, forever. `AUTOINCREMENT` is what stops that, so it is asserted rather than assumed.
   */
  @Test
  fun `keeps issuing higher sequences after retention removed the tail`() {
    withFeed("monotonic") { database, repository ->
      database.append(bookAdded("item-1"), bookAdded("item-2"))
      val highest = repository.findAfter(0, setOf(LIBRARY), limit = 10).changes.last().sequence
      repository.sweepThrough(highest)

      database.append(bookAdded("item-3"))

      val page = repository.findAfter(highest, setOf(LIBRARY), limit = 10)
      assertEquals(listOf("item-3"), page.changes.map { it.entityId })
      assertTrue(
        page.changes.single().sequence > highest,
        "a reused sequence would be invisible to every client already past it",
      )
    }
  }

  private fun XoboroDatabase.append(vararg events: CatalogMutationEvent) {
    transaction { it.appendCatalogChanges(events.toList(), occurredAtMillis = 1_700_000_000_000L) }
  }

  private fun bookAdded(
    id: String,
    library: LibraryId = LIBRARY,
  ) = CatalogMutationEvent.Book(CatalogMutationKind.ADDED, BookId(id), SERIES, library)

  private fun bookDeleted(id: String) =
    CatalogMutationEvent.Book(CatalogMutationKind.DELETED, BookId(id), SERIES, LIBRARY)

  private fun seriesUpdated(id: String) =
    CatalogMutationEvent.Series(CatalogMutationKind.UPDATED, SeriesId(id), LIBRARY)

  private fun withFeed(
    name: String,
    block: (XoboroDatabase, JooqCatalogChangeRepository) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$name.sqlite"))).use { database ->
      block(database, JooqCatalogChangeRepository(database))
    }
  }

  private companion object {
    val LIBRARY = LibraryId("library-1")
    val OTHER_LIBRARY = LibraryId("library-2")
    val SERIES = SeriesId("series-1")
  }
}
