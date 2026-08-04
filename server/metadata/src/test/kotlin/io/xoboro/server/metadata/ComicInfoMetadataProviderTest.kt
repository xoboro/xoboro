package io.xoboro.server.metadata

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.media.MaterializedMedia
import io.xoboro.server.media.RandomAccessMedia
import io.xoboro.server.media.SourceMediaAccess
import io.xoboro.server.media.SourceRandomAccess
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class ComicInfoMetadataProviderTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `imports book and series metadata from a bounded ComicInfo entry`() {
    val archive = tempDirectory.resolve("synthetic.cbz")
    archive.writeComicInfo(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <ComicInfo>
        <Title>Synthetic chapter</Title>
        <Series>Synthetic series</Series>
        <Number>7.5</Number>
        <Count>12</Count>
        <Volume>2</Volume>
        <Summary>Synthetic summary</Summary>
        <Year>2026</Year>
        <Month>7</Month>
        <Day>27</Day>
        <Writer>Writer One, Writer Two</Writer>
        <Penciller>Artist One</Penciller>
        <Publisher>Synthetic publisher</Publisher>
        <Genre>Action, Drama</Genre>
        <Tags>First, Second</Tags>
        <AlternateSeries>Synthetic chronology</AlternateSeries>
        <AlternateNumber>4</AlternateNumber>
        <StoryArc>Opening arc, Final arc</StoryArc>
        <StoryArcNumber>1, 9</StoryArcNumber>
        <SeriesGroup>Synthetic group, Shared group</SeriesGroup>
        <Web>https://example.invalid/item https://reference.invalid/item</Web>
        <LanguageISO>ko-KR</LanguageISO>
        <Manga>YesAndRightToLeft</Manga>
        <AgeRating>Teen</AgeRating>
        <GTIN>9780306406157</GTIN>
      </ComicInfo>
      """.trimIndent(),
    )
    val provider = ComicInfoMetadataProvider(listOf(FixedMediaAccess(archive)))

    val bookPatch = requireNotNull(provider.provide(library(), book()))
    assertEquals("Synthetic chapter", bookPatch.title)
    assertEquals("7.5", bookPatch.number)
    assertEquals(7.5F, bookPatch.numberSort)
    assertEquals("2026-07-27", bookPatch.releaseDate)
    assertEquals(
      listOf("writer", "writer", "penciller"),
      bookPatch.authors?.map { it.normalizedRole },
    )
    assertEquals(setOf("first", "second"), bookPatch.tags)
    assertEquals("9780306406157", bookPatch.isbn)
    assertEquals(2, bookPatch.links?.size)
    assertEquals(
      listOf(
        "Synthetic chronology" to 4,
        "Opening arc" to 1,
        "Final arc" to 9,
      ),
      bookPatch.readLists.map { it.name to it.number },
    )

    val seriesPatch = requireNotNull(provider.provide(library(), series(), listOf(book())))
    assertEquals("Synthetic series (2)", seriesPatch.title)
    assertEquals(ReadingDirection.RIGHT_TO_LEFT, seriesPatch.readingDirection)
    assertEquals("Synthetic publisher", seriesPatch.publisher)
    assertEquals(13, seriesPatch.ageRating)
    assertEquals("ko-KR", seriesPatch.language)
    assertEquals(setOf("Action", "Drama"), seriesPatch.genres)
    assertEquals(12, seriesPatch.totalBookCount)
    assertEquals(setOf("Synthetic group", "Shared group"), seriesPatch.collections)
  }

  @Test
  fun `imports organization metadata independently from bibliographic metadata`() {
    val archive = tempDirectory.resolve("organization.cbz")
    archive.writeComicInfo(
      """
      <ComicInfo>
        <Title>Ignored title</Title>
        <Series>Ignored series</Series>
        <AlternateSeries>Synthetic order</AlternateSeries>
        <StoryArc>Arc without number</StoryArc>
        <SeriesGroup>Synthetic shelf</SeriesGroup>
      </ComicInfo>
      """.trimIndent(),
    )
    val provider = ComicInfoMetadataProvider(listOf(FixedMediaAccess(archive)))
    val library =
      library().copy(
        settings =
          LibrarySettings(
            importComicInfoBook = false,
            importComicInfoSeries = false,
            importComicInfoReadList = true,
            importComicInfoCollection = true,
          ),
      )

    val bookPatch = requireNotNull(provider.provide(library, book()))
    assertEquals(false, provider.shouldApplyBookMetadata(library))
    assertEquals(
      listOf("Synthetic order", "Arc without number"),
      bookPatch.readLists.map { it.name },
    )
    val seriesPatch = requireNotNull(provider.provide(library, series(), listOf(book())))
    assertEquals(false, provider.shouldApplySeriesMetadata(library))
    assertEquals(setOf("Synthetic shelf"), seriesPatch.collections)
  }

  @Test
  fun `respects import settings and rejects document type declarations`() {
    val archive = tempDirectory.resolve("unsafe.cbz")
    archive.writeComicInfo(
      """
      <?xml version="1.0"?>
      <!DOCTYPE ComicInfo [<!ENTITY external SYSTEM "file:///synthetic/secret">]>
      <ComicInfo><Title>&external;</Title></ComicInfo>
      """.trimIndent(),
    )
    val provider = ComicInfoMetadataProvider(listOf(FixedMediaAccess(archive)))

    assertNull(provider.provide(library(), book()))
    assertNull(
      provider.provide(
        library().copy(
          settings = LibrarySettings(importComicInfoBook = false),
        ),
        book(),
      ),
    )
  }

  private fun Path.writeComicInfo(content: String) {
    ZipOutputStream(toFile().outputStream()).use { output ->
      output.putNextEntry(ZipEntry("ComicInfo.xml"))
      output.write(content.encodeToByteArray())
      output.closeEntry()
    }
  }

  private fun library(): Library =
    Library(
      id = LIBRARY_ID,
      name = "Synthetic library",
      root = SourceLocation("synthetic", "root"),
      createdAtMillis = 1,
    )

  private fun series(): Series =
    Series(
      id = SERIES_ID,
      libraryId = LIBRARY_ID,
      name = "Synthetic series",
      relativePath = "series",
      sourceItemId = "series-item",
      fileModifiedAtMillis = 1,
      bookCount = 1,
      createdAtMillis = 1,
    )

  private fun book(): Book =
    Book(
      id = BookId("book-1"),
      libraryId = LIBRARY_ID,
      seriesId = SERIES_ID,
      name = "Synthetic book",
      relativePath = "series/book.cbz",
      sourceItemId = "book-item",
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1,
      number = 1,
      createdAtMillis = 1,
    )

  @Test
  fun `reads ComicInfo by range without materializing the archive`() {
    val archive = tempDirectory.resolve("ranged.cbz")
    archive.writeComicInfo(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <ComicInfo>
        <Title>Synthetic chapter</Title>
        <Series>Synthetic series</Series>
      </ComicInfo>
      """.trimIndent(),
    )
    val ranged = FixedRandomAccess(java.nio.file.Files.readAllBytes(archive))
    val whole = CountingMediaAccess()
    val provider = ComicInfoMetadataProvider(listOf(whole), listOf(ranged))

    val patch = requireNotNull(provider.provide(library(), book()))

    assertEquals("Synthetic chapter", patch.title)
    assertEquals(1, ranged.opened)
    assertEquals(0, whole.materializations, "the archive must not be fetched to read ComicInfo.xml")
  }

  /**
   * The three-outcome case. "No `ComicInfo.xml` in this archive" is an answer the central directory
   * already gives; treating it as "could not tell" would fetch the whole archive to confirm an
   * absence, which on a remote library is the expensive half of the work coming straight back.
   */
  @Test
  fun `does not materialize to confirm an archive holds no ComicInfo`() {
    val archive = tempDirectory.resolve("bare.cbz")
    ZipOutputStream(java.nio.file.Files.newOutputStream(archive)).use { output ->
      output.putNextEntry(ZipEntry("pages/001.jpg"))
      output.write(ByteArray(64) { 0x7 })
      output.closeEntry()
    }
    val ranged = FixedRandomAccess(java.nio.file.Files.readAllBytes(archive))
    val whole = CountingMediaAccess()
    val provider = ComicInfoMetadataProvider(listOf(whole), listOf(ranged))

    assertNull(provider.provide(library(), book()))

    assertEquals(1, ranged.opened)
    assertEquals(
      0,
      whole.materializations,
      "an absence the central directory already settled must not be confirmed by a full fetch",
    )
  }

  /** Serves the archive's bytes by range and counts how many opens a read cost. */
  private class FixedRandomAccess(
    private val bytes: ByteArray,
  ) : SourceRandomAccess {
    override val sourceId: String = "synthetic"

    var opened: Int = 0
      private set

    override fun open(
      rootItemId: String,
      itemId: String,
    ): RandomAccessMedia {
      opened++
      return object : RandomAccessMedia {
        override val size: Long = bytes.size.toLong()

        override fun read(
          offset: Long,
          length: Int,
        ): ByteArray {
          if (offset >= size) return ByteArray(0)
          return bytes.copyOfRange(offset.toInt(), minOf(offset + length, size).toInt())
        }

        override fun close() = Unit
      }
    }
  }

  /**
   * Counts whole-archive fetches, which on a remote source is the entire cost of the work.
   *
   * It counts rather than throwing: `readComicInfo` wraps the materializing path in `runCatching`,
   * which swallows `Error` as readily as `Exception`, so a fixture that threw to fail the test was
   * silently absorbed and the assertion passed while the archive was being fetched. Verified by
   * mutation - collapsing the "archive holds no ComicInfo" case into "cannot tell" left the throwing
   * version green.
   */
  private class CountingMediaAccess : SourceMediaAccess {
    override val sourceId: String = "synthetic"

    var materializations: Int = 0
      private set

    override fun materialize(
      rootItemId: String,
      itemId: String,
    ): MaterializedMedia {
      materializations++
      throw java.io.IOException("The archive is deliberately unavailable in this fixture")
    }
  }

  private class FixedMediaAccess(
    private val path: Path,
  ) : SourceMediaAccess {
    override val sourceId: String = "synthetic"

    override fun materialize(
      rootItemId: String,
      itemId: String,
    ): MaterializedMedia =
      object : MaterializedMedia {
        override val path: Path = this@FixedMediaAccess.path

        override fun close() = Unit
      }
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
  }
}
