package io.xoboro.server.tasks

import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogGroupCount
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.ClaimedTask
import io.xoboro.core.application.DurableTask
import io.xoboro.core.application.DurableTaskQueue
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.TaskCounts
import io.xoboro.core.application.ThumbnailSize
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.SafeJpegArtworkProcessor
import io.xoboro.server.media.RarToCbzConverter
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqArtworkRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqDurableTaskQueue
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqPageHashRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.sources.local.LocalSourceMutationAccess
import io.xoboro.server.sources.local.LocalSourceMediaAccess
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
      val handler =
        GenerateBookArtworkTaskHandler(
          FixedBookContent(jpeg()),
          artwork,
          { ThumbnailSize.DEFAULT.maximumDimension },
        )
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
  fun `generates a cover at the configured thumbnail size`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("size.sqlite"))).use { database ->
      var sequence = 0
      val artwork =
        ArtworkLifecycle(
          artwork = JooqArtworkRepository(database),
          processor = SafeJpegArtworkProcessor(),
          idFactory = { "artwork-${++sequence}" },
          currentTimeMillis = { sequence.toLong() },
        )
      val content = RecordingBookContent(jpeg())
      val handler =
        GenerateBookArtworkTaskHandler(
          content,
          artwork,
          { ThumbnailSize.LARGE.maximumDimension },
        )

      handler.handle(
        DurableTask(
          id = "generate-large",
          type = GenerateBookArtworkTaskHandler.TASK_TYPE,
          payloadJson = """{"bookId":"book-1"}""",
          availableAtMillis = 1,
        ),
      )

      assertEquals(listOf<Int?>(ThumbnailSize.LARGE.maximumDimension), content.requestedDimensions)
    }
  }

  @Test
  fun `reads the thumbnail size for every cover rather than capturing it once`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("changed.sqlite"))).use {
        database ->
      var sequence = 0
      val artwork =
        ArtworkLifecycle(
          artwork = JooqArtworkRepository(database),
          processor = SafeJpegArtworkProcessor(),
          idFactory = { "artwork-${++sequence}" },
          currentTimeMillis = { sequence.toLong() },
        )
      val content = RecordingBookContent(jpeg())
      var configured = ThumbnailSize.DEFAULT
      val handler =
        GenerateBookArtworkTaskHandler(
          content,
          artwork,
          maximumCoverDimension = { configured.maximumDimension },
        )
      val task =
        DurableTask(
          id = "generate-1",
          type = GenerateBookArtworkTaskHandler.TASK_TYPE,
          payloadJson = """{"bookId":"book-1"}""",
          availableAtMillis = 1,
        )

      handler.handle(task)
      // An operator raising the setting mid-analysis is the whole reason this is a supplier: a
      // captured value would keep every remaining cover at the size the server started with.
      configured = ThumbnailSize.XLARGE
      handler.handle(task.copy(id = "generate-2"))

      assertEquals(
        listOf<Int?>(
          ThumbnailSize.DEFAULT.maximumDimension,
          ThumbnailSize.XLARGE.maximumDimension,
        ),
        content.requestedDimensions,
      )
    }
  }

  @Test
  fun `bigger-only regeneration measures existing covers against the configured size`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("sweep.sqlite"))).use { database ->
      var sequence = 0
      val artwork =
        ArtworkLifecycle(
          artwork = JooqArtworkRepository(database),
          processor = SafeJpegArtworkProcessor(),
          idFactory = { "artwork-${++sequence}" },
          currentTimeMillis = { sequence.toLong() },
        )
      // A 640x480 cover: above DEFAULT's limit, below XLARGE's. Which side of the configured size
      // it falls on is the entire decision under test.
      artwork.replaceGenerated(
        ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, "book-1"),
        jpeg(width = 640, height = 480),
      )
      val stored =
        artwork.findAll(ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, "book-1")).single()
      assertEquals(640, maxOf(stored.width, stored.height))

      fun sweep(size: ThumbnailSize): Int {
        val queue = RecordingQueue()
        FindBookArtworkTaskHandler(
          catalog = SingleBookCatalog,
          artwork = artwork,
          queue = queue,
          taskIdFactory = { "sweep-${size.name}" },
          currentTimeMillis = { 1 },
          maximumCoverDimension = { size.maximumDimension },
        ).handle(
          DurableTask(
            id = "find-${size.name}",
            type = FindBookArtworkTaskHandler.TASK_TYPE,
            payloadJson = """{"forBiggerResultOnly":"true"}""",
            availableAtMillis = 1,
          ),
        )
        return queue.enqueued.count { it.type == GenerateBookArtworkTaskHandler.TASK_TYPE }
      }

      assertEquals(0, sweep(ThumbnailSize.DEFAULT), "already at or above DEFAULT - nothing to grow")
      assertEquals(1, sweep(ThumbnailSize.XLARGE), "below XLARGE - a bigger cover is available")
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

  @Test
  fun `repairs a signature mismatched archive without replacing its catalog identity`() {
    val root = Files.createDirectories(temporaryDirectory.resolve("library-repair"))
    val seriesDirectory = Files.createDirectories(root.resolve("Synthetic series"))
    val archive = seriesDirectory.resolve("chapter.cbr")
    ZipOutputStream(Files.newOutputStream(archive)).use { output ->
      output.putNextEntry(ZipEntry("001.png"))
      output.write(byteArrayOf(1, 2, 3))
      output.closeEntry()
    }
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("repair.sqlite"))).use {
        database ->
      seedCatalog(database, root, seriesDirectory, archive)
      val books = JooqBookRepository(database)
      val queue = JooqDurableTaskQueue(database)
      val handler =
        ArchiveMaintenanceTaskHandler(
          books = books,
          libraries = JooqLibraryRepository(database),
          media = JooqBookMediaRepository(database),
          accesses = listOf(LocalSourceMediaAccess()),
          mutations = listOf(LocalSourceMutationAccess()),
          converter = RarToCbzConverter(),
          analysisEmitter = AnalyzeBookTaskEmitter(books, queue) { 20 },
          scanEmitter = ScanLibraryTaskEmitter(queue) { 20 },
          currentTimeMillis = { 20 },
        )

      handler.handle(
        DurableTask(
          id = "maintain-1",
          type = ArchiveMaintenanceTaskHandler.TASK_TYPE,
          payloadJson =
            """{"bookId":"book-1","repairExtensions":true,"convertToCbz":false}""",
          availableAtMillis = 1,
        ),
      )

      val repaired = seriesDirectory.resolve("chapter.cbz")
      assertTrue(Files.exists(repaired))
      assertTrue(!Files.exists(archive))
      val retained = requireNotNull(books.findByIdOrNull(BOOK_ID))
      assertEquals(BOOK_ID, retained.id)
      assertEquals("Synthetic series/chapter.cbz", retained.relativePath)
      assertTrue(Files.isSameFile(repaired, Path.of(java.net.URI(retained.sourceItemId))))
      assertEquals(2, queue.counts().pending)
    }
  }

  @Test
  fun `converts RAR to CBZ while retaining the media item and invalidating hashes`() {
    val root = Files.createDirectories(temporaryDirectory.resolve("library-convert"))
    val seriesDirectory = Files.createDirectories(root.resolve("Synthetic series"))
    val archive = seriesDirectory.resolve("chapter.cbr")
    Files.write(
      archive,
      Base64.getDecoder().decode(
        "UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAATS800SUCAwuHAASHACC6fRl6gAAACUZJTEUxLlRYVAoDAgDwWYPlessBZmlsZTENCqOo3u8lAgMLhwAEhwAg48NfeIAAAAlGSUxFMi5UWFQKAwIAd+2G5XrLAWZpbGUyDQodd1ZRAwUEAA==",
      ),
    )
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("convert.sqlite"))).use {
        database ->
      seedCatalog(database, root, seriesDirectory, archive)
      val books = JooqBookRepository(database)
      books.update(
        requireNotNull(books.findByIdOrNull(BOOK_ID)).copy(
          fileHash = "old-content-hash",
          fileHashKoreader = "old-reader-hash",
        ),
      )
      val queue = JooqDurableTaskQueue(database)
      val handler =
        ArchiveMaintenanceTaskHandler(
          books = books,
          libraries = JooqLibraryRepository(database),
          media = JooqBookMediaRepository(database),
          accesses = listOf(LocalSourceMediaAccess()),
          mutations = listOf(LocalSourceMutationAccess()),
          converter = RarToCbzConverter(),
          analysisEmitter = AnalyzeBookTaskEmitter(books, queue) { 20 },
          scanEmitter = ScanLibraryTaskEmitter(queue) { 20 },
          currentTimeMillis = { 20 },
        )

      handler.handle(
        DurableTask(
          id = "maintain-2",
          type = ArchiveMaintenanceTaskHandler.TASK_TYPE,
          payloadJson =
            """{"bookId":"book-1","repairExtensions":false,"convertToCbz":true}""",
          availableAtMillis = 1,
        ),
      )

      val converted = seriesDirectory.resolve("chapter.cbz")
      assertTrue(Files.exists(converted))
      assertTrue(!Files.exists(archive))
      ZipFile(converted.toFile()).use { output ->
        assertEquals(
          listOf("FILE1.TXT", "FILE2.TXT"),
          output.entries().asSequence().map { it.name }.toList(),
        )
      }
      val retained = requireNotNull(books.findByIdOrNull(BOOK_ID))
      assertEquals(BOOK_ID, retained.id)
      assertEquals("", retained.fileHash)
      assertEquals("", retained.fileHashKoreader)
      assertEquals(2, queue.counts().pending)
    }
  }

  @Test
  fun `repairs a document mislabelled as a comic archive and records its kind`() {
    val root = Files.createDirectories(temporaryDirectory.resolve("library-document"))
    val seriesDirectory = Files.createDirectories(root.resolve("Synthetic series"))
    val document = seriesDirectory.resolve("chapter.cbz")
    Files.write(document, syntheticPdf())
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("document.sqlite"))).use {
        database ->
      seedCatalog(database, root, seriesDirectory, document)
      val books = JooqBookRepository(database)
      val media = JooqBookMediaRepository(database)
      media.upsert(
        BookMedia(
          bookId = BOOK_ID,
          status = MediaStatus.ERROR,
          mediaType = "application/zip",
          profile = MediaProfile.DIVINA,
          createdAtMillis = 1,
        ),
      )
      val queue = JooqDurableTaskQueue(database)

      handlerFor(database, books, media, queue).handle(repairTask("maintain-document"))

      assertTrue(Files.exists(seriesDirectory.resolve("chapter.pdf")))
      assertTrue(!Files.exists(document))
      val repaired = requireNotNull(books.findByIdOrNull(BOOK_ID))
      // The scan derives kind from the extension but nothing re-analyzes a book afterwards, so a
      // repair that left the kind alone would leave this book analyzed as an archive forever.
      assertEquals(MediaKind.PDF, repaired.mediaKind)
      assertEquals("Synthetic series/chapter.pdf", repaired.relativePath)
      // Pages and a profile produced by the ZIP analyzer describe nothing about a PDF.
      assertNull(media.findByBookIdOrNull(BOOK_ID))
    }
  }

  @Test
  fun `repairs a comic archive mislabelled as a document`() {
    val root = Files.createDirectories(temporaryDirectory.resolve("library-reverse"))
    val seriesDirectory = Files.createDirectories(root.resolve("Synthetic series"))
    val archive = seriesDirectory.resolve("chapter.pdf")
    ZipOutputStream(Files.newOutputStream(archive)).use { output ->
      output.putNextEntry(ZipEntry("001.png"))
      output.write(byteArrayOf(1, 2, 3))
      output.closeEntry()
    }
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("reverse.sqlite"))).use {
        database ->
      seedCatalog(database, root, seriesDirectory, archive, mediaKind = MediaKind.PDF)
      val books = JooqBookRepository(database)
      val media = JooqBookMediaRepository(database)
      val queue = JooqDurableTaskQueue(database)

      // This direction was unreachable: both the emitter and the handler filtered to comic archives,
      // so a book the catalog believed was a PDF never entered maintenance at all.
      assertEquals(
        1,
        ArchiveMaintenanceTaskEmitter(books, queue) { 20 }.maintainLibrary(
          libraryId = LIBRARY_ID,
          repairExtensions = true,
          convertToCbz = false,
        ),
      )
      handlerFor(database, books, media, queue).handle(repairTask("maintain-reverse"))

      assertTrue(Files.exists(seriesDirectory.resolve("chapter.cbz")))
      assertEquals(
        MediaKind.COMIC_ARCHIVE,
        requireNotNull(books.findByIdOrNull(BOOK_ID)).mediaKind,
      )
    }
  }

  @Test
  fun `leaves a mislabelled file alone when the library would stop indexing it`() {
    val root = Files.createDirectories(temporaryDirectory.resolve("library-unscanned"))
    val seriesDirectory = Files.createDirectories(root.resolve("Synthetic series"))
    val document = seriesDirectory.resolve("chapter.cbz")
    Files.write(document, syntheticPdf())
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("unscanned.sqlite"))).use {
        database ->
      seedCatalog(
        database,
        root,
        seriesDirectory,
        document,
        settings = LibrarySettings(scanPdf = false),
      )
      val books = JooqBookRepository(database)
      val media = JooqBookMediaRepository(database)
      val queue = JooqDurableTaskQueue(database)

      handlerFor(database, books, media, queue).handle(repairTask("maintain-unscanned"))

      // Kind comes from the extension, and an unscanned kind yields no scan candidate - so renaming
      // this file would delete the book on the next scan. A vanished book is worse than a wrong name.
      assertTrue(Files.exists(document))
      assertTrue(!Files.exists(seriesDirectory.resolve("chapter.pdf")))
      assertEquals(
        "Synthetic series/chapter.cbz",
        requireNotNull(books.findByIdOrNull(BOOK_ID)).relativePath,
      )
      assertEquals(0, queue.counts().pending)
    }
  }

  private fun handlerFor(
    database: XoboroDatabase,
    books: JooqBookRepository,
    media: JooqBookMediaRepository,
    queue: JooqDurableTaskQueue,
  ): ArchiveMaintenanceTaskHandler =
    ArchiveMaintenanceTaskHandler(
      books = books,
      libraries = JooqLibraryRepository(database),
      media = media,
      accesses = listOf(LocalSourceMediaAccess()),
      mutations = listOf(LocalSourceMutationAccess()),
      converter = RarToCbzConverter(),
      analysisEmitter = AnalyzeBookTaskEmitter(books, queue) { 20 },
      scanEmitter = ScanLibraryTaskEmitter(queue) { 20 },
      currentTimeMillis = { 20 },
    )

  private fun repairTask(id: String): DurableTask =
    DurableTask(
      id = id,
      type = ArchiveMaintenanceTaskHandler.TASK_TYPE,
      payloadJson = """{"bookId":"book-1","repairExtensions":true,"convertToCbz":false}""",
      availableAtMillis = 1,
    )

  /**
   * A file the format detector identifies as a PDF. Only the signature matters here - this test is
   * about where a repair routes the file, not about parsing a document.
   */
  private fun syntheticPdf(): ByteArray =
    "%PDF-1.7\n%\u00e2\u00e3\u00cf\u00d3\ntrailer<</Root 1 0 R>>\n%%EOF\n".encodeToByteArray()

  private fun seedCatalog(
    database: XoboroDatabase,
    root: Path,
    seriesDirectory: Path,
    archive: Path,
    mediaKind: MediaKind = MediaKind.COMIC_ARCHIVE,
    settings: LibrarySettings = LibrarySettings(),
  ) {
    JooqLibraryRepository(database).insert(
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation("local", root.toUri().toString()),
        settings = settings,
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
        relativePath =
          root.relativize(archive).iterator().asSequence().joinToString("/") { it.toString() },
        sourceItemId = archive.toUri().toString(),
        mediaKind = mediaKind,
        fileModifiedAtMillis = 1,
        fileSize = Files.size(archive),
        createdAtMillis = 1,
      ),
    )
  }

  private fun jpeg(
    width: Int = 4,
    height: Int = 6,
  ): ByteArray =
    ByteArrayOutputStream().use { output ->
      ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", output)
      output.toByteArray()
    }

  /**
   * Records what a sweep decided to enqueue. A real queue would answer the same question through
   * [DurableTaskQueue.counts], but only as a number - and the decision under test is *which* task
   * type got enqueued, which a count cannot distinguish from an unrelated row.
   */
  private class RecordingQueue : DurableTaskQueue {
    val enqueued = mutableListOf<DurableTask>()

    override fun enqueue(
      task: DurableTask,
      nowMillis: Long,
    ): Boolean {
      enqueued += task
      return true
    }

    override fun claimNext(
      workerId: String,
      leaseToken: String,
      nowMillis: Long,
      leaseDurationMillis: Long,
    ): ClaimedTask? = null

    override fun renewLease(
      taskId: String,
      leaseToken: String,
      nowMillis: Long,
      leaseDurationMillis: Long,
    ): Boolean = false

    override fun complete(
      taskId: String,
      leaseToken: String,
    ): Boolean = false

    override fun fail(
      taskId: String,
      leaseToken: String,
      error: String,
      retryAtMillis: Long?,
      nowMillis: Long,
    ): Boolean = false

    override fun counts(): TaskCounts = TaskCounts(pending = 0, running = 0, dead = 0)
  }

  /** One book, so a sweep's per-book decision is the only variable the assertion can be reading. */
  private object SingleBookCatalog : CatalogReadRepository {
    override fun findBooks(
      query: BookCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogBook> =
      CatalogPage(
        content =
          listOf(
            CatalogBook(
              book =
                Book(
                  id = BookId("book-1"),
                  seriesId = SeriesId("series-1"),
                  libraryId = LibraryId("library-1"),
                  name = "book-1",
                  relativePath = "series-1/book-1.cbz",
                  sourceItemId = "file:///books/series-1/book-1.cbz",
                  mediaKind = MediaKind.COMIC_ARCHIVE,
                  fileModifiedAtMillis = 1,
                  fileSize = 1,
                  createdAtMillis = 1,
                ),
              seriesTitle = "series-1",
              seriesMetadata =
                SeriesMetadata(
                  seriesId = SeriesId("series-1"),
                  title = "series-1",
                  createdAtMillis = 1,
                  updatedAtMillis = 1,
                ),
              metadata =
                BookMetadata(
                  bookId = BookId("book-1"),
                  title = "book-1",
                  number = "1",
                  numberSort = 1f,
                  createdAtMillis = 1,
                  updatedAtMillis = 1,
                ),
              media = null,
              readProgress = null,
            ),
          ),
        page = 0,
        size = 500,
        totalElements = 1,
      )

    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = null

    override fun findPreviousBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = null

    override fun findNextBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = null

    override fun findSeries(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogSeries> =
      CatalogPage(content = emptyList(), page = 0, size = 500, totalElements = 0)

    override fun findSeriesByIdOrNull(
      id: SeriesId,
      access: CatalogAccess,
    ): CatalogSeries? = null

    override fun countSeriesByFirstCharacter(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
    ): List<CatalogGroupCount> = emptyList()
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

  /**
   * Records the limit each cover request carried. The generator hands the limit to the content
   * layer rather than resizing anything itself, so the request is where the setting is observable -
   * asserting on the stored artwork's pixels instead would only prove that this fake resizes.
   */
  private class RecordingBookContent(
    private val bytes: ByteArray,
  ) : BookContentAccess {
    val requestedDimensions = mutableListOf<Int?>()

    override fun pages(bookId: BookId): List<BookPage>? = emptyList()

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream {
      requestedDimensions += request.maximumDimension
      return ByteArrayMediaContent(bytes)
    }

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
