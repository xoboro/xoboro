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

describe('Reader home', () => {
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
