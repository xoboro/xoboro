import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
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
import { epubNavigationFor, epubProgressFor, epubResume } from '../src/reader/epubPosition.js'

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

  it('resolves only internal spine links relative to the current resource', () => {
    expect(epubNavigationFor(positions, 0, '#section-2')).toEqual({
      index: 0,
      href: 'chapter-1.xhtml#section-2',
      resourceHref: 'chapter-1.xhtml',
      fragment: 'section-2',
      progression: 0,
    })
    expect(epubNavigationFor(positions, 0, 'chapter-2.xhtml#ending')).toEqual({
      index: 2,
      href: 'chapter-2.xhtml#ending',
      resourceHref: 'chapter-2.xhtml',
      fragment: 'ending',
      progression: 0,
    })
    expect(epubNavigationFor(positions, 0, '../outside.xhtml')).toBeNull()
    expect(epubNavigationFor(positions, 0, 'https://example.invalid/chapter.xhtml')).toBeNull()
    expect(epubNavigationFor(positions, 0, 'javascript:alert(1)')).toBeNull()
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

    // By the time a real iframe emits load, the browser may already expose the new
    // document's zeroed scroll metrics through the old element reference.
    scrolling.scrollTop = 0
    frame.dispatchEvent(new Event('load'))
    expect(scrolling.scrollTop).toBe(750)
    expect(onProgress).toHaveBeenLastCalledWith({ progression: 0.75, atBottom: false })
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

    const style = frameDocument.getElementById('xoboro-epub-style').textContent
    expect(style).toContain(':where(img, svg, video, canvas)')
    expect(style).toContain('max-width: 100% !important')
    expect(style).toContain(':where(table, pre)')
    expect(style).toContain('overflow-x: auto !important')

    binding.destroy()
    frame.remove()
  })

  it('keeps the same logical location while typography reflows the chapter', () => {
    const frame = document.createElement('iframe')
    document.body.append(frame)
    const frameDocument = frame.contentDocument
    const scrolling = frameDocument.documentElement
    let scrollTop = 0
    Object.defineProperties(scrolling, {
      scrollHeight: {
        configurable: true,
        get: () =>
          frameDocument.getElementById('xoboro-epub-style')?.textContent.includes('font-size: 130%')
            ? 2200
            : 1200,
      },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: {
        configurable: true,
        get: () => scrollTop,
        set: (value) => {
          scrollTop = value
          frameDocument.dispatchEvent(new Event('scroll'))
        },
      },
    })
    const onProgress = vi.fn()
    const options = {
      restoreKey: 'same-chapter',
      progression: 0.5,
      styles: { fontSize: '100', lineHeight: '1.6', margin: '32', width: '42', theme: 'dark' },
      onProgress,
    }
    const binding = bindEpubFrame(frame, options)

    frame.dispatchEvent(new Event('load'))
    expect(scrolling.scrollTop).toBe(500)
    onProgress.mockClear()

    binding.update({
      ...options,
      styles: { ...options.styles, fontSize: '130' },
    })

    expect(scrolling.scrollTop).toBe(1000)
    expect(onProgress).toHaveBeenCalledTimes(1)
    expect(onProgress).toHaveBeenLastCalledWith({ progression: 0.5, atBottom: false })

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

  it('keeps both chrome bars inside the horizontal safe area', async () => {
    globalThis.fetch = novelRoutes()
    render(EpubReader, { params: { id: 'n1' } })

    await fireEvent.click(await screen.findByTestId('keyboard-chrome-toggle'))
    const source = readFileSync(join(process.cwd(), 'src/reader/EpubReader.svelte'), 'utf8')

    expect(source).toContain(
      'padding-left: max(var(--space-3), calc(var(--inset-left) + var(--space-2)))',
    )
    expect(source).toContain(
      'padding-right: max(var(--space-3), calc(var(--inset-right) + var(--space-2)))',
    )
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

  it('keeps the latest frame position when the same EPUB document reloads before exit', async () => {
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
      scrollTop: { configurable: true, writable: true, value: 0 },
    })
    frame.dispatchEvent(new Event('load'))
    scrolling.scrollTop = 750
    frameDocument.dispatchEvent(new Event('scroll'))

    // Safari and Chromium can reload the same iframe without changing the spine
    // location. That lifecycle event must preserve the live position, not replay the
    // locator from when the component first opened.
    scrolling.scrollTop = 0
    frame.dispatchEvent(new Event('load'))
    expect(scrolling.scrollTop).toBe(750)
    window.dispatchEvent(new Event('pagehide'))

    await waitFor(() =>
      expect(fetchImpl.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true),
    )
    const write = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT').at(-1)
    expect(write[1].keepalive).toBe(true)
    expect(JSON.parse(write[1].body).locator.locations.progression).toBe(0.75)
  })

  it('retains an unconfirmed ordinary write for a race-safe pagehide resend', async () => {
    const context = {
      item: NOVEL,
      previousId: null,
      nextId: null,
      pages: [],
      positions: POSITIONS,
    }
    let rejectOrdinary
    let progressWrites = 0
    const fetchImpl = routes([
      [
        '/media-items/n1/progress',
        () => {
          progressWrites += 1
          if (progressWrites === 1) {
            return new Promise((_, reject) => (rejectOrdinary = reject))
          }
          return reply(null, 204)
        },
      ],
    ])
    globalThis.fetch = fetchImpl
    render(EpubReader, { params: { id: 'n1' }, initialContext: context })

    const frame = await screen.findByTestId('chapter-frame')
    const frameDocument = installFrameDocument(frame)
    const scrolling = frameDocument.documentElement
    Object.defineProperties(scrolling, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 750 },
    })
    frame.dispatchEvent(new Event('load'))
    scrolling.scrollTop = 750
    frameDocument.dispatchEvent(new Event('scroll'))
    await waitFor(() => expect(progressWrites).toBe(1), { timeout: 1500 })

    window.dispatchEvent(new Event('pagehide'))
    await waitFor(() => expect(progressWrites).toBe(2))
    const writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(writes[0][1].keepalive).toBe(false)
    expect(writes[1][1].keepalive).toBe(true)
    expect(JSON.parse(writes[1][1].body).locator.locations.progression).toBe(0.75)

    rejectOrdinary(new Error('obsolete ordinary request failed'))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByRole('alert')).toBeNull()

    window.dispatchEvent(new Event('pagehide'))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(progressWrites).toBe(2)
  })

  it('resends the latest unconfirmed position when the document becomes hidden', async () => {
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
      scrollTop: { configurable: true, writable: true, value: 600 },
    })
    frame.dispatchEvent(new Event('load'))
    scrolling.scrollTop = 600
    frameDocument.dispatchEvent(new Event('scroll'))

    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await waitFor(() =>
      expect(fetchImpl.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true),
    )
    visibility.mockRestore()

    const write = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT').at(-1)
    expect(write[1].keepalive).toBe(true)
    expect(JSON.parse(write[1].body).locator.locations.progression).toBe(0.6)
  })

  it('follows valid chapter anchors, records fragments, and blocks external anchors', async () => {
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

    let frame = await screen.findByTestId('chapter-frame')
    let frameDocument = installFrameDocument(frame)
    frameDocument.body.innerHTML = `
      <a id="same" href="#section">same chapter</a>
      <a id="cross" href="chapter-2.xhtml#ending">next chapter</a>
      <a id="external" href="https://example.invalid/escape">external</a>
      <h2 id="section">section</h2>
    `
    const scrolling = frameDocument.documentElement
    Object.defineProperties(scrolling, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 0 },
    })
    Object.defineProperty(frameDocument.getElementById('section'), 'offsetTop', {
      configurable: true,
      value: 750,
    })
    frame.dispatchEvent(new Event('load'))

    const sameClick = new MouseEvent('click', { bubbles: true, cancelable: true })
    frameDocument.getElementById('same').dispatchEvent(sameClick)
    expect(sameClick.defaultPrevented).toBe(true)
    await waitFor(() => expect(scrolling.scrollTop).toBe(750))

    const externalClick = new MouseEvent('click', { bubbles: true, cancelable: true })
    frameDocument.getElementById('external').dispatchEvent(externalClick)
    expect(externalClick.defaultPrevented).toBe(true)
    expect(screen.getByTestId('chapter-frame').getAttribute('src')).toContain('chapter-1.xhtml')

    const crossClick = new MouseEvent('click', { bubbles: true, cancelable: true })
    frameDocument.getElementById('cross').dispatchEvent(crossClick)
    expect(crossClick.defaultPrevented).toBe(true)
    await waitFor(() =>
      expect(screen.getByTestId('chapter-frame').getAttribute('src')).toContain('chapter-2.xhtml'),
    )

    await new Promise((resolve) => setTimeout(resolve, 900))
    const write = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT').at(-1)
    expect(JSON.parse(write[1].body).locator.href).toBe('OEBPS/text/chapter-2.xhtml#ending')
  })

  it('resumes the later progression after scrolling beyond a followed anchor', async () => {
    const context = {
      item: NOVEL,
      previousId: null,
      nextId: null,
      pages: [],
      positions: POSITIONS,
    }
    const fetchImpl = routes([['/media-items/n1/progress', reply(null, 204)]])
    globalThis.fetch = fetchImpl
    const first = render(EpubReader, { params: { id: 'n1' }, initialContext: context })

    let frame = await screen.findByTestId('chapter-frame')
    let frameDocument = installFrameDocument(frame)
    frameDocument.body.innerHTML = '<a id="jump" href="#section">section</a><h2 id="section">section</h2>'
    let scrolling = frameDocument.documentElement
    Object.defineProperties(scrolling, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 0 },
    })
    Object.defineProperty(frameDocument.getElementById('section'), 'offsetTop', {
      configurable: true,
      value: 750,
    })
    frame.dispatchEvent(new Event('load'))

    frameDocument
      .getElementById('jump')
      .dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    await waitFor(() => expect(scrolling.scrollTop).toBe(750))
    scrolling.scrollTop = 900
    frameDocument.dispatchEvent(new Event('scroll'))
    await new Promise((resolve) => setTimeout(resolve, 900))

    const write = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT').at(-1)
    const stored = JSON.parse(write[1].body)
    expect(stored.locator.href).toBe(POSITIONS[0].href)
    expect(stored.locator.locations.progression).toBe(0.9)

    first.unmount()
    const resumedContext = { ...context, item: { ...NOVEL, progress: stored } }
    render(EpubReader, { params: { id: 'n1' }, initialContext: resumedContext })
    frame = await screen.findByTestId('chapter-frame')
    frameDocument = installFrameDocument(frame)
    frameDocument.body.innerHTML = '<h2 id="section">section</h2>'
    scrolling = frameDocument.documentElement
    Object.defineProperties(scrolling, {
      scrollHeight: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 0 },
    })
    Object.defineProperty(frameDocument.getElementById('section'), 'offsetTop', {
      configurable: true,
      value: 750,
    })
    frame.dispatchEvent(new Event('load'))

    expect(scrolling.scrollTop).toBe(900)
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
  it('routes the literal production media-item shape by item.type', async () => {
    const item = {
      id: 'production-novel',
      libraryId: 'library-1',
      seriesId: 'series-1',
      type: 'NOVEL',
      title: 'Production-shaped Novel',
      seriesTitle: 'Synthetic Series',
      summary: '',
      number: '1',
      sortNumber: 1,
      releaseDate: null,
      authors: [],
      tags: [],
      isbn: '',
      links: [],
      media: { status: 'READY', mediaType: 'application/epub+zip', profile: null, pageCount: 1, message: null },
      progress: null,
      fileSize: 1024,
      oneShot: false,
      deleted: false,
      createdAtMillis: 1,
      updatedAtMillis: 1,
      sourceModifiedAtMillis: 1,
    }
    globalThis.fetch = routes([
      [
        '/media-items/production-novel/reader-context',
        reply({
          item,
          pages: [],
          positions: [{ ...POSITIONS[0], href: 'OEBPS/text/production.xhtml' }],
        }),
      ],
    ])

    render(ReaderRoute, { params: { id: 'production-novel' } })

    const frame = await screen.findByTestId('chapter-frame')
    expect(frame.getAttribute('src')).toContain('OEBPS/text/production.xhtml')
  })

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
