package io.xoboro.server.media

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins what WebP support Xoboro actually has, in both directions.
 *
 * The JDK ships neither a WebP reader nor a WebP writer (verified on Temurin 26:
 * `ImageIO.getWriterFormatNames()` returns only BMP, GIF, JPEG, PNG, TIFF and
 * WBMP). Meanwhile `LocalSourceArtworkAccess` offers to discover `cover.webp`, so
 * before the reader was added a perfectly ordinary WebP cover became a
 * permanently failing artwork task whose message read "Uploaded artwork is not a
 * supported image" — about a file nobody uploaded.
 *
 * Reading is therefore supported and asserted. Writing is deliberately **not**
 * supported, and that is asserted too: see docs/architecture/0104-webp-support.md.
 * An assertion is the only thing that stops a future dependency bump from quietly
 * enabling WebP output that no design decision asked for.
 */
class WebPArtworkTest {
  @Test
  fun `a WebP reader is available`() {
    assertTrue(
      ImageIO.getImageReadersByFormatName("webp").hasNext(),
      "no WebP reader is registered; a discovered cover.webp will fail to decode",
    )
  }

  @Test
  fun `no WebP writer is available`() {
    // Declined on purpose. The only candidates that write WebP bind to a native
    // libwebp, which would make the container image architecture-specific for a
    // smaller thumbnail.
    assertFalse(
      ImageIO.getImageWritersByFormatName("webp").hasNext(),
      "a WebP writer appeared; ADR 0104 declines WebP output, so this is a dependency" +
        " change nobody decided on",
    )
  }

  @Test
  fun `decodes a WebP cover into JPEG artwork`() {
    // End to end through the real processor, which is where the failure happened:
    // it asks ImageIO for a reader and rejects the image when there is none.
    val processed = SafeJpegArtworkProcessor().process(syntheticWebP())

    assertEquals("image/jpeg", processed.mediaType)
    assertEquals(1, processed.width)
    assertEquals(1, processed.height)
    assertTrue(processed.bytes.isNotEmpty())
  }

  @Test
  fun `still rejects a file that only claims to be an image`() {
    // The reader is chosen by content, not by name, so adding WebP support must not
    // turn the "not a supported image" guard into something that accepts anything.
    val failure =
      runCatching { SafeJpegArtworkProcessor().process("not an image".toByteArray()) }
        .exceptionOrNull()
    assertNotNull(failure)
  }

  /**
   * A 1×1 lossless WebP, hand-built rather than generated.
   *
   * Nothing in the toolchain can write WebP - that is the whole point of this file -
   * so the fixture is a checked-in VP8L bitstream: one opaque red pixel encoded with
   * single-symbol Huffman codes and no transforms.
   */
  private fun syntheticWebP(): ByteArray =
    requireNotNull(javaClass.getResourceAsStream("/artwork/synthetic-1x1.webp")) {
      "the synthetic WebP fixture is missing"
    }.use { it.readBytes() }
}
