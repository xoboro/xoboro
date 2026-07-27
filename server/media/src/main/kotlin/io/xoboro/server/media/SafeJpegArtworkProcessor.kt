package io.xoboro.server.media

import io.xoboro.core.application.ArtworkProcessor
import io.xoboro.core.application.ProcessedArtwork
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class SafeJpegArtworkProcessor(
  private val maximumDimension: Int = 1_600,
  private val maximumDecodedPixels: Long = 100_000_000,
) : ArtworkProcessor {
  init {
    require(maximumDimension > 0) { "Maximum artwork dimension must be positive" }
    require(maximumDecodedPixels > 0) { "Maximum decoded pixels must be positive" }
  }

  override fun process(input: ByteArray): ProcessedArtwork {
    val source =
      requireNotNull(ImageIO.createImageInputStream(ByteArrayInputStream(input))) {
        "Uploaded artwork is not a supported image"
      }.use { imageInput ->
        val readers = ImageIO.getImageReaders(imageInput)
        require(readers.hasNext()) { "Uploaded artwork is not a supported image" }
        val reader = readers.next()
        try {
          reader.setInput(imageInput, true, true)
          val width = reader.getWidth(0)
          val height = reader.getHeight(0)
          require(width > 0 && height > 0) { "Uploaded artwork dimensions are invalid" }
          require(width.toLong() * height <= maximumDecodedPixels) {
            "Uploaded artwork exceeds the decoded image safety limit"
          }
          reader.read(0)
        } finally {
          reader.dispose()
        }
      }
    val scale = minOf(1.0, maximumDimension.toDouble() / maxOf(source.width, source.height))
    val width = maxOf(1, (source.width * scale).toInt())
    val height = maxOf(1, (source.height * scale).toInt())
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    target.createGraphics().use { graphics ->
      graphics.color = Color.WHITE
      graphics.fillRect(0, 0, width, height)
      graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      graphics.drawImage(source, 0, 0, width, height, null)
    }
    val bytes =
      ByteArrayOutputStream().use { output ->
        check(ImageIO.write(target, "jpeg", output)) { "No JPEG image writer is available" }
        output.toByteArray()
      }
    return ProcessedArtwork(
      bytes = bytes,
      mediaType = "image/jpeg",
      width = width,
      height = height,
    )
  }
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R =
  try {
    block(this)
  } finally {
    dispose()
  }
