package io.xoboro.server.media

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafeJpegArtworkProcessorTest {
  @Test
  fun `decodes bounds resizes and normalizes uploaded images to jpeg`() {
    val source =
      BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB).also {
        it.createGraphics().let { graphics ->
          try {
            graphics.color = Color(20, 40, 60, 128)
            graphics.fillRect(0, 0, it.width, it.height)
          } finally {
            graphics.dispose()
          }
        }
      }
    val input =
      ByteArrayOutputStream().use { output ->
        ImageIO.write(source, "png", output)
        output.toByteArray()
      }

    val processed = SafeJpegArtworkProcessor(maximumDimension = 100).process(input)

    assertEquals("image/jpeg", processed.mediaType)
    assertEquals(100, processed.width)
    assertEquals(50, processed.height)
    assertTrue(processed.bytes.isNotEmpty())
    val decoded = ImageIO.read(ByteArrayInputStream(processed.bytes))
    assertEquals(100, decoded.width)
    assertEquals(50, decoded.height)
  }

  @Test
  fun `rejects unsupported and oversized decoded images`() {
    assertFailsWith<IllegalArgumentException> {
      SafeJpegArtworkProcessor().process("not-an-image".encodeToByteArray())
    }
    val source = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
    val input =
      ByteArrayOutputStream().use { output ->
        ImageIO.write(source, "png", output)
        output.toByteArray()
      }
    assertFailsWith<IllegalArgumentException> {
      SafeJpegArtworkProcessor(maximumDecodedPixels = 100).process(input)
    }
  }
}
