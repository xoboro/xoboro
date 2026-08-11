import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SeriesScreen from '../src/reader/SeriesScreen.svelte'

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
    if (url.includes('/series/s1/resume')) return reply(null, 404)
    if (url.includes('/series/s1/metadata')) return reply({})
    if (url.includes('/series/s1')) return reply(SERIES)
    return reply(envelope())
  })
}

beforeEach(() => {
  localStorage.clear()
})

describe('SeriesScreen', () => {
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
