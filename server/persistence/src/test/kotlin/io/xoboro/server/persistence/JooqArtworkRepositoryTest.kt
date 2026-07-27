package io.xoboro.server.persistence

import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.LocalArtworkRefreshLifecycle
import io.xoboro.core.domain.Artwork
import io.xoboro.core.domain.ArtworkContent
import io.xoboro.core.domain.ArtworkId
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.SafeJpegArtworkProcessor
import io.xoboro.server.sources.local.LocalSourceArtworkAccess
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqArtworkRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `persists owner scoped content and atomically changes selection`() {
    val path = tempDirectory.resolve("artwork.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqArtworkRepository(database)
      val first = content("first", OWNER, selected = true, byte = 1)
      val second = content("second", OWNER, selected = true, byte = 2)
      val other = content("other", OTHER_OWNER, selected = true, byte = 3)

      repository.insert(first)
      repository.insert(second)
      repository.insert(other)

      assertEquals(
        listOf("second", "first"),
        repository.findAll(OWNER).map { it.id.value },
      )
      assertFalse(requireNotNull(repository.findByIdOrNull(OWNER, first.artwork.id)).selected)
      assertTrue(requireNotNull(repository.findByIdOrNull(OWNER, second.artwork.id)).selected)
      assertContentEquals(second.bytes, repository.content(OWNER, second.artwork.id))
      assertNull(repository.findByIdOrNull(OWNER, other.artwork.id))
      assertTrue(repository.markSelected(OWNER, first.artwork.id, 30))
      assertEquals("first", repository.findSelectedOrNull(OWNER)?.id?.value)
      assertFalse(repository.markSelected(OWNER, other.artwork.id, 30))
      assertTrue(repository.delete(OWNER, second.artwork.id))
    }
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqArtworkRepository(database)
      assertEquals("first", repository.findSelectedOrNull(OWNER)?.id?.value)
      assertContentEquals(byteArrayOf(1), repository.content(OWNER, ArtworkId("first")))
    }
  }

  @Test
  fun `refreshes local book and series sidecars while preserving user selection`() {
    val root = Files.createDirectories(tempDirectory.resolve("library"))
    val seriesPath = Files.createDirectories(root.resolve("Synthetic series"))
    val bookPath = Files.write(seriesPath.resolve("volume.cbz"), byteArrayOf(0))
    Files.write(seriesPath.resolve("volume.jpg"), image("jpeg", 20, 30))
    Files.write(seriesPath.resolve("cover.png"), image("png", 40, 50))
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("sidecars.sqlite"))).use { database ->
      val libraries = JooqLibraryRepository(database)
      val series = JooqSeriesRepository(database)
      val books = JooqBookRepository(database)
      libraries.insert(
        Library(
          id = LIBRARY_ID,
          name = "Synthetic library",
          root = SourceLocation("local", root.toUri().toString()),
          createdAtMillis = 1,
        ),
      )
      series.insert(
        Series(
          id = SERIES_ID,
          libraryId = LIBRARY_ID,
          name = "Synthetic series",
          relativePath = "Synthetic series",
          sourceItemId = seriesPath.toUri().toString(),
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      books.insert(
        Book(
          id = BOOK_ID,
          libraryId = LIBRARY_ID,
          seriesId = SERIES_ID,
          name = "Synthetic volume",
          relativePath = "Synthetic series/volume.cbz",
          sourceItemId = bookPath.toUri().toString(),
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = 1,
          createdAtMillis = 1,
        ),
      )
      var sequence = 0
      val lifecycle =
        ArtworkLifecycle(
          artwork = JooqArtworkRepository(database),
          processor = SafeJpegArtworkProcessor(),
          idFactory = { "artwork-${++sequence}" },
          currentTimeMillis = { sequence.toLong() },
        )
      val refresh =
        LocalArtworkRefreshLifecycle(
          libraries = libraries,
          books = books,
          series = series,
          artwork = lifecycle,
          accesses = listOf(LocalSourceArtworkAccess()),
        )

      assertEquals(1, refresh.refreshBook(BOOK_ID))
      assertEquals(1, refresh.refreshSeries(SERIES_ID))
      val bookOwner = ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, BOOK_ID.value)
      val seriesOwner = ArtworkOwner(ArtworkOwnerKind.SERIES, SERIES_ID.value)
      assertEquals(ArtworkType.SIDECAR, lifecycle.findAll(bookOwner).single().type)
      assertTrue(lifecycle.findAll(bookOwner).single().selected)
      assertEquals(40, lifecycle.findAll(seriesOwner).single().width)

      val uploaded = lifecycle.addUploaded(bookOwner, image("png", 60, 70), selected = true)
      Files.write(seriesPath.resolve("volume-2.png"), image("png", 25, 35))
      Files.write(seriesPath.resolve("volume-3.webp"), "invalid".encodeToByteArray())
      assertEquals(2, refresh.refreshBook(BOOK_ID))
      assertEquals(uploaded.id, lifecycle.findAll(bookOwner).first { it.selected }.id)

      Files.delete(seriesPath.resolve("volume.jpg"))
      Files.delete(seriesPath.resolve("volume-2.png"))
      Files.delete(seriesPath.resolve("volume-3.webp"))
      assertEquals(0, refresh.refreshBook(BOOK_ID))
      assertEquals(listOf(uploaded.id), lifecycle.findAll(bookOwner).map { it.id })
      assertTrue(lifecycle.findAll(bookOwner).single().selected)
    }
  }

  private fun image(
    format: String,
    width: Int,
    height: Int,
  ): ByteArray =
    ByteArrayOutputStream().use { output ->
      ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output)
      output.toByteArray()
    }

  private fun content(
    id: String,
    owner: ArtworkOwner,
    selected: Boolean,
    byte: Byte,
  ): ArtworkContent =
    ArtworkContent(
      artwork =
        Artwork(
          id = ArtworkId(id),
          owner = owner,
          type = ArtworkType.USER_UPLOADED,
          selected = selected,
          mediaType = "image/jpeg",
          fileSize = 1,
          width = 1,
          height = 1,
          createdAtMillis = 10,
          updatedAtMillis = 10,
        ),
      bytes = byteArrayOf(byte),
    )

  private companion object {
    val OWNER = ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, "item-1")
    val OTHER_OWNER = ArtworkOwner(ArtworkOwnerKind.SERIES, "series-1")
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
  }
}
