import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SeriesScreen from '../src/reader/SeriesScreen.svelte'

/**
 * A hub the test can publish into.
 *
 * The real one only ever delivers what a live `EventSource` gave it, so nothing in a test can
 * make it speak. Doubling it here is what lets these tests state which events reach the screen —
 * the property under test is precisely *which* events it reacts to.
 */
const listeners = { domain: [], resync: [] }
vi.mock('../src/lib/eventHub.js', () => ({
  eventHub: {
    status: { subscribe: (run) => { run('open'); return () => {} } },
    on: (names, handler) => {
      const entry = { names: Array.isArray(names) ? names : [names], handler }
      listeners.domain.push(entry)
      return () => {
        listeners.domain = listeners.domain.filter((each) => each !== entry)
      }
    },
    onResync: (handler) => {
      listeners.resync.push(handler)
      return () => {
        listeners.resync = listeners.resync.filter((each) => each !== handler)
      }
    },
  },
}))

function emit(name, payload = {}) {
  for (const { names, handler } of listeners.domain) {
    if (names.includes(name)) handler({ name, ...payload })
  }
}

function emitResync() {
  for (const handler of listeners.resync) handler({ reason: 'gap' })
}

/** How many times the screen has asked for the series itself. */
function detailRequests(fetchImpl) {
  return fetchImpl.mock.calls.filter(
    ([url]) => url.includes('/series/s1') && !url.includes('/media-items') && !url.includes('/resume'),
  ).length
}

/**
 * The series page as a reader sees it.
 *
 * The page opened with a title bar, a facts panel and a chapter list, and never showed
 * the series artwork — while the same artwork is already the whole visual language of
 * the home grid and the shelves. A reader arriving from a cover found a page carrying
 * nothing that looked like the thing they had just tapped.
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

/** `XoboroSeriesResponse`, field for field, because the flat shape is the contract. */
const SERIES = {
  id: 's1',
  libraryId: 'lib1',
  title: 'Synthetic Series',
  sortTitle: 'Synthetic Series',
  summary: 'A synthetic summary.',
  status: 'ONGOING',
  readingDirection: null,
  publisher: 'Synthetic Publisher',
  ageRating: null,
  language: 'ko',
  genres: ['Action'],
  tags: [],
  links: [],
  alternateTitles: [],
  authors: [{ name: 'Synthetic Writer', role: 'writer' }],
  mediaItemCount: 2,
  expectedMediaItemCount: null,
  oneShot: false,
  deleted: false,
  createdAtMillis: 1,
  updatedAtMillis: 1,
  sourceModifiedAtMillis: 1,
}

const ITEMS = [
  { id: 'm2', title: 'Chapter 2', media: { pageCount: 10 }, progress: null },
  {
    id: 'm1',
    title: 'Chapter 1',
    media: { pageCount: 10 },
    progress: { page: 5, completed: false, readAtMillis: 1, updatedAtMillis: 1 },
  },
]

/**
 * Ordered from the most specific path to the least.
 *
 * `url.includes` matching means `/series/s1` also matches `/series/s1/media-items`, so a
 * general entry placed first answers a request it was not written for and the failure
 * surfaces somewhere unrelated.
 */
function server(overrides = []) {
  return vi.fn(async (url) => {
    for (const [match, answer] of overrides) {
      if (url.includes(match)) return answer
    }
    if (url.includes('/series/s1/media-items')) return reply(envelope(ITEMS))
    if (url.includes('/series/s1/reader-context')) {
      return reply({ series: SERIES, first: ITEMS[1], resume: ITEMS[1] })
    }
    if (url.includes('/series/s1/metadata')) return reply({})
    if (url.includes('/series/s1')) return reply(SERIES)
    return reply(envelope())
  })
}

beforeEach(() => {
  localStorage.clear()
  listeners.domain = []
  listeners.resync = []
})

/**
 * Which events this screen answers, and which it must ignore.
 *
 * It answered all of them. A scan announces every series and every chapter in the library, so one
 * open series page issued four requests per entity it had never heard of — measured on a
 * catalogue of 3,339 series, that is thousands of requests for one page nobody touched.
 */
describe('series screen refreshes', () => {
  it('ignores a change to a different series', async () => {
    const fetchImpl = server()
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })
    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())
    const before = detailRequests(fetchImpl)

    emit('series.changed', { ids: ['other'] })
    await new Promise((resolve) => setTimeout(resolve, 350))

    expect(detailRequests(fetchImpl)).toBe(before)
  })

  it('reloads when its own series changes', async () => {
    const fetchImpl = server()
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })
    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())
    const before = detailRequests(fetchImpl)

    emit('series.changed', { ids: ['s1'] })

    await waitFor(() => expect(detailRequests(fetchImpl)).toBeGreaterThan(before))
  })

  it('reloads for a chapter of this series and ignores another series’ chapter', async () => {
    const fetchImpl = server()
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })
    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())
    const before = detailRequests(fetchImpl)

    emit('media-item.changed', { ids: ['m9'], seriesId: 'other' })
    await new Promise((resolve) => setTimeout(resolve, 350))
    expect(detailRequests(fetchImpl)).toBe(before)

    emit('media-item.changed', { ids: ['m1'], seriesId: 's1' })
    await waitFor(() => expect(detailRequests(fetchImpl)).toBeGreaterThan(before))
  })

  /** A scan announces a series and each of its chapters, so the relevant events arrive together. */
  it('coalesces a burst into one reload', async () => {
    const fetchImpl = server()
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })
    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())
    const before = detailRequests(fetchImpl)

    emit('series.changed', { ids: ['s1'] })
    emit('media-item.changed', { ids: ['m1'], seriesId: 's1' })
    emit('media-item.added', { ids: ['m3'], seriesId: 's1' })

    await waitFor(() => expect(detailRequests(fetchImpl)).toBe(before + 1))
    await new Promise((resolve) => setTimeout(resolve, 350))
    expect(detailRequests(fetchImpl)).toBe(before + 1)
  })

  /**
   * The only signal the server sends that a view may be stale without saying which view. The
   * other reader screens listened for it; this one did not, so a reader sitting here kept
   * whatever had loaded — which is how a series showed no authors while the route returned two.
   */
  it('reloads when the stream reports a gap', async () => {
    const fetchImpl = server()
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })
    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())
    const before = detailRequests(fetchImpl)

    emitResync()

    await waitFor(() => expect(detailRequests(fetchImpl)).toBeGreaterThan(before))
  })

  /**
   * The slower read must not win.
   *
   * Two reads are in flight whenever an event lands while the reader is changing the order or
   * marking something read, and the one that answers last is the one that paints. Held here at the
   * series request: the first read is made to answer after the second, and what stays on screen has
   * to be the second read's answer.
   */
  it('ignores an answer to a read that a newer one has superseded', async () => {
    let seriesRequests = 0
    let releaseFirst = () => {}
    const held = new Promise((resolve) => {
      releaseFirst = resolve
    })
    globalThis.fetch = vi.fn(async (url) => {
      if (url.includes('/series/s1/media-items')) return reply(envelope(ITEMS))
      if (url.includes('/series/s1/reader-context')) {
        seriesRequests += 1
        if (seriesRequests === 2) {
          // The first read is released only once the second has been asked for, so the order the
          // answers arrive in is the order under test rather than a matter of timing.
          await held
          return reply({
            series: { ...SERIES, title: 'Stale Answer' },
            first: ITEMS[1],
            resume: ITEMS[1],
          })
        }
        if (seriesRequests === 3) {
          return reply({
            series: { ...SERIES, title: 'Fresh Answer' },
            first: ITEMS[1],
            resume: ITEMS[1],
          })
        }
        return reply({ series: SERIES, first: ITEMS[1], resume: ITEMS[1] })
      }
      return reply(envelope())
    })
    render(SeriesScreen, { params: { id: 's1' } })
    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())

    emit('series.changed', { ids: ['s1'] })
    await waitFor(() => expect(seriesRequests).toBe(2))
    emit('series.changed', { ids: ['s1'] })
    await waitFor(() => expect(seriesRequests).toBe(3))
    releaseFirst()

    await waitFor(() => expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('Fresh Answer'))
    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(screen.getByRole('heading', { level: 1 }).textContent).not.toContain('Stale Answer')
  })

  it('aborts the requests a newer series load supersedes', async () => {
    let contextRequests = 0
    let supersededSignal = null
    const fetchImpl = vi.fn(async (url, options) => {
      if (url.includes('/series/s1/media-items')) return reply(envelope(ITEMS))
      if (url.includes('/series/s1/reader-context')) {
        contextRequests += 1
        if (contextRequests === 2) {
          supersededSignal = options.signal
          return new Promise((_, reject) => {
            options.signal.addEventListener('abort', () => {
              reject(new DOMException('Aborted', 'AbortError'))
            })
          })
        }
        return reply({ series: SERIES, first: ITEMS[1], resume: ITEMS[1] })
      }
      return reply(envelope())
    })
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })
    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())

    emit('series.changed', { ids: ['s1'] })
    await waitFor(() => expect(supersededSignal).not.toBeNull())
    emit('series.changed', { ids: ['s1'] })

    await waitFor(() => expect(supersededSignal.aborted).toBe(true))
  })

  /**
   * A payload naming nothing still reloads. Guessing "not mine" from a payload that says nothing
   * would trade needless reloads for a screen that silently stops updating, and only one of those
   * two gets reported.
   */
  it('reloads for an event whose payload names nothing', async () => {
    const fetchImpl = server()
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })
    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())
    const before = detailRequests(fetchImpl)

    emit('series.changed')

    await waitFor(() => expect(detailRequests(fetchImpl)).toBeGreaterThan(before))
  })
})

describe('SeriesScreen', () => {
  it('requests the next page so every chapter beyond the first hundred is reachable', async () => {
    const fetchImpl = vi.fn(async (url) => {
      if (url.includes('/series/s1/media-items')) {
        const page = Number(new URL(url, 'http://localhost').searchParams.get('page') ?? 0)
        const item = page === 0 ? ITEMS[0] : { ...ITEMS[1], id: 'm101', title: 'Chapter 101' }
        return reply({
          ...envelope([item]),
          page,
          size: 100,
          totalItems: 101,
          totalPages: 2,
          hasPrevious: page > 0,
          hasNext: page === 0,
        })
      }
      if (url.includes('/series/s1/reader-context')) {
        return reply({ series: SERIES, first: ITEMS[1], resume: ITEMS[1] })
      }
      return reply(envelope())
    })
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })

    const next = await screen.findByTestId('series-items-page-next')
    expect(next).toBeEnabled()
    await fireEvent.click(next)

    await screen.findByText('Chapter 101')
    const itemRequests = fetchImpl.mock.calls
      .map(([url]) => url)
      .filter((url) => url.includes('/series/s1/media-items'))
    expect(itemRequests).toHaveLength(2)
    expect(itemRequests[1]).toContain('page=1')
  })

  it('returns to page zero before requesting a different series order', async () => {
    const fetchImpl = vi.fn(async (url) => {
      if (url.includes('/series/s1/media-items')) {
        const query = new URL(url, 'http://localhost').searchParams
        const page = Number(query.get('page') ?? 0)
        const item = page === 0 ? ITEMS[0] : { ...ITEMS[1], id: 'm101', title: 'Chapter 101' }
        return reply({
          ...envelope([item]),
          page,
          size: 100,
          totalItems: 101,
          totalPages: 2,
          hasPrevious: page > 0,
          hasNext: page === 0,
        })
      }
      if (url.includes('/series/s1/reader-context')) {
        return reply({ series: SERIES, first: ITEMS[1], resume: ITEMS[1] })
      }
      return reply(envelope())
    })
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })

    await fireEvent.click(await screen.findByTestId('series-items-page-next'))
    await screen.findByText('Chapter 101')
    await fireEvent.click(screen.getByTestId('series-order-oldest'))

    await waitFor(() => {
      const last = fetchImpl.mock.calls
        .map(([url]) => url)
        .filter((url) => url.includes('/series/s1/media-items'))
        .at(-1)
      expect(last).toContain('page=0')
      expect(last).toContain('sort=number%2Casc')
    })
  })

  it('shows the series cover', async () => {
    globalThis.fetch = server()
    const { container } = render(SeriesScreen, { params: { id: 's1' } })

    const cover = await waitFor(() => {
      const found = container.querySelector('[data-testid="series-cover"] img')
      expect(found).toBeTruthy()
      return found
    })
    // Asserted as the artwork route rather than an exact string, so the check survives a
    // change to how the URL is built without stopping being a check.
    expect(cover.getAttribute('src')).toContain('/series/s1')
  })

  it('keeps the facts beside the cover rather than under it', async () => {
    globalThis.fetch = server()
    const { container } = render(SeriesScreen, { params: { id: 's1' } })

    await waitFor(() => expect(container.querySelector('[data-testid="series-cover"]')).toBeTruthy())
    // The overview is one element holding both, which is what lets a wide viewport put
    // them side by side; two siblings in the page flow could not.
    const overview = container.querySelector('[data-testid="series-overview"]')
    expect(overview).toBeTruthy()
    expect(overview.querySelector('[data-testid="series-cover"]')).toBeTruthy()
    // The panel's own `series-facts` list lives inside this column. The column carries a
    // different id on purpose: two elements sharing one made `getByTestId` throw, and this
    // file only escaped that by reaching for `querySelector`, which silently takes the first.
    expect(overview.querySelector('[data-testid="series-overview-facts"]')).toBeTruthy()
    expect(overview.querySelectorAll('[data-testid="series-facts"]')).toHaveLength(1)
  })

  /**
   * How many chapters are here, on the list rather than among the facts above it.
   *
   * The overview already says what the source claims exists ("3 of 167"); this says what is
   * present to open, next to the control that reorders it. A reader who has scrolled to the
   * chapters has left the facts behind, and otherwise learns the length by reaching the end.
   *
   * Taken from the envelope's total, not from the page: the listing is paged, so counting the
   * rows on screen would report the page size as the series length.
   */
  it('says how many chapters the list holds', async () => {
    globalThis.fetch = server()
    render(SeriesScreen, { params: { id: 's1' } })

    await waitFor(() => expect(screen.getByTestId('item-count')).toBeInTheDocument())
    expect(screen.getByTestId('item-count').textContent).toContain('2')
  })

  it('still lists the chapters', async () => {
    // The cover must be added to the page, not in place of what was already there.
    globalThis.fetch = server()
    render(SeriesScreen, { params: { id: 's1' } })

    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())
    expect(screen.getByTestId('reading-order').textContent).toContain('Chapter 1')
    expect(screen.getByTestId('reading-order').textContent).toContain('Chapter 2')
  })

  /**
   * The bar, which is the part the words cannot do.
   *
   * `page 5` says nothing about whether that is the start or nearly the end without the
   * chapter's length, and a reader should not have to hold that.
   */
  it('draws how far into a chapter the reader is', async () => {
    globalThis.fetch = server()
    const { container } = render(SeriesScreen, { params: { id: 's1' } })

    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())
    const bars = container.querySelectorAll('[data-testid="item-progress"]')
    // One bar, for the one chapter that has been started. Chapter 2 has no progress.
    expect(bars).toHaveLength(1)
    expect(bars[0].getAttribute('data-percent')).toBe('50')
    // Hidden from assistive technology on purpose: the row states the same progress in
    // words directly above it, so naming the bar as well would say it twice.
    expect(bars[0].getAttribute('aria-hidden')).toBe('true')
    expect(screen.getByTestId('reading-order').textContent).toContain('5')
  })

  it('fills the bar for a finished chapter whatever page it stopped on', async () => {
    globalThis.fetch = server([
      [
        '/series/s1/media-items',
        reply(
          envelope([
            {
              id: 'm1',
              title: 'Chapter 1',
              media: { pageCount: 10 },
              progress: { page: 3, completed: true, readAtMillis: 1, updatedAtMillis: 1 },
            },
          ]),
        ),
      ],
    ])
    const { container } = render(SeriesScreen, { params: { id: 's1' } })

    await waitFor(() =>
      expect(container.querySelector('[data-testid="item-progress"]')).toBeTruthy(),
    )
    expect(
      container.querySelector('[data-testid="item-progress"]').getAttribute('data-percent'),
    ).toBe('100')
  })

  /**
   * Marking a chapter read, and taking it back.
   *
   * The only way to move a chapter out of "unread" was to open it and reach the end, and
   * there was no way at all to move one back: the surface offered `PUT` on progress and
   * nothing else. A chapter opened by accident stayed started forever.
   */
  it('marks an unread chapter read', async () => {
    const fetchImpl = server()
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })

    await waitFor(() => expect(screen.getByTestId('reading-order')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('toggle-read-m2'))

    await waitFor(() => {
      const call = fetchImpl.mock.calls.find(([url, init]) =>
        url.includes('/media-items/m2/progress') && (init?.method ?? 'GET') === 'PUT',
      )
      expect(call).toBeTruthy()
    })
  })

  it('clears the progress of a chapter already read', async () => {
    const fetchImpl = server([
      [
        '/series/s1/media-items',
        reply(
          envelope([
            {
              id: 'm1',
              title: 'Chapter 1',
              media: { pageCount: 10 },
              progress: { page: 10, completed: true, readAtMillis: 1, updatedAtMillis: 1 },
            },
          ]),
        ),
      ],
    ])
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })

    await waitFor(() => expect(screen.getByTestId('toggle-read-m1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('toggle-read-m1'))

    await waitFor(() => {
      const call = fetchImpl.mock.calls.find(([url, init]) =>
        url.includes('/media-items/m1/progress') && init?.method === 'DELETE',
      )
      expect(call).toBeTruthy()
    })
  })

  it('marks the whole series read, and takes that back', async () => {
    const fetchImpl = server()
    globalThis.fetch = fetchImpl
    render(SeriesScreen, { params: { id: 's1' } })

    await waitFor(() => expect(screen.getByTestId('mark-series-read')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('mark-series-read'))
    await waitFor(() =>
      expect(
        fetchImpl.mock.calls.some(
          ([url, init]) => url.includes('/series/s1/progress') && init?.method === 'PUT',
        ),
      ).toBe(true),
    )

    // Waited for rather than raced: the second request is refused while the first is in
    // flight, which is the point of the guard - clicking twice must not queue two writes.
    await waitFor(() => expect(screen.getByTestId('mark-series-unread')).not.toBeDisabled())
    await fireEvent.click(screen.getByTestId('mark-series-unread'))
    await waitFor(() =>
      expect(
        fetchImpl.mock.calls.some(
          ([url, init]) => url.includes('/series/s1/progress') && init?.method === 'DELETE',
        ),
      ).toBe(true),
    )
  })
})
