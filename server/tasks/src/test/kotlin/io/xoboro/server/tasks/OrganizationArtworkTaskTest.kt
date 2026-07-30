package io.xoboro.server.tasks

import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.DurableTask
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.Library
import io.xoboro.server.media.SafeJpegArtworkProcessor
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqArtworkRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqReadListRepository
import io.xoboro.server.persistence.JooqSeriesCollectionRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class OrganizationArtworkTaskTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `derives a collection cover from the first member that has one`() {
    withFixture("collection") { fixture ->
      // The first series has no artwork of its own. Series artwork comes only from disk sidecars and
      // is routinely absent, so falling through to its books is what makes the collection get a cover
      // at all rather than skipping straight past a populated series.
      fixture.giveBookArtwork(BookId("book-1"))

      fixture.handler.handle(collectionTask())

      val generated =
        fixture.artwork.findAll(ArtworkOwner(ArtworkOwnerKind.COLLECTION, "collection-1"))
      assertEquals(listOf(ArtworkType.GENERATED), generated.map { it.type })
      assertTrue(generated.single().selected)
    }
  }

  @Test
  fun `derives a read list cover from its first member`() {
    withFixture("read-list") { fixture ->
      fixture.giveBookArtwork(BookId("book-1"))

      fixture.handler.handle(readListTask())

      assertEquals(
        1,
        fixture.artwork.findAll(ArtworkOwner(ArtworkOwnerKind.READ_LIST, "read-list-1")).size,
      )
    }
  }

  @Test
  fun `leaves a group without any member artwork alone`() {
    withFixture("empty") { fixture ->
      fixture.handler.handle(collectionTask())

      // Nothing to borrow from. Writing a placeholder would put a cover in the catalog that no
      // member ever had.
      assertNull(
        fixture.artwork.findSelectedOrNull(
          ArtworkOwner(ArtworkOwnerKind.COLLECTION, "collection-1"),
        ),
      )
    }
  }

  @Test
  fun `sweeps only groups that have no generated artwork`() {
    withFixture("sweep") { fixture ->
      assertEquals(2, fixture.emitter.generateMissing())
      fixture.handler.handle(collectionTask())
      fixture.giveBookArtwork(BookId("book-1"))
      fixture.handler.handle(collectionTask())

      // Re-running must not re-enqueue the collection that now carries a derived cover, or a sweep
      // would grow the queue every time it ran.
      assertEquals(1, fixture.emitter.generateMissing())
    }
  }

  @Test
  fun `re-enqueues a group whose membership changed after its cover was derived`() {
    withFixture("stale") { fixture ->
      fixture.giveBookArtwork(BookId("book-1"))
      fixture.handler.handle(collectionTask())
      // Covered now, so the sweep leaves it alone. The read list is still uncovered, hence 1.
      assertEquals(1, fixture.emitter.generateMissing())

      // A membership change bumps updatedAtMillis past the cover's createdAtMillis of 20. None of the
      // three mutation paths has to know this task exists - the sweep notices on its own.
      val collection = requireNotNull(fixture.collections.findByIdOrNull(CollectionId("collection-1")))
      fixture.collections.update(collection.copy(updatedAtMillis = 100))

      assertEquals(2, fixture.emitter.generateMissing())
    }
  }

  @Test
  fun `does not re-enqueue a group covered in the same millisecond it changed`() {
    withFixture("same-instant") { fixture ->
      fixture.giveBookArtwork(BookId("book-1"))
      fixture.handler.handle(collectionTask())
      val collection = requireNotNull(fixture.collections.findByIdOrNull(CollectionId("collection-1")))

      // Equal timestamps, not newer. The comparison is `>` so a group created and covered within one
      // millisecond does not re-enqueue forever; a real membership change always lands afterwards.
      fixture.collections.update(collection.copy(updatedAtMillis = 20))

      assertEquals(1, fixture.emitter.generateMissing())
    }
  }

  private fun collectionTask(): DurableTask =
    DurableTask(
      id = "generate-collection",
      type = OrganizationArtworkTaskHandler.TASK_TYPE,
      payloadJson = """{"ownerKind":"COLLECTION","ownerId":"collection-1"}""",
      availableAtMillis = 1,
    )

  private fun readListTask(): DurableTask =
    DurableTask(
      id = "generate-read-list",
      type = OrganizationArtworkTaskHandler.TASK_TYPE,
      payloadJson = """{"ownerKind":"READ_LIST","ownerId":"read-list-1"}""",
      availableAtMillis = 1,
    )

  private fun withFixture(
    name: String,
    block: (Fixture) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("$name.sqlite"))).use { database ->
      JooqLibraryRepository(database).insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", "file:///synthetic"),
          createdAtMillis = 1,
        ),
      )
      JooqSeriesRepository(database).insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "Synthetic series",
          sourceItemId = "file:///synthetic/series",
          fileModifiedAtMillis = 1,
          bookCount = 1,
          createdAtMillis = 1,
        ),
      )
      val books = JooqBookRepository(database)
      books.insert(
        Book(
          id = BookId("book-1"),
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic chapter",
          relativePath = "Synthetic series/chapter.cbz",
          sourceItemId = "file:///synthetic/series/chapter.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      val collections = JooqSeriesCollectionRepository(database)
      collections.insert(
        SeriesCollection(
          id = CollectionId("collection-1"),
          name = "Synthetic collection",
          ordered = true,
          seriesIds = listOf(SERIES_ID),
          createdAtMillis = 1,
        ),
      )
      val readLists = JooqReadListRepository(database)
      readLists.insert(
        ReadList(
          id = ReadListId("read-list-1"),
          name = "Synthetic read list",
          bookIds = listOf(BookId("book-1")),
          createdAtMillis = 1,
        ),
      )
      val artwork = JooqArtworkRepository(database)
      var sequence = 0
      val lifecycle =
        ArtworkLifecycle(
          artwork = artwork,
          processor = SafeJpegArtworkProcessor(),
          idFactory = { "artwork-${sequence++}" },
          currentTimeMillis = { 20 },
        )
      block(
        Fixture(
          artwork = artwork,
          collections = collections,
          lifecycle = lifecycle,
          emitter =
            OrganizationArtworkTaskEmitter(
              collections = collections,
              readLists = readLists,
              artwork = artwork,
              queue = JooqDurableTaskQueue(database),
              currentTimeMillis = { 20 },
            ),
          handler =
            OrganizationArtworkTaskHandler(
              collections = collections,
              readLists = readLists,
              books = books,
              artwork = artwork,
              lifecycle = lifecycle,
            ),
        ),
      )
    }
  }

  private class Fixture(
    val artwork: JooqArtworkRepository,
    val collections: JooqSeriesCollectionRepository,
    private val lifecycle: ArtworkLifecycle,
    val emitter: OrganizationArtworkTaskEmitter,
    val handler: OrganizationArtworkTaskHandler,
  ) {
    fun giveBookArtwork(bookId: BookId) {
      lifecycle.replaceGenerated(
        ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, bookId.value),
        jpeg(),
      )
    }

    private fun jpeg(): ByteArray =
      ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB), "jpeg", output)
        output.toByteArray()
      }
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
  }
}
