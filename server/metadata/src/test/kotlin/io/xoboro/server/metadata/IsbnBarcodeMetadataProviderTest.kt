package io.xoboro.server.metadata

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsbnBarcodeMetadataProviderTest {
  @Test
  fun `scans bounded trailing then leading pages and closes every stream`() {
    val content =
      SyntheticContentAccess(
        pageCount = 8,
        images = mapOf(2 to barcode("9780306406157")),
      )
    val provider = IsbnBarcodeMetadataProvider(content)

    val patch = requireNotNull(provider.provide(library(), book()))

    assertEquals("9780306406157", patch.isbn)
    assertEquals(listOf(8, 7, 6, 1, 2), content.openedPages)
    assertEquals(content.openedPages.size, content.closedStreamCount)
    assertTrue(
      content.requests.all {
        it.format == PageImageFormat.PNG &&
          it.maximumDimension == 4_096
      },
    )
  }

  @Test
  fun `ignores non ISBN EAN values disabled imports and EPUB`() {
    val content =
      SyntheticContentAccess(
        pageCount = 1,
        images = mapOf(1 to barcode("4006381333931")),
      )
    val provider = IsbnBarcodeMetadataProvider(content)

    assertNull(provider.provide(library(), book()))
    assertNull(
      provider.provide(
        library(LibrarySettings(importBarcodeIsbn = false)),
        book(),
      ),
    )
    assertNull(provider.provide(library(), book().copy(mediaKind = MediaKind.EPUB)))
    assertEquals(listOf(1), content.openedPages)
  }

  @Test
  fun `continues after an individual page cannot be opened`() {
    val content =
      SyntheticContentAccess(
        pageCount = 4,
        images = mapOf(3 to barcode("9780306406157")),
        failingPages = setOf(4),
      )

    val patch = requireNotNull(IsbnBarcodeMetadataProvider(content).provide(library(), book()))

    assertEquals("9780306406157", patch.isbn)
    assertEquals(listOf(4, 3), content.openedPages)
    assertEquals(1, content.closedStreamCount)
  }

  @Test
  fun `does not fail metadata refresh when media inventory is not ready`() {
    val content = SyntheticContentAccess(pageCount = 1, images = emptyMap(), failInventory = true)

    assertNull(IsbnBarcodeMetadataProvider(content).provide(library(), book()))
    assertTrue(content.openedPages.isEmpty())
  }

  private fun barcode(value: String): ByteArray {
    val matrix =
      MultiFormatWriter().encode(
        value,
        BarcodeFormat.EAN_13,
        1_200,
        400,
        mapOf(EncodeHintType.MARGIN to 20),
      )
    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until matrix.height) {
      for (x in 0 until matrix.width) {
        image.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
      }
    }
    return ByteArrayOutputStream().use { output ->
      check(ImageIO.write(image, "png", output))
      output.toByteArray()
    }
  }

  private fun library(settings: LibrarySettings = LibrarySettings()): Library =
    Library(
      id = LIBRARY_ID,
      name = "Synthetic library",
      root = SourceLocation("synthetic", "synthetic://root"),
      settings = settings,
      createdAtMillis = 1,
    )

  private fun book(): Book =
    Book(
      id = BOOK_ID,
      libraryId = LIBRARY_ID,
      seriesId = SERIES_ID,
      name = "Synthetic volume",
      relativePath = "series/volume.cbz",
      sourceItemId = "synthetic://volume",
      mediaKind = MediaKind.COMIC_ARCHIVE,
      fileModifiedAtMillis = 1,
      createdAtMillis = 1,
    )

  private class SyntheticContentAccess(
    private val pageCount: Int,
    private val images: Map<Int, ByteArray>,
    private val failingPages: Set<Int> = emptySet(),
    private val failInventory: Boolean = false,
  ) : BookContentAccess {
    val openedPages = mutableListOf<Int>()
    val requests = mutableListOf<PageImageRequest>()
    var closedStreamCount = 0

    override fun pages(bookId: BookId): List<BookPage> {
      if (failInventory) error("Synthetic inventory failure")
      return (1..pageCount).map { page ->
        BookPage(
          number = page,
          fileName = "$page.png",
          mediaType = "image/png",
        )
      }
    }

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream {
      openedPages += pageNumber
      requests += request
      if (pageNumber in failingPages) error("Synthetic page failure")
      return ByteArrayContentStream(
        bytes = images[pageNumber] ?: BLANK_PAGE,
        onClose = { closedStreamCount += 1 },
      )
    }

    override fun openBook(bookId: BookId): MediaContentStream? = null
  }

  private class ByteArrayContentStream(
    private val bytes: ByteArray,
    private val onClose: () -> Unit,
  ) : MediaContentStream {
    private var offset = 0
    private var closed = false

    override val mediaType: String = "image/png"
    override val contentLength: Long = bytes.size.toLong()

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (this.offset >= bytes.size) return -1
      val count = minOf(length, bytes.size - this.offset)
      bytes.copyInto(buffer, offset, this.offset, this.offset + count)
      this.offset += count
      return count
    }

    override fun close() {
      if (!closed) {
        closed = true
        onClose()
      }
    }
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
    val BLANK_PAGE: ByteArray =
      ByteArrayOutputStream().use { output ->
        check(ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", output))
        output.toByteArray()
      }
  }
}
