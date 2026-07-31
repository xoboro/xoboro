/**
 * Turning a page manifest into the things a reader actually looks at.
 *
 * Pure functions, separated from the component because these three are the reader's
 * hard parts and each one fails in a way that looks like something else:
 *
 * - Getting spread splitting wrong shows a right-to-left comic in the wrong order,
 *   which reads as bad source data.
 * - Losing the aspect ratio collapses every slot to zero height, which defeats lazy
 *   loading — every image loads at once and the reader stalls.
 * - Getting the load priority wrong lets prefetches starve the page in front of the
 *   reader, which looks like a slow server.
 */

/** Reading directions the splitter understands. */
export const DIRECTIONS = Object.freeze(['ltr', 'rtl'])

/**
 * Builds the sequence of views for a page manifest.
 *
 * `width` and `height` are carried through so each slot can reserve its aspect ratio
 * **before** its image loads. Without that the slot has no height, every slot is
 * inside the viewport at once, and native lazy loading has nothing to defer.
 *
 * When splitting, a landscape page is treated as a two-page spread and cut in half;
 * a portrait page is left whole. The halves are ordered by reading direction, so a
 * right-to-left comic gets the right half first — the half a reader turns to.
 *
 * @param {Array<{number: number, width?: number, height?: number}>} pages
 * @param {boolean} split
 * @param {'ltr'|'rtl'} direction
 */
export function buildViews(pages, split, direction) {
  if (!DIRECTIONS.includes(direction)) throw new Error(`unknown direction: ${direction}`)
  const whole = (page) => ({
    page: page.number,
    half: null,
    width: page.width ?? null,
    height: page.height ?? null,
  })

  if (!split) return pages.map(whole)

  const order = direction === 'rtl' ? ['R', 'L'] : ['L', 'R']
  return pages.flatMap((page) => {
    // A page with no dimensions cannot be classified, so it is left whole rather than
    // guessed at — half of an unknown page is worse than all of it.
    if (!page.width || !page.height) return [whole(page)]
    if (page.width <= page.height) return [whole(page)]
    return order.map((half) => ({
      page: page.number,
      half,
      width: page.width / 2,
      height: page.height,
    }))
  })
}

/** A stable key for a view, since two views can share a page number when split. */
export function viewKey(view) {
  return `${view.page}${view.half ?? ''}`
}

/** The index of the view showing a page, or 0 when it is not present. */
export function indexOfPage(views, page) {
  const found = views.findIndex((view) => view.page === page)
  return found >= 0 ? found : 0
}

/**
 * Load priority for a page, lower first.
 *
 * The page the reader is on wins outright. Pages ahead of it come next, in order,
 * because that is where the reader is going. Pages behind are filled in last — they
 * are worth having for a scroll back, but not at the cost of the page being looked at.
 *
 * The offset for a page behind starts above every possible forward page, so no
 * backward page can ever outrank a forward one however long the item is.
 *
 * @param {number} page
 * @param {number} current
 * @param {number} pageCount
 */
export function pageLoadPriority(page, current, pageCount) {
  if (page === current) return 0
  const total = Math.max(pageCount, 1)
  if (page > current) return page - current
  return total + (current - page)
}

/**
 * Reserves a slot's shape before its image arrives.
 *
 * Returns a CSS `aspect-ratio` value, or `null` when the manifest did not say. A
 * missing ratio is left unset rather than defaulted: a wrong reservation shifts the
 * page under the reader's thumb when the real image lands, which is worse than a slot
 * that grows once.
 */
export function aspectRatio(view) {
  if (!view.width || !view.height) return null
  return `${view.width} / ${view.height}`
}
