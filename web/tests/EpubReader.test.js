import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import EpubReader from '../src/reader/EpubReader.svelte'
import ReaderRoute from '../src/reader/ReaderRoute.svelte'
import {
  RETRYABLE_DELIVERY_CODES,
  isRetryableDeliveryFailure,
  resourceUrlFor,
} from '../src/lib/api/catalog.js'
import { resetProgressClock } from '../src/lib/api/progress.js'

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

function routes(handlers) {
  return vi.fn(async (url, init = {}) => {
    for (const [match, handler] of handlers) {
      if (url.includes(match)) return typeof handler === 'function' ? handler(url, init) : handler
    }
    throw new Error(`unexpected request: ${init.method ?? 'GET'} ${url}`)
  })
}

const NOVEL = {
  id: 'n1',
  title: 'Synthetic Novel',
  seriesId: 's1',
  mediaKind: 'NOVEL',
  media: { status: 'READY' },
  progress: null,
}

/**
 * The reading-order listing, with the values the server actually computes.
 *
 * `totalProgression` is `(position - 1) / count` since ADR 0106, so the first position is 0
 * and the last is 0.5 — not 0.5 and 1, which is what the server sent while the analyzer used
 * `position / count`. A fixture using the other convention describes an API that does not
 * exist, and the reader copies these straight into the locator it stores.
 */
const POSITIONS = [
  {
    position: 1,
    href: 'OEBPS/text/chapter-1.xhtml',
    mediaType: 'application/xhtml+xml',
    progression: 0,
    totalProgression: 0,
  },
  {
    position: 2,
    href: 'OEBPS/text/chapter-2.xhtml',
    mediaType: 'application/xhtml+xml',
    progression: 0,
    totalProgression: 0.5,
  },
]

function novelRoutes(extra = []) {
  return routes([
    ...extra,
    [
      '/media-items/n1/reader-context',
      reply({ item: NOVEL, previousId: null, nextId: null, pages: [], positions: POSITIONS }),
    ],
    ['/media-items/n1/positions', reply(POSITIONS)],
    ['/media-items/n1/progress', reply(null, 204)],
    ['/media-items/n1', reply(NOVEL)],
  ])
}

beforeEach(() => {
  resetProgressClock()
  localStorage.clear()
})

describe('resourceUrlFor', () => {
  it('keeps the archive path’s slashes and encodes each segment', () => {
    // The path is an index key, sent verbatim. Encoding the whole string would turn its
    // slashes into %2F and miss the entry; leaving a space unencoded would break the URL.
    expect(resourceUrlFor('n1', 'OEBPS/text/chapter 1.xhtml')).toBe(
      '/api/xoboro/v1/media-items/n1/resources/OEBPS/text/chapter%201.xhtml',
    )
  })
})

describe('delivery failures', () => {
  it('treats only an unready item as worth retrying', () => {
    // media_unsupported means the file can never be delivered as it stands, and
    // page_not_decodable means this page is broken. A retry button on either is a button
    // that will never work.
    expect(RETRYABLE_DELIVERY_CODES).toEqual(['media_not_ready'])
    expect(isRetryableDeliveryFailure({ code: 'media_not_ready' })).toBe(true)
    expect(isRetryableDeliveryFailure({ code: 'media_unsupported' })).toBe(false)
    expect(isRetryableDeliveryFailure({ code: 'page_not_decodable' })).toBe(false)
    expect(isRetryableDeliveryFailure(null)).toBe(false)
  })
})

describe('EpubReader', () => {
  it('follows reading order from positions, not the resource manifest', async () => {
    globalThis.fetch = novelRoutes()
    render(EpubReader, { params: { id: 'n1' } })

    const frame = await screen.findByTestId('chapter-frame')
    // The first position's href, requested verbatim.
    expect(frame.getAttribute('src')).toContain('OEBPS/text/chapter-1.xhtml')
  })

  it('sandboxes chapter markup', async () => {
    // Chapters are user-supplied documents. The server sends script-src 'none'; this is
    // the other half of that decision, and without it a crafted EPUB could navigate the
    // reader away or run script in the session's origin.
    globalThis.fetch = novelRoutes()
    render(EpubReader, { params: { id: 'n1' } })

    const frame = await screen.findByTestId('chapter-frame')
    expect(frame).toHaveAttribute('sandbox', 'allow-same-origin')
    expect(frame.getAttribute('sandbox')).not.toContain('allow-scripts')
    expect(frame.getAttribute('sandbox')).not.toContain('allow-top-navigation')
  })

  it('moves through positions and stops at the ends', async () => {
    globalThis.fetch = novelRoutes()
    render(EpubReader, { params: { id: 'n1' } })

    await screen.findByTestId('position')
    await fireEvent.click(screen.getByTestId('toggle-chrome'))

    expect(screen.getByTestId('previous-position')).toBeDisabled()
    await fireEvent.click(screen.getByTestId('next-position'))

    await waitFor(() =>
      expect(screen.getByTestId('chapter-frame').getAttribute('src')).toContain('chapter-2'),
    )
    expect(screen.getByTestId('next-position')).toBeDisabled()
    expect(screen.getByTestId('previous-position')).not.toBeDisabled()
  })

  it('writes a locator alongside the page position', async () => {
    // The endpoint stores both. Sending only the page would lose the place for a reader
    // that resumes by locator, and only the locator would lose it for one that resumes
    // by page.
    const fetchImpl = novelRoutes()
    globalThis.fetch = fetchImpl
    render(EpubReader, { params: { id: 'n1' } })

    await screen.findByTestId('position')
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    await new Promise((resolve) => setTimeout(resolve, 900))

    const write = fetchImpl.mock.calls.find(([, init]) => init?.method === 'PUT')
    expect(write).toBeTruthy()
    const sent = JSON.parse(write[1].body)
    expect(sent.page).toBe(2)
    expect(sent.locator.href).toBe('OEBPS/text/chapter-2.xhtml')
    // Copied from the second position verbatim. 0.5 rather than 0 also distinguishes it from
    // the first position, so the reader really did advance.
    expect(sent.locator.locations.totalProgression).toBe(0.5)
  })

  it('persists the column width, which is a setting that has an effect', async () => {
    globalThis.fetch = novelRoutes()
    render(EpubReader, { params: { id: 'n1' } })

    await fireEvent.click(await screen.findByTestId('toggle-chrome'))
    await fireEvent.click(screen.getByTestId('open-settings'))
    await fireEvent.click(screen.getByTestId('width-52'))

    await waitFor(() => expect(localStorage.getItem('xoboro.epub.width')).toBe('52'))
  })

  it('offers no text-size control, and says why', async () => {
    // Text size and line spacing cannot be changed from here: CSS on the iframe element
    // does not cascade into the chapter document, and injecting a stylesheet would need
    // script inside the frame, which the sandbox and the server both refuse. Controls
    // for them stored a preference and changed nothing on screen.
    globalThis.fetch = novelRoutes()
    const { container } = render(EpubReader, { params: { id: 'n1' } })

    await fireEvent.click(await screen.findByTestId('toggle-chrome'))
    await fireEvent.click(screen.getByTestId('open-settings'))

    expect(screen.getByTestId('typography-note')).toBeInTheDocument()
    expect(container.querySelector('[data-testid^="font-"]')).toBeNull()
  })

  it('offers a retry only for a failure that can succeed later', async () => {
    globalThis.fetch = routes([
      ['/media-items/n1', reply({ code: 'media_unsupported', message: 'encrypted' }, 409)],
    ])
    render(EpubReader, { params: { id: 'n1' } })

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    // The notice renders a retry button only when one is passed, so its absence is the
    // assertion.
    expect(screen.getByRole('alert').querySelector('button')).toBeNull()
  })

  it('offers a retry for an item still being analyzed', async () => {
    globalThis.fetch = routes([
      ['/media-items/n1', reply({ code: 'media_not_ready', message: 'analyzing' }, 409)],
    ])
    render(EpubReader, { params: { id: 'n1' } })

    await waitFor(() =>
      expect(screen.getByRole('alert').querySelector('button')).toBeTruthy(),
    )
  })
})

describe('ReaderRoute', () => {
  it('opens a novel in the EPUB reader', async () => {
    const fetchImpl = novelRoutes()
    globalThis.fetch = fetchImpl
    render(ReaderRoute, { params: { id: 'n1' } })

    await waitFor(() => expect(screen.getByTestId('chapter-frame')).toBeInTheDocument())
    expect(
      fetchImpl.mock.calls.filter(([url]) => url.endsWith('/media-items/n1/reader-context')).length,
    ).toBe(1)
    expect(fetchImpl.mock.calls.some(([url]) => url.endsWith('/media-items/n1'))).toBe(false)
  })

  it('opens a PDF in the image reader, because the server renders its pages', async () => {
    const pdf = {
      id: 'b1',
      title: 'Synthetic Document',
      seriesId: 's1',
      mediaKind: 'BOOK',
      media: { pagesCount: 1, status: 'READY' },
      progress: null,
    }
    globalThis.IntersectionObserver = class {
      observe() {}
      disconnect() {}
    }
    globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0)
    const fetchImpl = routes([
      [
        '/media-items/b1/reader-context',
        reply({
          item: pdf,
          previousId: null,
          nextId: null,
          pages: [{ number: 1, mediaType: 'image/jpeg', width: 800, height: 1200 }],
          positions: [],
        }),
      ],
      ['/media-items/b1', reply(pdf)],
    ])
    globalThis.fetch = fetchImpl
    const { container } = render(ReaderRoute, { params: { id: 'b1' } })

    await waitFor(() => expect(container.querySelector('.slot img')).toBeTruthy())
    expect(screen.queryByTestId('chapter-frame')).toBeNull()
    expect(
      fetchImpl.mock.calls.filter(([url]) => url.endsWith('/media-items/b1/reader-context')).length,
    ).toBe(1)
    expect(fetchImpl.mock.calls.some(([url]) => /\/media-items\/b1\/(pages|previous|next)$/.test(url))).toBe(false)
  })
})
