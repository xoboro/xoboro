package io.xoboro.server.tasks

import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.SafeJpegArtworkProcessor
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqArtworkRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqPageHashRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.sources.local.LocalSourceMutationAccess
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CompatibilityMaintenanceTaskTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `generates and atomically replaces persisted book artwork`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("artwork.sqlite"))).use {
        database ->
      var sequence = 0
      val artwork =
        ArtworkLifecycle(
          artwork = JooqArtworkRepository(database),
          processor = SafeJpegArtworkProcessor(),
          idFactory = { "artwork-${++sequence}" },
          currentTimeMillis = { sequence.toLong() },
        )
      val handler = GenerateBookArtworkTaskHandler(FixedBookContent(jpeg()), artwork)
      val task =
        DurableTask(
          id = "generate-1",
          type = GenerateBookArtworkTaskHandler.TASK_TYPE,
          payloadJson = """{"bookId":"book-1"}""",
          availableAtMillis = 1,
        )

      handler.handle(task)
      handler.handle(task.copy(id = "generate-2"))

      val generated =
        artwork.findAll(ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, "book-1"))
      assertEquals(1, generated.size)
      assertEquals(ArtworkType.GENERATED, generated.single().type)
      assertTrue(generated.single().selected)
    }
  }

  @Test
  fun `removes duplicate archive pages and schedules a rescan`() {
    val root = Files.createDirectories(temporaryDirectory.resolve("library"))
    val seriesDirectory = Files.createDirectories(root.resolve("Synthetic series"))
    val archive = seriesDirectory.resolve("chapter.cbz")
    ZipOutputStream(Files.newOutputStream(archive)).use { output ->
      listOf("001.jpg", "002.jpg", "ComicInfo.xml").forEach { name ->
        output.putNextEntry(ZipEntry(name))
        output.write(name.encodeToByteArray())
        output.closeEntry()
      }
    }
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("removal.sqlite"))).use {
        database ->
      seedCatalog(database, root, seriesDirectory, archive)
      val queue = JooqDurableTaskQueue(database)
      val pageHashes = JooqPageHashRepository(database)
      pageHashes.upsert(
        KnownPageHash(
          hash = "shared",
          action = PageHashAction.DELETE_MANUAL,
          createdAtMillis = 1,
        ),
      )
      val handler =
        RemoveDuplicatePagesTaskHandler(
          books = JooqBookRepository(database),
          libraries = JooqLibraryRepository(database),
          pageHashes = pageHashes,
          mutations = listOf(LocalSourceMutationAccess()),
          scanEmitter = ScanLibraryTaskEmitter(queue) { 10 },
        )

      handler.handle(
        DurableTask(
          id = "remove-1",
          type = RemoveDuplicatePagesTaskHandler.TASK_TYPE,
          payloadJson =
            """{"bookId":"book-1","pageHash":"shared","entryNames":["002.jpg"]}""",
          availableAtMillis = 1,
        ),
      )

      assertEquals(
        listOf("001.jpg", "ComicInfo.xml"),
        ZipInputStream(Files.newInputStream(archive)).use { input ->
          buildList {
            while (true) add(input.nextEntry?.name ?: break)
          }
        },
      )
      assertEquals(1, queue.counts().pending)
      assertEquals(1, pageHashes.findKnownOrNull("shared")?.deleteCount)
    }
  }

  private fun seedCatalog(
    database: XoboroDatabase,
    root: Path,
    seriesDirectory: Path,
    archive: Path,
  ) {
    JooqLibraryRepository(database).insert(
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation("local", root.toUri().toString()),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = SERIES_ID,
        libraryId = LIBRARY_ID,
        name = "Synthetic series",
        relativePath = "Synthetic series",
        sourceItemId = seriesDirectory.toUri().toString(),
        fileModifiedAtMillis = 1,
        bookCount = 1,
        createdAtMillis = 1,
      ),
    )
    JooqBookRepository(database).insert(
      Book(
        id = BOOK_ID,
        libraryId = LIBRARY_ID,
        seriesId = SERIES_ID,
        name = "Synthetic chapter",
        relativePath = "Synthetic series/chapter.cbz",
        sourceItemId = archive.toUri().toString(),
        mediaKind = MediaKind.COMIC_ARCHIVE,
        fileModifiedAtMillis = 1,
        fileSize = Files.size(archive),
        createdAtMillis = 1,
      ),
    )
  }

  private fun jpeg(): ByteArray =
    ByteArrayOutputStream().use { output ->
      ImageIO.write(BufferedImage(4, 6, BufferedImage.TYPE_INT_RGB), "jpeg", output)
      output.toByteArray()
    }

  private class FixedBookContent(
    private val bytes: ByteArray,
  ) : BookContentAccess {
    override fun pages(bookId: BookId): List<BookPage>? = emptyList()

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream = ByteArrayMediaContent(bytes)

    override fun openBook(bookId: BookId): MediaContentStream? = null
  }

  private class ByteArrayMediaContent(
    bytes: ByteArray,
  ) : MediaContentStream {
    private val input = ByteArrayInputStream(bytes)
    override val mediaType: String = "image/jpeg"
    override val contentLength: Long = bytes.size.toLong()

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int = input.read(buffer, offset, length)

    override fun close() = input.close()
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
  }
}
