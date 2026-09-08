/** The native page endpoint refuses larger transformation bounds. */
export const MAXIMUM_PAGE_WIDTH = 4096

/**
 * Returns the width bound needed for one rendered page, or null for source delivery.
 *
 * A split view shows half the stored spread but still downloads the whole image, so it
 * needs twice the pixels of a whole-page slot. Manifest width prevents a pointless
 * decode/re-encode when the stored page already fits the target.
 */
export function maximumPageWidth({
  sourceWidth,
  displayWidth,
  devicePixelRatio = 1,
  split = false,
}) {
  const cssWidth = Number(displayWidth)
  const density = Number(devicePixelRatio)
  if (!Number.isFinite(cssWidth) || cssWidth <= 0) return null
  const safeDensity = Number.isFinite(density) && density > 0 ? density : 1
  const target = Math.min(
    MAXIMUM_PAGE_WIDTH,
    Math.max(1, Math.ceil(cssWidth * safeDensity * (split ? 2 : 1))),
  )
  const storedWidth = Number(sourceWidth)
  if (Number.isFinite(storedWidth) && storedWidth > 0 && storedWidth <= target) return null
  return target
}
