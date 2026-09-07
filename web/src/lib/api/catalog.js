import { request, resourceUrl } from '../http.js'

/**
 * Catalog reading: series, media items, and the named discovery feeds.
 */

/**
 * The feeds whose ordering the server owns.
 *
 * Each is expressible with the parameters the plain listings already accept, and that
 * is the point: when every client decides for itself what "latest" sorts by, two
 * clients showing a shelf with the same label show different shelves, and a report
 * that "latest is wrong" cannot be answered because nothing ever said what right was.
 *
 * A feed **rejects** `sort` with `400 invalid_query` rather than ignoring it, so
 * passing one is a client defect. Nothing here accepts a sort.
 */
export const FEEDS = Object.freeze({
  new: { collections: ['series', 'media-items'] },
  updated: { collections: ['series', 'media-items'] },
  'recently-read': { collections: ['series', 'media-items'] },
  'on-deck': { collections: ['media-items'] },
  'keep-reading': { collections: ['media-items'] },
})

/**
 * Reads a named feed.
 *
 * @param {'series'|'media-items'} collection
 * @param {keyof FEEDS} feed
 * @param {{size?: number}} [options] paging only — a feed owns its order.
 */
export function readFeed(collection, feed, { size = 20, libraryId = null, signal } = {}) {
  const definition = FEEDS[feed]
  if (!definition) throw new Error(`unknown feed: ${feed}`)
  if (!definition.collections.includes(collection)) {
    // on-deck and keep-reading describe what *this reader* has started, which is a
    // property of an item and not of a series. Asking for them on series would be a
    // question with no meaning rather than an empty answer.
    throw new Error(`the ${feed} feed does not exist for ${collection}`)
  }
  // A feed refuses `sort` — its ordering is part of its definition — but it does take
  // the library filter, and it has to: a reader who has narrowed the shelf to one
  // library would otherwise still be offered chapters from the other one.
  return request(`/${collection}/feeds/${feed}`, { query: { size, libraryId }, signal })
}

export function listSeries({ page = 0, size = 50, libraryId = null, query = null, signal } = {}) {
  return request('/series', { query: { page, size, libraryId, query }, signal })
}

export function readSeries(seriesId) {
  return request(`/series/${seriesId}`)
}

/** Series listing orders, as the `sort` parameter spells them. */
export const SeriesOrder = Object.freeze({
  NEWEST: 'number,desc',
  OLDEST: 'number,asc',
})

/**
 * Lists a series' media items, newest chapter first by default.
 *
 * Ordering is asked of the server rather than applied to the returned array. The
 * listing is paged, so reversing a page client-side would show the oldest hundred
 * backwards and label it "newest" — right for a short series and quietly wrong for a
 * long one.
 *
 * Only `number` is offered as the field. Sorting by title is what puts chapter 10
 * before chapter 2, which reads as corrupted data rather than a chosen order.
 */
export function listSeriesMediaItems(
  seriesId,
  { page = 0, size = 100, sort = SeriesOrder.NEWEST } = {},
) {
  return request(`/series/${seriesId}/media-items`, { query: { page, size, sort } })
}

/**
 * The item a reader would resume, or `null` when they have not started the series.
 *
 * Asked of the server in two steps because "resume" means two different things: an
 * item left part-read is where the reader actually stopped, and once none is
 * part-read the next unread one is where they are going. Both filters are resolved
 * in SQL over the whole series, so neither depends on how the listing happens to be
 * paged.
 */
export async function readResumePoint(seriesId) {
  for (const filter of [{ keepReading: true }, { onDeck: true }]) {
    const page = await request(`/series/${seriesId}/media-items`, {
      query: { page: 0, size: 1, sort: SeriesOrder.OLDEST, ...filter },
    })
    const [item] = page.items ?? []
    if (item) return item
  }
  return null
}

export function readMediaItem(mediaItemId) {
  return request(`/media-items/${mediaItemId}`)
}

/**
 * Walks the in-series reading order.
 *
 * The ends answer `404` rather than wrapping around, so a caller gets "there is no
 * next one" instead of silently starting over.
 *
 * @returns the neighbouring item, or `null` at the end of the series.
 */
export async function readNeighbour(mediaItemId, direction) {
  if (direction !== 'previous' && direction !== 'next') {
    throw new Error(`unknown direction: ${direction}`)
  }
  try {
    return await request(`/media-items/${mediaItemId}/${direction}`)
  } catch (error) {
    if (error.code === 'media_item_not_found' || error.code === 'not_found') return null
    throw error
  }
}

export function listMediaItems({ page = 0, size = 50, libraryId = null, seriesId = null } = {}) {
  return request('/media-items', { query: { page, size, libraryId, seriesId } })
}

/**
 * The selected artwork for a series or media item.
 *
 * There is deliberately no page-render fallback on the server: artwork is produced
 * during scanning, so its absence is a real state and a `404` here is information
 * rather than a failure to work around. Callers render a placeholder locally instead
 * of asking the server to synthesize one per request.
 */
export function artworkUrl(kind, id) {
  const collection = kind === 'series' ? 'series' : 'media-items'
  return resourceUrl(`/${collection}/${id}/artwork`)
}

/** The maximum `maxDimension` the page endpoint accepts. */
export const MAX_PAGE_DIMENSION = 4096

/**
 * A page's bytes.
 *
 * `format=source` returns the stored bytes without re-encoding and **cannot** be
 * combined with `maxDimension`; the server rejects the pair rather than ignoring one
 * of them, so the combination is refused here instead of producing a 400 later.
 */
export function pageUrl(mediaItemId, pageNumber, { format = null, maxDimension = null } = {}) {
  if (format === 'source' && maxDimension !== null) {
    throw new Error('format=source cannot be combined with maxDimension')
  }
  if (maxDimension !== null && (maxDimension <= 0 || maxDimension > MAX_PAGE_DIMENSION)) {
    throw new Error(`maxDimension must be between 1 and ${MAX_PAGE_DIMENSION}`)
  }
  const search = new URLSearchParams()
  if (format) search.set('format', format)
  if (maxDimension !== null) search.set('maxDimension', String(maxDimension))
  const query = search.toString()
  return resourceUrl(`/media-items/${mediaItemId}/pages/${pageNumber}${query ? `?${query}` : ''}`)
}

/** The page manifest: one-based `number`, `mediaType`, and optional dimensions. */
export function listPages(mediaItemId) {
  return request(`/media-items/${mediaItemId}/pages`)
}

/**
 * The EPUB's reading positions, in reading order.
 *
 * The only response that carries it. `/resources` lists container entries in stored
 * order, which is OPF **manifest** order — the sequence the packager happened to write
 * — so a reader that followed it would present chapters in an arbitrary order. Each
 * position's `href` matches a resource-manifest path and is requested verbatim.
 *
 * A non-EPUB item answers with an empty list: it has pages, not positions.
 */
export function listPositions(mediaItemId) {
  return request(`/media-items/${mediaItemId}/positions`)
}

/**
 * One EPUB container resource.
 *
 * The path must come from the resource manifest or a position's `href` and be sent
 * **verbatim**. Indexed paths are already resolved relative to the OPF directory, so a
 * client that parses the OPF itself and sends its raw hrefs gets 404s. Resolution is an
 * exact index lookup rather than a filesystem join, which is why traversal is
 * structurally impossible rather than merely filtered.
 */
export function resourceUrlFor(mediaItemId, path) {
  // Each segment is encoded separately so the slashes that make up the archive path
  // survive; encoding the whole string would turn them into %2F and miss the index.
  const encoded = path.split('/').map(encodeURIComponent).join('/')
  return resourceUrl(`/media-items/${mediaItemId}/resources/${encoded}`)
}

/**
 * What a delivery failure means for a retry.
 *
 * Only `media_not_ready` is worth trying again: the item is still being analyzed.
 * `media_unsupported` means the file can never be delivered as it stands — an
 * encrypted archive or document — and `page_not_decodable` means this particular page
 * is broken. Presenting all three as "try again" would send a reader back to a page
 * that will never load.
 */
export const RETRYABLE_DELIVERY_CODES = Object.freeze(['media_not_ready'])

export function isRetryableDeliveryFailure(error) {
  return RETRYABLE_DELIVERY_CODES.includes(error?.code)
}
