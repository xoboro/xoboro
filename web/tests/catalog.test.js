import { describe, expect, it, vi } from 'vitest'
import {
  FEEDS,
  MAX_PAGE_DIMENSION,
  SeriesOrder,
  artworkUrl,
  listSeriesMediaItems,
  pageUrl,
  readFeed,
  readNeighbour,
  readSeriesReaderContext,
} from '../src/lib/api/catalog.js'

function reply(body, status = 200) {
  const text = body === null ? '' : JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => text,
    json: async () => JSON.parse(text),
  }
}

describe('named feeds', () => {
  it('never sends a sort to a feed route', async () => {
    // A feed rejects `sort` with 400 invalid_query rather than ignoring it, so passing
    // one is a client defect. There is no parameter here that could carry it.
    const fetchImpl = vi.fn().mockResolvedValue(reply({ items: [] }))
    globalThis.fetch = fetchImpl

    await readFeed('series', 'new')
    const [url] = fetchImpl.mock.calls[0]
    expect(url).toContain('/series/feeds/new')
    expect(url).not.toContain('sort')
  })

  it('refuses a feed that does not exist for a collection', () => {
    // on-deck and keep-reading are about what *this reader* has started, which is a
    // property of an item rather than of a series. Asking on series is a question with
    // no meaning, not an empty answer.
    expect(() => readFeed('series', 'on-deck')).toThrow(/does not exist for series/)
    expect(() => readFeed('series', 'keep-reading')).toThrow(/does not exist for series/)
    expect(() => readFeed('media-items', 'on-deck')).not.toThrow()
  })

  it('refuses an unknown feed name', () => {
    expect(() => readFeed('series', 'latest')).toThrow(/unknown feed/)
  })

  it('lists all five feeds and where each applies', () => {
    expect(Object.keys(FEEDS)).toEqual([
      'new',
      'updated',
      'recently-read',
      'on-deck',
      'keep-reading',
    ])
    expect(FEEDS['recently-read'].collections).toEqual(['series', 'media-items'])
    expect(FEEDS['keep-reading'].collections).toEqual(['media-items'])
  })
})

describe('reading order', () => {
  it('asks the server for newest-first by default', async () => {
    // Asked of the server rather than applied to the response, because the listing is
    // paged: reversing a page here would show the oldest hundred backwards and label it
    // "newest" — right for a short series and quietly wrong for a long one.
    const fetchImpl = vi.fn().mockResolvedValue(reply({ items: [] }))
    globalThis.fetch = fetchImpl

    await listSeriesMediaItems('s1')
    const [url] = fetchImpl.mock.calls[0]
    expect(url).toContain('/series/s1/media-items')
    expect(decodeURIComponent(url)).toContain('sort=number,desc')
  })

  it('sorts only on number, in either direction', async () => {
    // Sorting by title puts chapter 10 before chapter 2 — an ordering bug that reads as
    // data corruption — so number is the only field either order names.
    const fetchImpl = vi.fn().mockResolvedValue(reply({ items: [] }))
    globalThis.fetch = fetchImpl

    await listSeriesMediaItems('s1', { sort: SeriesOrder.OLDEST })
    expect(decodeURIComponent(fetchImpl.mock.calls[0][0])).toContain('sort=number,asc')
    expect(Object.values(SeriesOrder).every((sort) => sort.startsWith('number,'))).toBe(true)
  })
})

describe('series reader context', () => {
  it('loads the first and resume items in one request', async () => {
    const expected = {
      series: { id: 's1' },
      first: { id: 'b1' },
      resume: { id: 'b7' },
    }
    const fetchImpl = vi.fn().mockResolvedValue(reply(expected))
    globalThis.fetch = fetchImpl

    expect(await readSeriesReaderContext('s1')).toEqual(expected)
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl.mock.calls[0][0]).toContain('/series/s1/reader-context')
  })
})

describe('readNeighbour', () => {
  it('returns null at the end of a series rather than wrapping', async () => {
    // The ends answer 404 on purpose. Wrapping around would silently restart the series
    // instead of saying there is nothing after this.
    globalThis.fetch = vi.fn().mockResolvedValue(reply({ code: 'media_item_not_found' }, 404))
    await expect(readNeighbour('m1', 'next')).resolves.toBeNull()
  })

  it('propagates a failure that is not an end-of-series 404', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(reply({ code: 'page_streaming_forbidden' }, 403))
    await expect(readNeighbour('m1', 'next')).rejects.toMatchObject({
      code: 'page_streaming_forbidden',
    })
  })

  it('rejects a direction it cannot walk', async () => {
    // An async function rejects rather than throwing synchronously, so this awaits the
    // rejection — asserting `toThrow` here would pass on an unhandled promise instead.
    await expect(readNeighbour('m1', 'up')).rejects.toThrow(/unknown direction/)
  })
})

describe('pageUrl', () => {
  it('builds a same-origin page path', () => {
    expect(pageUrl('m1', 3)).toBe('/api/xoboro/v1/media-items/m1/pages/3')
  })

  it('refuses source together with maxDimension', () => {
    // The server rejects the pair rather than ignoring one of them, so the combination
    // fails here instead of producing a 400 after a round trip.
    expect(() => pageUrl('m1', 1, { format: 'source', maxDimension: 800 })).toThrow(
      /cannot be combined/,
    )
    expect(() => pageUrl('m1', 1, { format: 'source' })).not.toThrow()
  })

  it('enforces the server’s dimension cap', () => {
    expect(() => pageUrl('m1', 1, { maxDimension: MAX_PAGE_DIMENSION + 1 })).toThrow(/between 1/)
    expect(() => pageUrl('m1', 1, { maxDimension: 0 })).toThrow(/between 1/)
    expect(pageUrl('m1', 1, { maxDimension: 1600 })).toContain('maxDimension=1600')
  })
})

describe('artworkUrl', () => {
  it('uses the media-items collection for an item and series for a series', () => {
    expect(artworkUrl('series', 's1')).toBe('/api/xoboro/v1/series/s1/artwork')
    expect(artworkUrl('mediaItem', 'm1')).toBe('/api/xoboro/v1/media-items/m1/artwork')
  })
})
