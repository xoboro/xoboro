import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import Home from '../src/reader/Home.svelte'

/**
 * The reader's home page, and what it says when a shelf cannot be read.
 *
 * The four shelves load with `Promise.allSettled`, which is right — a reader with no
 * progress yet gets nothing from keep-reading, and that must not blank the page. But
 * settling the failures also hid a total outage: every discovery feed answered `500` for a
 * while, and the only symptom was four permanently empty shelves. On a new server that is
 * indistinguishable from an empty library, so nobody would report it as a fault.
 */
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

function envelope(items = []) {
  return {
    items,
    page: 0,
    size: 20,
    totalItems: items.length,
    totalPages: 1,
    hasPrevious: false,
    hasNext: false,
  }
}

/** Answers feeds with `status` and everything else with an empty page. */
function server({ feedStatus = 200 } = {}) {
  return vi.fn(async (url) => {
    if (url.includes('/feeds/')) {
      return feedStatus === 200
        ? reply(envelope())
        : reply({ code: 'internal_error', message: 'Unsupported catalog sort property' }, feedStatus)
    }
    return reply(envelope())
  })
}

/**
 * A catalog larger than one page, answered the way the server answers it.
 *
 * `totalItems` and `totalPages` describe the whole set while `items` carries only this
 * page - the distinction the grid used to discard.
 */
function pagedSeriesServer({ totalItems = 3339, size = 100, libraries = [] } = {}) {
  const totalPages = Math.ceil(totalItems / size)
  return vi.fn(async (url) => {
    if (url.includes('/libraries')) return reply(envelope(libraries))
    if (url.includes('/feeds/')) return reply(envelope())
    if (url.includes('/series?')) {
      const page = Number(new URL(url, 'http://localhost').searchParams.get('page') ?? 0)
      const first = page * size
      const count = Math.max(0, Math.min(size, totalItems - first))
      return reply({
        items: Array.from({ length: count }, (_, index) => ({
          id: `series-${first + index}`,
          title: `Synthetic series ${first + index}`,
          mediaItemCount: 1,
        })),
        page,
        size,
        totalItems,
        totalPages,
        hasPrevious: page > 0,
        hasNext: page < totalPages - 1,
      })
    }
    return reply(envelope())
  })
}

describe('Reader home', () => {
  it('reaches a catalog larger than one page', async () => {
    // The grid asked for one page of 100 and rendered it as "all series", so a library of
    // 3,339 showed its first 100 and offered no route to the other 3,239. The listing it
    // calls has taken a `page` since it existed; nothing passed one.
    globalThis.fetch = pagedSeriesServer()
    render(Home)

    await screen.findByText('Synthetic series 0')
    const next = await screen.findByTestId('all-series-page-next')
    expect(next.disabled).toBe(false)

    await fireEvent.click(next)

    // The second page's contents, from the server, and not a slice of the first.
    await screen.findByText('Synthetic series 100')
    expect(screen.queryByText('Synthetic series 0')).toBeNull()
    await waitFor(() => {
      const urls = globalThis.fetch.mock.calls.map(([url]) => url)
      expect(urls.some((url) => url.includes('/series?') && url.includes('page=1'))).toBe(true)
    })
  })

  it('cannot page past either end', async () => {
    globalThis.fetch = pagedSeriesServer({ totalItems: 40 })
    render(Home)

    await screen.findByText('Synthetic series 0')
    // One page holds the whole set, so both directions are dead ends and the envelope says
    // so - `hasNext` and `hasPrevious`, not a count of the rows on screen.
    expect((await screen.findByTestId('all-series-page-previous')).disabled).toBe(true)
    expect((await screen.findByTestId('all-series-page-next')).disabled).toBe(true)
  })

  it('restarts at the first page when the reader narrows to a library', async () => {
    // Page 7 of one library is usually past the end of another, so carrying the page
    // number across a narrowing would ask for a page that does not exist and land the
    // reader on an empty grid - which reads as an empty library.
    globalThis.fetch = pagedSeriesServer({ libraries: TWO_LIBRARIES })
    render(Home)

    await fireEvent.click(await screen.findByTestId('all-series-page-next'))
    await screen.findByText('Synthetic series 100')

    globalThis.fetch.mockClear()
    await fireEvent.click(await screen.findByTestId('library-lib-webtoon'))

    await waitFor(() => {
      const seriesUrls = globalThis.fetch.mock.calls
        .map(([url]) => url)
        .filter((url) => url.includes('/series?'))
      expect(seriesUrls.length).toBeGreaterThan(0)
      // Every request the narrowing makes is for the first page of the new set.
      for (const url of seriesUrls) {
        expect(url).toContain('libraryId=lib-webtoon')
        expect(url).toContain('page=0')
      }
    })
  })

  it('says how many shelves could not be read', async () => {
    globalThis.fetch = server({ feedStatus: 500 })
    render(Home)

    const notice = await screen.findByTestId('shelves-failed')
    // Four shelves, four failures. The count is stated rather than a generic "something
    // went wrong", because "all of them" and "one of them" call for different reactions.
    expect(notice.textContent).toContain('4')
  })

  it('says nothing when every shelf loads, empty or not', async () => {
    // The failure notice must not double as an empty state. Four empty shelves on a fresh
    // server is the normal case and has its own rendering.
    globalThis.fetch = server()
    render(Home)

    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled())
    expect(screen.queryByTestId('shelves-failed')).toBeNull()
  })
})

/** Answers `/libraries` with the given libraries and everything else with an empty page. */
function serverWithLibraries(libraries) {
  return vi.fn(async (url) => {
    if (url.includes('/libraries')) return reply(envelope(libraries))
    return reply(envelope())
  })
}

const TWO_LIBRARIES = [
  { id: 'lib-comics', name: 'comics' },
  { id: 'lib-webtoon', name: 'webtoon' },
]

describe('library switcher', () => {
  it('offers every library plus all of them', async () => {
    globalThis.fetch = serverWithLibraries(TWO_LIBRARIES)
    render(Home)

    await waitFor(() => expect(screen.getByTestId('library-lib-webtoon')).toBeInTheDocument())
    expect(screen.getByTestId('library-lib-comics')).toBeInTheDocument()
    // "All" is a choice of its own, not the absence of one.
    expect(screen.getByTestId('library-all')).toBeInTheDocument()
  })

  it('stays hidden when there is only one library to choose', async () => {
    // A control that cannot change any result costs a reader the time it takes to work
    // that out.
    globalThis.fetch = serverWithLibraries([TWO_LIBRARIES[0]])
    render(Home)

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled())
    expect(screen.queryByTestId('library-all')).toBeNull()
  })

  it('narrows the shelves as well as the grid', async () => {
    // The shelves matter as much as the grid here: a reader who has narrowed to one
    // library would otherwise still be offered chapters from the other one, which reads
    // as the filter not working.
    globalThis.fetch = serverWithLibraries(TWO_LIBRARIES)
    render(Home)

    await fireEvent.click(await screen.findByTestId('library-lib-webtoon'))

    await waitFor(() => {
      const urls = globalThis.fetch.mock.calls.map(([url]) => url)
      expect(urls.some((url) => url.includes('/series?') && url.includes('libraryId=lib-webtoon')))
        .toBe(true)
      expect(urls.some((url) => url.includes('/feeds/') && url.includes('libraryId=lib-webtoon')))
        .toBe(true)
    })
  })

  it('remembers the chosen library for the next visit', async () => {
    globalThis.fetch = serverWithLibraries(TWO_LIBRARIES)
    render(Home)

    await fireEvent.click(await screen.findByTestId('library-lib-webtoon'))

    await waitFor(() =>
      expect(localStorage.getItem('xoboro.pref.anonymous.global.library')).toBe('lib-webtoon'),
    )
  })

  it('stores all-libraries as a decision rather than as no decision', async () => {
    // Empty string, not a removed key: without the distinction, choosing "all" after
    // narrowing could not be persisted and the narrow choice would come back.
    localStorage.setItem('xoboro.pref.anonymous.global.library', 'lib-webtoon')
    globalThis.fetch = serverWithLibraries(TWO_LIBRARIES)
    render(Home)

    await fireEvent.click(await screen.findByTestId('library-all'))

    await waitFor(() =>
      expect(localStorage.getItem('xoboro.pref.anonymous.global.library')).toBe(''),
    )
  })
})

/**
 * Searching without leaving home.
 *
 * The reader this UI is modelled on searches on the home screen: a field above the
 * shelves, results in their place as you type. Xoboro sent a reader to a separate screen
 * for the same thing, so finding one title meant a navigation, a back, and losing the
 * scroll position of the grid they were browsing.
 *
 * The dedicated screen stays — it carries facets, sorts and paging that do not belong
 * above a shelf — but the first keystroke no longer costs a page change.
 */
describe('home search', () => {
  function searchServer({ series = [], items = [] } = {}) {
    return vi.fn(async (url) => {
      if (url.includes('/feeds/')) return reply(envelope())
      if (url.includes('/libraries')) return reply(envelope())
      // A query is only ever sent to the two listings, so the presence of the parameter
      // is what distinguishes a search from the grid's own request.
      if (url.includes('query=')) {
        return reply(envelope(url.includes('/media-items') ? items : series))
      }
      return reply(envelope())
    })
  }

  async function typeQuery(value) {
    const field = await screen.findByTestId('home-search')
    await fireEvent.input(field, { target: { value } })
    return field
  }

  it('shows results in place of the shelves', async () => {
    globalThis.fetch = searchServer({
      series: [{ id: 's1', title: 'Found Series', mediaItemCount: 3 }],
      items: [{ id: 'm1', title: 'Found Chapter', seriesTitle: 'Found Series' }],
    })
    render(Home, {})

    await typeQuery('found')

    await waitFor(() =>
      expect(screen.getByTestId('home-search-results').textContent).toContain('Found Series'),
    )
    expect(screen.getByTestId('home-search-results').textContent).toContain('Found Chapter')
    // The grid the search replaces is gone rather than pushed below the results.
    expect(screen.queryByTestId('all-series-page-next')).toBeNull()
  })

  it('restores the shelves when the query is cleared', async () => {
    globalThis.fetch = searchServer({ series: [{ id: 's1', title: 'Found Series' }] })
    render(Home, {})

    await typeQuery('found')
    await waitFor(() => expect(screen.getByTestId('home-search-results')).toBeInTheDocument())

    await typeQuery('')

    await waitFor(() => expect(screen.queryByTestId('home-search-results')).toBeNull())
  })

  it('says when a query matched nothing, rather than showing an empty page', async () => {
    globalThis.fetch = searchServer()
    render(Home, {})

    await typeQuery('nothing-matches-this')

    await waitFor(() => expect(screen.getByTestId('home-search-empty')).toBeInTheDocument())
  })

  /**
   * A slow first request must not overwrite a faster second one. Typed quickly, "ab"
   * follows "a", and if "a" lands last the reader is looking at results for a query they
   * have already replaced.
   */
  it('discards a response that a later query has superseded', async () => {
    const pending = []
    globalThis.fetch = vi.fn((url) => {
      if (url.includes('query=')) {
        return new Promise((resolve) => {
          pending.push({
            url,
            resolve: (body) => resolve(reply(envelope(body))),
          })
        })
      }
      return Promise.resolve(reply(envelope()))
    })
    render(Home, {})

    await typeQuery('a')
    await waitFor(() => expect(pending.length).toBeGreaterThan(0))
    const stale = pending.filter((p) => p.url.includes('query=a&') || p.url.endsWith('query=a'))

    await typeQuery('ab')
    await waitFor(() => expect(pending.length).toBeGreaterThan(stale.length))
    const fresh = pending.filter((p) => p.url.includes('query=ab'))

    // The later query answers first, then the earlier one arrives late.
    fresh.forEach((p) => p.resolve([{ id: 's2', title: 'Later Answer' }]))
    await waitFor(() =>
      expect(screen.getByTestId('home-search-results').textContent).toContain('Later Answer'),
    )
    stale.forEach((p) => p.resolve([{ id: 's1', title: 'Earlier Answer' }]))

    await waitFor(() =>
      expect(screen.getByTestId('home-search-results').textContent).toContain('Later Answer'),
    )
    expect(screen.getByTestId('home-search-results').textContent).not.toContain('Earlier Answer')
  })
})

