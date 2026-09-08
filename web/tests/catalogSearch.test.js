import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  ONE_SHOT_CHOICES,
  SCOPES,
  SCOPE_NAMES,
  SERIES_FACET_FILTERS,
  UNFILTERABLE_FACETS,
  readSeriesFilterChoices,
  searchCatalog,
} from '../src/lib/api/catalogSearch.js'
import { FACETS } from '../src/lib/api/metadata.js'

/**
 * What the search layer is allowed to put on the wire.
 *
 * The failure this file is built around is not a rejected request — it is an accepted
 * one that does nothing. `/media-items` takes `publisher`, `genre`, `tag`, `language`
 * and `oneShot` in its query string, applies none of them, and answers `200` with the
 * whole catalog. Probed against a running server before these tests were written; a
 * mocked test cannot discover it, which is exactly why the parameter list has to be
 * pinned here rather than assumed to be shared between the two listings.
 */
function reply(body, status = 200) {
  const text = JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => text,
    json: async () => JSON.parse(text),
  }
}

function envelope(items = []) {
  return {
    items,
    page: 0,
    size: DEFAULT_PAGE_SIZE,
    totalItems: items.length,
    totalPages: 1,
    hasPrevious: false,
    hasNext: false,
  }
}

/** Records every requested URL and answers each with `body`. */
function recordingFetch(body = envelope()) {
  const urls = []
  const fetch = vi.fn(async (url) => {
    urls.push(url)
    return reply(body)
  })
  return { fetch, urls }
}

/** The query parameters of the single request that was made. */
function askedParameters(urls) {
  expect(urls.length, 'exactly one request was expected').toBe(1)
  return new URL(urls[0], 'http://localhost').searchParams
}

beforeEach(() => {
  globalThis.fetch = undefined
})

describe('series search parameters', () => {
  it('sends the query, the libraries, all four facet filters, oneShot and the sort', async () => {
    const { fetch, urls } = recordingFetch()
    globalThis.fetch = fetch

    await searchCatalog('series', {
      query: 'lantern',
      libraryId: ['lib-one', 'lib-two'],
      genre: ['gen-a'],
      tag: ['tag-a', 'tag-b'],
      publisher: ['pub-a'],
      language: ['la'],
      oneShot: 'only',
      page: 2,
      size: 30,
      sort: { field: 'mediaItemCount', direction: 'desc' },
    })

    const asked = askedParameters(urls)
    expect(urls[0]).toContain('/series?')
    expect(asked.get('query')).toBe('lantern')
    // Repeated rather than comma-joined. One `libraryId=a,b` filters by an identifier
    // that does not exist, and the listing answers an empty page rather than an error.
    expect(asked.getAll('libraryId')).toEqual(['lib-one', 'lib-two'])
    expect(asked.getAll('genre')).toEqual(['gen-a'])
    expect(asked.getAll('tag')).toEqual(['tag-a', 'tag-b'])
    expect(asked.getAll('publisher')).toEqual(['pub-a'])
    expect(asked.getAll('language')).toEqual(['la'])
    expect(asked.get('oneShot')).toBe('true')
    expect(asked.get('page')).toBe('2')
    expect(asked.get('size')).toBe('30')
    // `field,direction` as one value. The server splits on the comma after
    // percent-decoding, which was verified against a running server.
    expect(asked.get('sort')).toBe('mediaItemCount,desc')
  })

  it('omits a blank query rather than sending an empty one', async () => {
    const { fetch, urls } = recordingFetch()
    globalThis.fetch = fetch

    await searchCatalog('series', { query: '   ' })

    expect(askedParameters(urls).has('query')).toBe(false)
  })

  it('maps the three oneShot choices onto the tri-state the server accepts', async () => {
    const expected = { any: null, only: 'true', exclude: 'false' }
    // Asserted for every declared choice, so adding a fourth without a mapping fails
    // here instead of sending the word "maybe" and collecting a 400.
    expect(Object.keys(expected).sort()).toEqual([...ONE_SHOT_CHOICES].sort())

    for (const [choice, wire] of Object.entries(expected)) {
      const { fetch, urls } = recordingFetch()
      globalThis.fetch = fetch
      await searchCatalog('series', { oneShot: choice })
      expect(askedParameters(urls).get('oneShot'), `oneShot=${choice}`).toBe(wire)
    }
  })

  it('refuses a oneShot value the server would reject', async () => {
    globalThis.fetch = recordingFetch().fetch
    // `oneShot` is `toBooleanStrictOrNull` on the server: anything else is
    // `400 invalid_query`, so it is caught here where the caller can see why.
    expect(() => searchCatalog('series', { oneShot: 'maybe' })).toThrow(/oneShot/)
  })

  it('never sends trashed, which would replace the results with unreadable entries', async () => {
    const { fetch, urls } = recordingFetch()
    globalThis.fetch = fetch

    await searchCatalog('series', { query: 'x', trashed: true })

    // `trashed=true` selects trashed entries *instead of* live ones. A reader who
    // reached it would be looking at a list of things they cannot open, so the
    // parameter is dropped rather than passed through from criteria.
    expect(askedParameters(urls).has('trashed')).toBe(false)
  })
})

describe('media-item search parameters', () => {
  it('sends the query, the libraries, the two progress filters and the sort', async () => {
    const { fetch, urls } = recordingFetch()
    globalThis.fetch = fetch

    await searchCatalog('mediaItems', {
      query: 'volume',
      libraryId: ['lib-one'],
      onDeck: true,
      keepReading: true,
      sort: { field: 'seriesLastReadAt', direction: 'desc' },
    })

    const asked = askedParameters(urls)
    expect(urls[0]).toContain('/media-items?')
    expect(asked.get('query')).toBe('volume')
    expect(asked.getAll('libraryId')).toEqual(['lib-one'])
    expect(asked.get('onDeck')).toBe('true')
    expect(asked.get('keepReading')).toBe('true')
    expect(asked.get('sort')).toBe('seriesLastReadAt,desc')
  })

  it('omits the progress filters when they are not set', async () => {
    const { fetch, urls } = recordingFetch()
    globalThis.fetch = fetch

    await searchCatalog('mediaItems', { onDeck: false, keepReading: false })

    const asked = askedParameters(urls)
    expect(asked.has('onDeck')).toBe(false)
    expect(asked.has('keepReading')).toBe(false)
  })

  it('drops every series filter, because the media-item listing ignores them silently', async () => {
    const { fetch, urls } = recordingFetch()
    globalThis.fetch = fetch

    await searchCatalog('mediaItems', {
      query: 'volume',
      genre: ['gen-a'],
      tag: ['tag-a'],
      publisher: ['pub-a'],
      language: ['la'],
      oneShot: 'only',
    })

    const asked = askedParameters(urls)
    // Each of these was sent to a running `/media-items` and returned all twenty items
    // in a twenty-item library: accepted, ignored, and reported as success. A filter
    // that reaches the server and changes nothing is worse than one that is refused.
    for (const ignored of ['genre', 'tag', 'publisher', 'language', 'oneShot']) {
      expect(asked.has(ignored), `${ignored} must not be sent to /media-items`).toBe(false)
    }
    // The query itself still went, so this is not passing because nothing was sent.
    expect(asked.get('query')).toBe('volume')
  })
})

describe('sorts', () => {
  it('accepts every field it offers for a scope', async () => {
    for (const scope of SCOPE_NAMES) {
      for (const field of SCOPES[scope].sorts) {
        const { fetch, urls } = recordingFetch()
        globalThis.fetch = fetch
        await searchCatalog(scope, { sort: { field, direction: 'asc' } })
        expect(askedParameters(urls).get('sort'), `${scope}/${field}`).toBe(`${field},asc`)
      }
    }
  })

  it('refuses a field belonging to the other collection', async () => {
    globalThis.fetch = recordingFetch().fetch
    // `mediaItemCount` is a series sort and `fileSize` a media-item one. Sending either
    // to the wrong listing is `400 invalid_query`, so it fails here — where the message
    // names the mistake — rather than as a search that came back broken.
    expect(() => searchCatalog('mediaItems', { sort: { field: 'mediaItemCount' } })).toThrow(
      /mediaItemCount/,
    )
    expect(() => searchCatalog('series', { sort: { field: 'fileSize' } })).toThrow(/fileSize/)
  })

  it('refuses a direction that is neither asc nor desc', async () => {
    globalThis.fetch = recordingFetch().fetch
    expect(() =>
      searchCatalog('series', { sort: { field: 'title', direction: 'sideways' } }),
    ).toThrow(/sideways/)
  })

  it('defaults each scope to the order its route already uses', () => {
    // Not a restatement of the constant: the point is that the field named is one the
    // same scope accepts, so the very first request a screen makes cannot be refused.
    for (const scope of SCOPE_NAMES) {
      const { field, direction } = SCOPES[scope].defaultSort
      expect(SCOPES[scope].sorts, `${scope} default`).toContain(field)
      expect(['asc', 'desc']).toContain(direction)
    }
  })
})

describe('paging bounds', () => {
  it('refuses a size the listing would reject', async () => {
    globalThis.fetch = recordingFetch().fetch
    expect(() => searchCatalog('series', { size: 0 })).toThrow(/size/)
    expect(() => searchCatalog('series', { size: MAX_PAGE_SIZE + 1 })).toThrow(/size/)
  })

  it('refuses a negative page', async () => {
    globalThis.fetch = recordingFetch().fetch
    expect(() => searchCatalog('series', { page: -1 })).toThrow(/page/)
  })

  it('defaults to a size the listing accepts', async () => {
    const { fetch, urls } = recordingFetch()
    globalThis.fetch = fetch
    await searchCatalog('series', {})
    expect(Number(askedParameters(urls).get('size'))).toBeLessThanOrEqual(MAX_PAGE_SIZE)
    expect(Number(askedParameters(urls).get('size'))).toBe(DEFAULT_PAGE_SIZE)
  })
})

describe('unknown scope', () => {
  it('is refused rather than requested', async () => {
    globalThis.fetch = recordingFetch().fetch
    expect(() => searchCatalog('everything', {})).toThrow(/everything/)
  })
})

describe('facet-backed filter choices', () => {
  it('pairs each listing parameter with the facet that actually enumerates it', () => {
    // The one that is not the same word. The listing parameter is `tag`; the facet that
    // lists a series' tags is `seriesTag`. `bookTag` is a real facet with real values
    // and no series parameter, so populating the tag filter from it would offer values
    // that match nothing — an empty result set that looks like an empty library.
    expect(SERIES_FACET_FILTERS).toContainEqual({ parameter: 'tag', facet: 'seriesTag' })
    for (const { facet } of SERIES_FACET_FILTERS) {
      expect(FACETS, `${facet} is not a facet the server accepts`).toContain(facet)
    }
  })

  it('accounts for every facet the server offers, as a filter or as not filterable', () => {
    // The two lists together must cover `/facets` exactly. A facet in neither is one
    // nobody has decided about, which is how an unusable filter gets added later.
    const covered = [...SERIES_FACET_FILTERS.map(({ facet }) => facet), ...UNFILTERABLE_FACETS]
    expect([...covered].sort()).toEqual([...FACETS].sort())
  })

  it('reads one facet per filter and keys the answers by listing parameter', async () => {
    const answers = {
      genre: ['gen-a', 'gen-b'],
      seriesTag: ['tag-a'],
      publisher: ['pub-a'],
      language: ['la'],
    }
    globalThis.fetch = vi.fn(async (url) => {
      const facet = new URL(url, 'http://localhost').searchParams.get('facet')
      return reply(answers[facet] ?? [])
    })

    const { choices, failed } = await readSeriesFilterChoices()

    expect(failed).toEqual([])
    // Keyed by the parameter, not the facet: the filter group has to send `tag`.
    expect(choices).toEqual({
      genre: ['gen-a', 'gen-b'],
      tag: ['tag-a'],
      publisher: ['pub-a'],
      language: ['la'],
    })
  })

  it('names the facet that failed and keeps the ones that answered', async () => {
    globalThis.fetch = vi.fn(async (url) => {
      const facet = new URL(url, 'http://localhost').searchParams.get('facet')
      if (facet === 'publisher') return reply({ code: 'internal_error', message: 'no' }, 500)
      return reply([`${facet}-value`])
    })

    const { choices, failed } = await readSeriesFilterChoices()

    // Reported, not swallowed: a group with no choices renders as nothing at all, so
    // "this library has no publishers" and "the publisher list could not be read" are
    // the same picture unless the caller is told which happened.
    expect(failed).toEqual(['publisher'])
    // And the three that answered are still usable — one dead facet does not cost them.
    expect(Object.keys(choices).sort()).toEqual(['genre', 'language', 'tag'])
    expect(choices.tag).toEqual(['seriesTag-value'])
  })

  it('uses one abort signal for every facet choice request', async () => {
    const controller = new AbortController()
    const signals = []
    globalThis.fetch = vi.fn(async (url, init = {}) => {
      if (url.includes('/facets')) signals.push(init.signal)
      return reply([])
    })

    await readSeriesFilterChoices({ signal: controller.signal })

    expect(signals).toHaveLength(SERIES_FACET_FILTERS.length)
    expect(signals.every((signal) => signal === controller.signal)).toBe(true)
  })
})
