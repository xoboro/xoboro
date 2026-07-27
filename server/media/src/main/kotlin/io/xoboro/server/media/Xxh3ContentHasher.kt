package io.xoboro.server.media

import com.appmattus.crypto.Algorithm
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Komga-compatible content hashing backed by seeded XXH3-128.
 *
 * JPEG page hashes are computed after decoding and encoding the pixels again so
 * archives that only differ in EXIF metadata still produce the same page hash.
 */
class Xxh3ContentHasher(
  private val maximumDecodedPixels: Long = 100_000_000,
) {
  init {
    require(maximumDecodedPixels > 0) { "Maximum decoded pixels must be positive" }
  }

  fun hash(path: Path): String = Files.newInputStream(path).use(::hash)

  fun hash(input: InputStream): String {
    val digest = Algorithm.XXH3_128.Seeded(0).createDigest()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      if (read > 0) digest.update(buffer, 0, read)
    }
    return digest.digest().toHexString()
  }

  fun hashPage(
    input: InputStream,
    mediaType: String,
  ): String =
    if (mediaType == JPEG_MEDIA_TYPE) {
      hashNormalizedJpeg(input)
    } else {
      hash(input)
    }

  private fun hashNormalizedJpeg(input: InputStream): String {
    val image =
      requireNotNull(ImageIO.createImageInputStream(input)) {
        "JPEG page is not a supported image"
      }.use { imageInput ->
        val readers = ImageIO.getImageReaders(imageInput)
        require(readers.hasNext()) { "JPEG page is not a supported image" }
        val reader = readers.next()
        try {
          reader.setInput(imageInput, true, true)
          val width = reader.getWidth(0)
          val height = reader.getHeight(0)
          require(width > 0 && height > 0) { "JPEG page dimensions are invalid" }
          require(width.toLong() * height <= maximumDecodedPixels) {
            "JPEG page exceeds the decoded image safety limit"
          }
          reader.read(0)
        } finally {
          reader.dispose()
        }
      }
    val encodable =
      if (image.type == BufferedImage.TYPE_INT_RGB) {
        image
      } else {
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { target ->
          target.createGraphics().use { graphics ->
            graphics.drawImage(image, 0, 0, null)
          }
        }
      }
    return ByteArrayOutputStream().use { output ->
      check(ImageIO.write(encodable, "jpeg", output)) { "No JPEG image writer is available" }
      hash(output.toByteArray().inputStream())
    }
  }

  private fun ByteArray.toHexString(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

  companion object {
    private const val DEFAULT_BUFFER_SIZE: Int = 32 * 1_024
    private const val JPEG_MEDIA_TYPE: String = "image/jpeg"
  }
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R =
  try {
    block(this)
  } finally {
    dispose()
  }
