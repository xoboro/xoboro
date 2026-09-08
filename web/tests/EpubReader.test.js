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
import { bindEpubFrame } from '../src/reader/epubFrame.js'
import { epubProgressFor, epubResume } from '../src/reader/epubPosition.js'

const router = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }))
vi.mock('svelte-spa-router', () => router)

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

function installFrameDocument(frame) {
  const frameDocument = frame.contentDocument
  frameDocument.open()
  frameDocument.write('<!doctype html><html><head></head><body><main>chapter</main></body></html>')
  frameDocument.close()
  return frameDocument
}

const NOVEL = {
  id: 'n1',
  title: 'Synthetic Novel',
  seriesId: 's1',
  mediaKind: 'NOVEL',
  media: { pageCount: 2, status: 'READY' },
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
  router.replace.mockReset()
  router.push.mockReset()
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

describe('EPUB position mapping', () => {
  const positions = [
    { position: 1, href: 'chapter-1.xhtml', progression: 0, totalProgression: 0 },
    { position: 2, href: 'chapter-1.xhtml', progression: 0.5, totalProgression: 0.25 },
    { position: 3, href: 'chapter-2.xhtml', progression: 0, totalProgression: 0.5 },
    { position: 4, href: 'chapter-2.xhtml', progression: 0.5, totalProgression: 0.75 },
  ]

  it('resumes from locator position and in-resource progression before the page fallback', () => {
    expect(
      epubResume(
        {
          page: 1,
          locator: {
            href: 'chapter-2.xhtml',
            locations: { position: 3, progression: 0.5 },
          },
        },
        positions,
        2,
      ),
    ).toEqual({ index: 2, progression: 0.5 })

    expect(
      epubResume(
        {
          page: 2,
          locator: { href: 'chapter-1.xhtml', locations: { progression: 0.75 } },
        },
        positions,
        2,
      ),
    ).toEqual({ index: 1, progression: 0.75 })
  })

  it('inverts the server page mapping when no locator is stored', () => {
    expect(epubResume({ page: 2, locator: null }, positions, 2)).toEqual({
      index: 2,
      progression: 0,
    })
  })

  it('matches a fragmented locator to its canonical spine resource', () => {
    const resume = epubResume(
      {
        page: 1,
        locator: {
          href: 'chapter-2.xhtml?edition=synthetic#section-3',
          locations: { progression: 0.5 },
        },
      },
      positions,
      2,
    )

    expect(resume).toEqual({ index: 3, progression: 0.5 })
    expect(epubProgressFor(positions, 'chapter-2.xhtml#section-3', 0.5, 2, false).locator.href).toBe(
      'chapter-2.xhtml',
    )
  })

  it('maps the third of four positions to analyzed page two and clamps the page', () => {
    const progress = epubProgressFor(positions, 'chapter-2.xhtml', 0, 2, false)

    expect(progress.page).toBe(2)
    expect(progress.locator).toEqual({
      href: 'chapter-2.xhtml',
      locations: { progression: 0, totalProgression: 0.5, position: 3 },
    })
    expect(progress.completed).toBe(false)
    expect(epubProgressFor(positions, 'chapter-2.xhtml', 0, 0, false).page).toBe(1)
  })

  it('reports precise progression and completes only at the final resource bottom', () => {
    const middle = epubProgressFor(positions, 'chapter-1.xhtml', 0.75, 2, true)
    const bottom = epubProgressFor(positions, 'chapter-2.xhtml', 1, 2, true)

    expect(middle.locator.locations).toEqual({
      progression: 0.75,
      totalProgression: 0.375,
      position: 2,
    })
    expect(middle.completed).toBe(false)
    expect(bottom.locator.locations.totalProgression).toBe(1)
    expect(bottom.completed).toBe(true)
  })
})

describe('EPUB frame binding', () => {
  it('restores before reporting scroll and owns frame listeners across every load', () => {
    const frame = document.createElement('iframe')
    document.body.append(frame)
    const frameDocument = frame.contentDocument
    const scrolling = frameDocument.documentElement
    Object.defineProperties(scrolling, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 0 },
    })
    const restoreObservations = []
    const onProgress = vi.fn((progress) => {
      restoreObservations.push({ progress, scrollTop: scrolling.scrollTop })
    })
    const onToggleChrome = vi.fn()
    const onKeydown = vi.fn()
    const binding = bindEpubFrame(frame, {
      restoreKey: 'chapter-1@0.5',
      progression: 0.5,
      styles: { fontSize: '115', lineHeight: '1.8', margin: '32', width: '42', theme: 'light' },
      onProgress,
      onToggleChrome,
      onKeydown,
    })

    frame.dispatchEvent(new Event('load'))
    expect(scrolling.scrollTop).toBe(500)
    expect(onProgress).toHaveBeenLastCalledWith({ progression: 0.5, atBottom: false })
    expect(restoreObservations[0]).toEqual({
      progress: { progression: 0.5, atBottom: false },
      scrollTop: 500,
    })

    scrolling.scrollTop = 750
    frameDocument.dispatchEvent(new Event('scroll'))
    expect(onProgress).toHaveBeenLastCalledWith({ progression: 0.75, atBottom: false })
    const style = frameDocument.getElementById('xoboro-epub-style').textContent
    expect(style).toContain('font-size: 115%')
    expect(style).toContain('line-height: 1.8')
    expect(style).toContain('max-width: 42rem')
    expect(style).toContain('padding: 32px')
    expect(style).toContain('color-scheme: light')

    frame.dispatchEvent(new Event('load'))
    frameDocument.dispatchEvent(new MouseEvent('click'))
    frameDocument.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    expect(onToggleChrome).toHaveBeenCalledTimes(1)
    expect(onKeydown).toHaveBeenCalledTimes(1)
    expect(onProgress).toHaveBeenCalledTimes(3)

    frameDocument.dispatchEvent(new Event('scroll'))
    expect(onProgress).toHaveBeenCalledTimes(4)

    binding.destroy()
    frameDocument.dispatchEvent(new MouseEvent('click'))
    frameDocument.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    frameDocument.dispatchEvent(new Event('scroll'))
    expect(onToggleChrome).toHaveBeenCalledTimes(1)
    expect(onKeydown).toHaveBeenCalledTimes(1)
    expect(onProgress).toHaveBeenCalledTimes(4)
    frame.remove()
  })

  it('overrides conflicting publisher styles with the chosen typography and dark theme', () => {
    const frame = document.createElement('iframe')
    document.body.append(frame)
    const frameDocument = frame.contentDocument
    frameDocument.head.innerHTML = `
      <style>
        body { background: white; color: black; }
        p { background: white; color: black; font-size: 12px; line-height: 1.1; }
      </style>
    `
    frameDocument.body.innerHTML = '<p>synthetic publisher text</p>'
    const binding = bindEpubFrame(frame, {
      restoreKey: 'publisher-conflict',
      progression: 0,
      styles: { fontSize: '130', lineHeight: '1.8', margin: '32', width: '42', theme: 'dark' },
    })

    frame.dispatchEvent(new Event('load'))
    const bodyStyle = frame.contentWindow.getComputedStyle(frameDocument.body)
    const paragraphStyle = frame.contentWindow.getComputedStyle(frameDocument.querySelector('p'))

    expect(bodyStyle.backgroundColor).toBe('rgb(0, 0, 0)')
    expect(bodyStyle.color).toBe('rgb(255, 255, 255)')
    expect(paragraphStyle.backgroundColor).toBe('rgba(0, 0, 0, 0)')
    expect(paragraphStyle.color).toBe('rgb(255, 255, 255)')
    expect(paragraphStyle.fontSize).toBe('130%')
    expect(paragraphStyle.lineHeight).toBe('1.8')

    binding.destroy()
    frame.remove()
  })
})

describe('EpubReader', () => {
  it('uses only the supplied reader context on routed entry', async () => {
    const fetchImpl = novelRoutes()
    globalThis.fetch = fetchImpl
    render(ReaderRoute, { params: { id: 'n1' } })

    await screen.findByTestId('chapter-frame')
    const reads = fetchImpl.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === 'GET')
    expect(reads).toHaveLength(1)
    expect(reads[0][0]).toContain('/media-items/n1/reader-context')
    expect(reads[0][0]).not.toMatch(/\/(positions|pages|previous|next)$/)
  })

  it('falls back to one reader-context request when mounted alone', async () => {
    const fetchImpl = novelRoutes()
    globalThis.fetch = fetchImpl
    render(EpubReader, { params: { id: 'n1' } })

    await screen.findByTestId('chapter-frame')
    const reads = fetchImpl.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === 'GET')
    expect(reads).toHaveLength(1)
    expect(reads[0][0]).toContain('/media-items/n1/reader-context')
  })

  it('ignores a stale context promise after the item changes', async () => {
    let rejectFirst
    const nextContext = {
      item: { ...NOVEL, id: 'n2', title: 'Second Synthetic Novel' },
      previousId: 'n1',
      nextId: null,
      pages: [],
      positions: [{ ...POSITIONS[0], href: 'OEBPS/text/second.xhtml' }],
    }
    globalThis.fetch = routes([
      [
        '/media-items/n1/reader-context',
        () => new Promise((_, reject) => (rejectFirst = reject)),
      ],
      ['/media-items/n2/reader-context', reply(nextContext)],
    ])
    const { rerender } = render(EpubReader, { params: { id: 'n1' } })

    await waitFor(() => expect(rejectFirst).toBeTypeOf('function'))
    await rerender({ params: { id: 'n2' } })
    const frame = await screen.findByTestId('chapter-frame')
    expect(frame.getAttribute('src')).toContain('second.xhtml')

    rejectFirst(new Error('late failure'))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByTestId('chapter-frame').getAttribute('src')).toContain('second.xhtml')
  })

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
    await fireEvent.click(screen.getByTestId('keyboard-chrome-toggle'))

    expect(screen.getByTestId('previous-position')).toBeDisabled()
    await fireEvent.click(screen.getByTestId('next-position'))

    await waitFor(() =>
      expect(screen.getByTestId('chapter-frame').getAttribute('src')).toContain('chapter-2'),
    )
    expect(screen.getByTestId('next-position')).toBeDisabled()
    expect(screen.getByTestId('previous-position')).not.toBeDisabled()
  })

  it('toggles page-first chrome from a tap inside the frame', async () => {
    globalThis.fetch = novelRoutes()
    render(EpubReader, { params: { id: 'n1' } })

    const frame = await screen.findByTestId('chapter-frame')
    expect(screen.queryByTestId('toggle-chrome')).toBeNull()
    expect(screen.getByTestId('position')).toHaveClass('visually-hidden')
    expect(screen.getByTestId('keyboard-chrome-toggle')).toHaveClass('visually-hidden')

    const frameDocument = installFrameDocument(frame)
    frame.dispatchEvent(new Event('load'))
    frameDocument.dispatchEvent(new MouseEvent('click'))
    await waitFor(() => expect(screen.getByTestId('open-settings')).toBeInTheDocument())
  })

  it('uses history replacement for adjacent items and list navigation', async () => {
    const context = {
      item: NOVEL,
      previousId: 'n0',
      nextId: 'n2',
      pages: [],
      positions: POSITIONS,
    }
    globalThis.fetch = routes([
      ['/media-items/n1/reader-context', reply(context)],
      ['/media-items/n1/progress', reply(null, 204)],
    ])
    render(EpubReader, { params: { id: 'n1' }, initialContext: context })

    await screen.findByTestId('chapter-frame')
    await fireEvent.click(screen.getByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('previous-position'))
    expect(router.replace).toHaveBeenLastCalledWith('/read/n0')

    await fireEvent.click(screen.getByTestId('next-position'))
    await fireEvent.click(screen.getByTestId('next-position'))
    expect(router.replace).toHaveBeenLastCalledWith('/read/n2')

    await fireEvent.click(screen.getByLabelText('목록'))
    expect(router.replace).toHaveBeenLastCalledWith('/series/s1')
    await fireEvent.click(screen.getByLabelText('뒤로'))
    expect(router.replace).toHaveBeenLastCalledWith('/series/s1')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('restores the saved in-resource progression when the frame loads', async () => {
    const context = {
      item: {
        ...NOVEL,
        progress: {
          page: 1,
          completed: false,
          locator: {
            href: POSITIONS[0].href,
            locations: { position: 1, progression: 0.5, totalProgression: 0.25 },
          },
        },
      },
      previousId: null,
      nextId: null,
      pages: [],
      positions: POSITIONS,
    }
    globalThis.fetch = novelRoutes()
    render(EpubReader, { params: { id: 'n1' }, initialContext: context })

    const frame = await screen.findByTestId('chapter-frame')
    const frameDocument = installFrameDocument(frame)
    const scrolling = frameDocument.documentElement
    Object.defineProperties(scrolling, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 0 },
    })
    frame.dispatchEvent(new Event('load'))

    expect(scrolling.scrollTop).toBe(500)
  })

  it('writes precise scrolling progress with a bounded page and explicit completion', async () => {
    const fourPositions = [
      { position: 1, href: 'chapter-1.xhtml', progression: 0, totalProgression: 0 },
      { position: 2, href: 'chapter-2.xhtml', progression: 0, totalProgression: 0.25 },
      { position: 3, href: 'chapter-3.xhtml', progression: 0, totalProgression: 0.5 },
      { position: 4, href: 'chapter-4.xhtml', progression: 0, totalProgression: 0.75 },
    ]
    const context = {
      item: {
        ...NOVEL,
        progress: {
          page: 2,
          completed: false,
          locator: { href: 'chapter-3.xhtml', locations: { position: 3, progression: 0 } },
        },
      },
      previousId: null,
      nextId: null,
      pages: [],
      positions: fourPositions,
    }
    const fetchImpl = routes([['/media-items/n1/progress', reply(null, 204)]])
    globalThis.fetch = fetchImpl
    render(EpubReader, { params: { id: 'n1' }, initialContext: context })

    const frame = await screen.findByTestId('chapter-frame')
    const frameDocument = installFrameDocument(frame)
    const scrolling = frameDocument.documentElement
    Object.defineProperties(scrolling, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 500 },
    })
    frame.dispatchEvent(new Event('load'))
    scrolling.scrollTop = 500
    frameDocument.dispatchEvent(new Event('scroll'))
    await new Promise((resolve) => setTimeout(resolve, 900))

    const write = fetchImpl.mock.calls.find(([, init]) => init?.method === 'PUT')
    const sent = JSON.parse(write[1].body)
    expect(sent.page).toBe(2)
    expect(sent.locator.locations).toEqual({
      progression: 0.5,
      totalProgression: 0.625,
      position: 3,
    })
    expect(sent.completed).toBe(false)
  })

  it('completes only at the bottom of the final resource', async () => {
    const context = {
      item: {
        ...NOVEL,
        progress: {
          page: 2,
          completed: false,
          locator: { href: POSITIONS[1].href, locations: { position: 2, progression: 0 } },
        },
      },
      previousId: null,
      nextId: null,
      pages: [],
      positions: POSITIONS,
    }
    const fetchImpl = routes([['/media-items/n1/progress', reply(null, 204)]])
    globalThis.fetch = fetchImpl
    render(EpubReader, { params: { id: 'n1' }, initialContext: context })

    const frame = await screen.findByTestId('chapter-frame')
    const frameDocument = installFrameDocument(frame)
    const scrolling = frameDocument.documentElement
    Object.defineProperties(scrolling, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 0 },
    })
    frame.dispatchEvent(new Event('load'))
    scrolling.scrollTop = 1000
    frameDocument.dispatchEvent(new Event('scroll'))
    await new Promise((resolve) => setTimeout(resolve, 900))

    const writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(JSON.parse(writes.at(-1)[1].body).completed).toBe(true)
  })

  it('flushes the newest pending frame position with keepalive on pagehide', async () => {
    const context = {
      item: NOVEL,
      previousId: null,
      nextId: null,
      pages: [],
      positions: POSITIONS,
    }
    const fetchImpl = routes([['/media-items/n1/progress', reply(null, 204)]])
    globalThis.fetch = fetchImpl
    render(EpubReader, { params: { id: 'n1' }, initialContext: context })

    const frame = await screen.findByTestId('chapter-frame')
    const frameDocument = installFrameDocument(frame)
    const scrolling = frameDocument.documentElement
    Object.defineProperties(scrolling, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 250 },
    })
    frame.dispatchEvent(new Event('load'))
    scrolling.scrollTop = 750
    frameDocument.dispatchEvent(new Event('scroll'))
    window.dispatchEvent(new Event('pagehide'))

    await waitFor(() =>
      expect(fetchImpl.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true),
    )
    const write = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT').at(-1)
    expect(write[1].keepalive).toBe(true)
    expect(JSON.parse(write[1].body).locator.locations.progression).toBe(0.75)
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

    await fireEvent.click(await screen.findByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('open-settings'))
    await fireEvent.click(screen.getByTestId('width-52'))

    await waitFor(() => expect(localStorage.getItem('xoboro.epub.width')).toBe('52'))
  })

  it('applies persisted typography and theme controls inside the frame', async () => {
    localStorage.setItem('xoboro.epub.font-size', '115')
    localStorage.setItem('xoboro.epub.line-height', '1.8')
    localStorage.setItem('xoboro.epub.theme', 'light')
    globalThis.fetch = novelRoutes()
    render(EpubReader, { params: { id: 'n1' } })

    const frame = await screen.findByTestId('chapter-frame')
    const frameDocument = installFrameDocument(frame)
    frame.dispatchEvent(new Event('load'))
    const style = frameDocument.getElementById('xoboro-epub-style')
    expect(style.textContent).toContain('font-size: 115%')
    expect(style.textContent).toContain('line-height: 1.8')
    expect(style.textContent).toContain('color-scheme: light')

    await fireEvent.click(screen.getByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('open-settings'))
    await fireEvent.click(screen.getByTestId('font-130'))

    await waitFor(() => expect(style.textContent).toContain('font-size: 130%'))
    expect(localStorage.getItem('xoboro.epub.font-size')).toBe('130')
  })

  it('offers a retry only for a failure that can succeed later', async () => {
    globalThis.fetch = routes([
      [
        '/media-items/n1/reader-context',
        reply({ code: 'media_unsupported', message: 'encrypted' }, 409),
      ],
    ])
    render(EpubReader, { params: { id: 'n1' } })

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    // The notice renders a retry button only when one is passed, so its absence is the
    // assertion.
    expect(screen.getByRole('alert').querySelector('button')).toBeNull()
  })

  it('offers a retry for an item still being analyzed', async () => {
    globalThis.fetch = routes([
      [
        '/media-items/n1/reader-context',
        reply({ code: 'media_not_ready', message: 'analyzing' }, 409),
      ],
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
