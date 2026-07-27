package io.xoboro.server.metadata

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.BookMetadataPatch
import io.xoboro.core.application.BookMetadataProvider
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.MediaKind
import java.io.InputStream
import java.util.EnumSet
import javax.imageio.ImageIO

class IsbnBarcodeMetadataProvider(
  private val content: BookContentAccess,
) : BookMetadataProvider {
  private val hints =
    mapOf(
      DecodeHintType.POSSIBLE_FORMATS to EnumSet.of(BarcodeFormat.EAN_13),
      DecodeHintType.TRY_HARDER to true,
    )

  override fun provide(
    library: Library,
    book: Book,
  ): BookMetadataPatch? {
    if (!library.settings.importBarcodeIsbn || book.mediaKind == MediaKind.EPUB) return null
    val pageCount =
      try {
        content.pages(book.id)?.size
      } catch (_: Exception) {
        null
      } ?: return null
    val pageNumbers =
      ((maxOf(1, pageCount - LAST_PAGE_COUNT + 1)..pageCount).reversed() +
        (1..minOf(FIRST_PAGE_COUNT, pageCount)))
        .distinct()
    return pageNumbers
      .asSequence()
      .mapNotNull { pageNumber -> decodePage(book, pageNumber) }
      .firstOrNull()
      ?.let { BookMetadataPatch(isbn = it) }
  }

  private fun decodePage(
    book: Book,
    pageNumber: Int,
  ): String? {
    try {
      val stream =
        content.openPage(
          book.id,
          pageNumber,
          PageImageRequest(
            format = PageImageFormat.PNG,
            maximumDimension = MAXIMUM_SCAN_DIMENSION,
          ),
        ) ?: return null
      return stream.asInputStream().use { input ->
        val image = ImageIO.read(input) ?: return@use null
        require(image.width > 0 && image.height > 0)
        require(image.width.toLong() * image.height <= MAXIMUM_SCAN_PIXELS) {
          "Barcode scan image exceeds the pixel limit"
        }
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bitmap =
          BinaryBitmap(
            HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)),
          )
        MultiFormatReader()
          .decode(bitmap, hints)
          .text
          ?.normalizedIsbn13OrNull()
      }
    } catch (_: Exception) {
      return null
    }
  }

  private fun String.normalizedIsbn13OrNull(): String? {
    val digits = filter(Char::isDigit)
    if (digits.length != ISBN_13_LENGTH || !(digits.startsWith("978") || digits.startsWith("979"))) {
      return null
    }
    val sum =
      digits.mapIndexed { index, character ->
        character.digitToInt() * if (index % 2 == 0) 1 else 3
      }.sum()
    return digits.takeIf { sum % 10 == 0 }
  }

  private fun MediaContentStream.asInputStream(): InputStream =
    object : InputStream() {
      private val singleByte = ByteArray(1)

      override fun read(): Int =
        if (read(singleByte, 0, 1) < 0) {
          -1
        } else {
          singleByte[0].toInt() and 0xff
        }

      override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
      ): Int = this@asInputStream.read(buffer, offset, length)

      override fun close() = this@asInputStream.close()
    }

  private companion object {
    const val FIRST_PAGE_COUNT = 3
    const val LAST_PAGE_COUNT = 3
    const val ISBN_13_LENGTH = 13
    const val MAXIMUM_SCAN_DIMENSION = 4_096
    const val MAXIMUM_SCAN_PIXELS = MAXIMUM_SCAN_DIMENSION.toLong() * MAXIMUM_SCAN_DIMENSION
  }
}
