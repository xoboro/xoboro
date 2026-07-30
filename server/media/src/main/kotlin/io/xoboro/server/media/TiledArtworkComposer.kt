package io.xoboro.server.media

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Composes several member covers into one image for a collection or read list.
 *
 * Tiles **2×2 and only 2×2**, from the first four covers, and returns the first cover unchanged when
 * there are fewer than four. The reason is aspect ratio: halving both the width and the height of a
 * cover-shaped canvas leaves each cell cover-shaped, so a 2×2 mosaic sits in a grid of covers without
 * looking wrong. A 1×2 strip or a 1×3 row does not — it would be the only differently-proportioned
 * thing on the shelf. Leaving cells blank to force a mosaic from two covers is worse still: an empty
 * quadrant reads as a failed image rather than as a design.
 *
 * So a group with three members shows one member's cover rather than a lopsided mosaic. That is a
 * deliberate floor, not a missing case.
 *
 * Output is JPEG at the same media type [SafeJpegArtworkProcessor] produces, because the composed
 * bytes are handed straight back to it for scaling and re-encoding — this only decides the layout.
 */
class TiledArtworkComposer(
  private val cellDimension: Int = DEFAULT_CELL_DIMENSION,
) {
  init {
    require(cellDimension > 0) { "Artwork cell dimension must be positive" }
  }

  /**
   * Returns the composed image, the single cover, or null when [covers] has nothing usable.
   *
   * A cover that cannot be decoded is skipped rather than failing the composition: one unreadable
   * member must not cost the group its cover, and the group has other members precisely so that this
   * can be tolerated.
   */
  fun compose(covers: List<ByteArray>): ByteArray? {
    val usable = covers.filter(ByteArray::isNotEmpty)
    if (usable.isEmpty()) return null
    if (usable.size < TILE_COUNT) return usable.first()
    val decoded = usable.asSequence().mapNotNull(::decodeOrNull).take(TILE_COUNT).toList()
    // Decoding may have thinned the list below the tile count. Falling back to the first *usable*
    // bytes rather than the first decoded one keeps the outcome identical to the not-enough-covers
    // case, including when nothing decoded at all.
    if (decoded.size < TILE_COUNT) return usable.first()

    // Aspect ratio comes from the first cover, so the mosaic is shaped like the covers it is made of
    // rather than always square.
    val ratio = decoded.first().height.toDouble() / decoded.first().width.toDouble()
    val cellWidth = cellDimension
    val cellHeight = maxOf(1, (cellDimension * ratio).toInt())
    val canvas = BufferedImage(cellWidth * COLUMNS, cellHeight * ROWS, BufferedImage.TYPE_INT_RGB)
    canvas.createGraphics().use { graphics ->
      // Filled first: a source cover with an alpha channel would otherwise leave the canvas's default
      // black showing through, and every other artwork path in this module composites onto white.
      graphics.color = Color.WHITE
      graphics.fillRect(0, 0, canvas.width, canvas.height)
      graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      decoded.forEachIndexed { index, cover ->
        graphics.drawImage(
          cover,
          index % COLUMNS * cellWidth,
          index / COLUMNS * cellHeight,
          cellWidth,
          cellHeight,
          null,
        )
      }
    }
    return ByteArrayOutputStream().use { output ->
      check(ImageIO.write(canvas, "jpeg", output)) { "No JPEG image writer is available" }
      output.toByteArray()
    }
  }

  /**
   * Decodes through [ImageIO.read], which returns null for an unsupported format rather than throwing.
   *
   * No decoded-pixel guard here: these bytes are artwork this server already stored, having passed
   * [SafeJpegArtworkProcessor]'s limits on the way in. The guard belongs at the boundary where
   * untrusted bytes arrive, and duplicating it would imply this is such a boundary.
   */
  private fun decodeOrNull(bytes: ByteArray): BufferedImage? =
    runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()

  companion object {
    const val DEFAULT_CELL_DIMENSION: Int = 400
    const val COLUMNS: Int = 2
    const val ROWS: Int = 2

    /** How many covers a mosaic needs. Fewer means the single-cover fallback. */
    const val TILE_COUNT: Int = COLUMNS * ROWS
  }
}

private inline fun <T : Graphics2D, R> T.use(block: (T) -> R): R =
  try {
    block(this)
  } finally {
    dispose()
  }
