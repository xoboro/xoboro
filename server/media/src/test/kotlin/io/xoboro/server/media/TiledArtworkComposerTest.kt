package io.xoboro.server.media

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiledArtworkComposerTest {
  @Test
  fun `tiles four covers into a two by two mosaic`() {
    val composed =
      requireNotNull(
        TiledArtworkComposer(cellDimension = 100).compose(
          listOf(
            cover(Color.RED),
            cover(Color.GREEN),
            cover(Color.BLUE),
            cover(Color.YELLOW),
          ),
        ),
      )

    val image = requireNotNull(decode(composed))
    assertEquals(200, image.width)
    // Each cell keeps the source cover's 2:3 ratio, so the mosaic is cover-shaped rather than square.
    // A 1x2 strip would be the only differently-proportioned thing in a grid of covers.
    assertEquals(300, image.height)

    // One colour per quadrant, in reading order. Compared with a tolerance because the composed image
    // is JPEG - the composer emits what SafeJpegArtworkProcessor consumes - and exact values would be
    // asserting the encoder's quality setting rather than the layout.
    image.assertQuadrant(50, 75, Color.RED)
    image.assertQuadrant(150, 75, Color.GREEN)
    image.assertQuadrant(50, 225, Color.BLUE)
    image.assertQuadrant(150, 225, Color.YELLOW)
  }

  @Test
  fun `returns the single cover when there are too few to fill the grid`() {
    val first = cover(Color.RED)

    // Three is the interesting count: leaving a quadrant blank to force a mosaic would read as a failed
    // image rather than as a design, so one member's cover is shown instead. A deliberate floor.
    listOf(
      listOf(first),
      listOf(first, cover(Color.GREEN)),
      listOf(first, cover(Color.GREEN), cover(Color.BLUE)),
    ).forEach { covers ->
      assertTrue(
        first.contentEquals(TiledArtworkComposer().compose(covers)),
        "expected the first cover unchanged for ${covers.size} covers",
      )
    }
  }

  @Test
  fun `ignores empty entries before counting`() {
    val first = cover(Color.RED)
    val composed =
      TiledArtworkComposer(cellDimension = 100).compose(
        listOf(ByteArray(0), first, ByteArray(0), cover(Color.GREEN)),
      )

    // Two usable covers, so the single-cover path - the blanks must not count towards the tile total.
    assertTrue(first.contentEquals(composed))
  }

  @Test
  fun `falls back to the first cover when a member cannot be decoded`() {
    val first = cover(Color.RED)
    val composed =
      TiledArtworkComposer(cellDimension = 100).compose(
        listOf(first, cover(Color.GREEN), cover(Color.BLUE), "not an image".toByteArray()),
      )

    // One unreadable member must not cost the group its cover. Four usable byte arrays but only three
    // decode, so this takes the same path as having only three.
    assertTrue(first.contentEquals(composed))
  }

  @Test
  fun `composes nothing from nothing`() {
    assertNull(TiledArtworkComposer().compose(emptyList()))
    assertNull(TiledArtworkComposer().compose(listOf(ByteArray(0), ByteArray(0))))
  }

  @Test
  fun `takes the mosaic aspect ratio from the first cover`() {
    val wide = cover(Color.RED, width = 300, height = 100)
    val composed =
      requireNotNull(
        TiledArtworkComposer(cellDimension = 90).compose(
          listOf(wide, wide, wide, wide),
        ),
      )

    val image = requireNotNull(decode(composed))
    assertEquals(180, image.width)
    // 1:3 source, so each 90-wide cell is 30 tall.
    assertEquals(60, image.height)
  }

  private fun BufferedImage.assertQuadrant(
    x: Int,
    y: Int,
    expected: Color,
  ) {
    val actual = Color(getRGB(x, y))
    listOf(
      "red" to (expected.red to actual.red),
      "green" to (expected.green to actual.green),
      "blue" to (expected.blue to actual.blue),
    ).forEach { (channel, values) ->
      val (want, got) = values
      assertTrue(
        kotlin.math.abs(want - got) <= JPEG_CHANNEL_TOLERANCE,
        "at ($x, $y) expected $channel near $want but was $got",
      )
    }
  }

  private fun cover(
    color: Color,
    width: Int = 200,
    height: Int = 300,
  ): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
      this.color = color
      fillRect(0, 0, width, height)
      dispose()
    }
    return ByteArrayOutputStream().use { output ->
      // PNG, not JPEG: the source covers must be exact so the tolerance in assertQuadrant covers only
      // the composer's own encoding, not a second lossy pass on the way in.
      check(ImageIO.write(image, "png", output))
      output.toByteArray()
    }
  }

  private fun decode(bytes: ByteArray): BufferedImage? = ImageIO.read(ByteArrayInputStream(bytes))

  private companion object {
    /** Wide enough for JPEG ringing on a hard colour edge, far too narrow to confuse two quadrants. */
    const val JPEG_CHANNEL_TOLERANCE = 12
  }
}
