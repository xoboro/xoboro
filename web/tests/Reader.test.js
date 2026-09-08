import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { compile } from 'svelte/compiler'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Reader from '../src/reader/Reader.svelte'
import { resetProgressClock } from '../src/lib/api/progress.js'

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

const ITEM = {
  id: 'm1',
  title: 'Synthetic Chapter',
  seriesId: 's1',
  // `pageCount` is the field the native API sends. This fixture said `pagesCount`, matching a
  // misspelling in the component, so the two agreed with each other and neither agreed with the
  // server - and the component's `?? pages.length` fallback made the mistake invisible because the
  // fallback happened to be the same number.
  media: { pageCount: 3, status: 'READY' },
  progress: null,
}

const PAGES = [
  { number: 1, mediaType: 'image/jpeg', width: 800, height: 1200 },
  { number: 2, mediaType: 'image/jpeg', width: 1600, height: 1200 },
  { number: 3, mediaType: 'image/jpeg', width: 800, height: 1200 },
]

const CONTEXT = {
  item: ITEM,
  previousId: null,
  nextId: 'm2',
  pages: PAGES,
  positions: [],
}

function routes(handlers) {
  return vi.fn(async (url, init = {}) => {
    for (const [match, handler] of handlers) {
      if (url.includes(match)) return typeof handler === 'function' ? handler(url, init) : handler
    }
    throw new Error(`unexpected request: ${init.method ?? 'GET'} ${url}`)
  })
}

function standardRoutes(extra = []) {
  return routes([
    ...extra,
    ['/media-items/m1/reader-context', reply(CONTEXT)],
    // `200` with the stored progress, which is what the server answers. This fake replied `204`,
    // copied from an OpenAPI description that was wrong about it; a fake that agrees with the
    // description rather than the server is not a test of the client's contract.
    [
      '/media-items/m1/progress',
      reply({ page: 1, completed: false, readAtMillis: 1, updatedAtMillis: 1 }),
    ],
  ])
}

beforeEach(() => {
  resetProgressClock()
  router.replace.mockReset()
  router.push.mockReset()
  localStorage.clear()
  globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0)
  Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: 1 })
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 })
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 768 })
  Object.defineProperties(document.documentElement, {
    scrollTop: { configurable: true, value: 0 },
    clientHeight: { configurable: true, value: 1000 },
    scrollHeight: { configurable: true, value: 3000 },
  })
})

function place(node, { top, bottom }) {
  vi.spyOn(node, 'getBoundingClientRect').mockReturnValue({
    top,
    bottom,
    height: bottom - top,
    left: 0,
    right: 400,
    width: 400,
    x: 0,
    y: top,
    toJSON: () => ({}),
  })
}

describe('Reader', () => {
  it('uses one bounded context request when entered through the route', async () => {
    const { default: ReaderRoute } = await import('../src/reader/ReaderRoute.svelte')
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    const { container } = render(ReaderRoute, { params: { id: 'm1' } })

    await waitFor(() => expect(container.querySelector('.slot img')).toBeTruthy())

    const jsonReads = fetchImpl.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === 'GET')
    expect(jsonReads).toHaveLength(1)
    expect(jsonReads[0][0]).toContain('/media-items/m1/reader-context')
    expect(jsonReads[0][0]).not.toMatch(/\/pages|\/previous|\/next$/)
  })

  it('uses the same context request when mounted without the route', async () => {
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    const { container } = render(Reader, { params: { id: 'm1' } })

    await waitFor(() => expect(container.querySelector('.slot img')).toBeTruthy())

    const jsonReads = fetchImpl.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === 'GET')
    expect(jsonReads).toHaveLength(1)
    expect(jsonReads[0][0]).toContain('/media-items/m1/reader-context')
  })

  it('aborts an obsolete route context and clears its active image before replacement loads', async () => {
    const { default: ReaderRoute } = await import('../src/reader/ReaderRoute.svelte')
    let secondSignal
    let resolveSecond
    const second = new Promise((resolve) => (resolveSecond = resolve))
    const contextFor = (id) => ({
      ...CONTEXT,
      item: { ...ITEM, id },
      pages: [{ ...PAGES[0] }],
      nextId: null,
    })
    globalThis.fetch = vi.fn((url, init = {}) => {
      if (url.includes('/media-items/m1/reader-context')) return Promise.resolve(reply(contextFor('m1')))
      if (url.includes('/media-items/m2/reader-context')) {
        secondSignal = init.signal
        return second
      }
      if (url.includes('/media-items/m3/reader-context')) return Promise.resolve(reply(contextFor('m3')))
      throw new Error(`unexpected request: ${url}`)
    })
    const { container, rerender } = render(ReaderRoute, { params: { id: 'm1' } })
    const oldImage = await waitFor(() => {
      const image = container.querySelector('.slot img[src]')
      expect(image).toBeTruthy()
      return image
    })

    await rerender({ params: { id: 'm2' } })
    await waitFor(() => expect(oldImage.getAttribute('src')).toBeNull())
    expect(secondSignal.aborted).toBe(false)

    await rerender({ params: { id: 'm3' } })
    expect(secondSignal.aborted).toBe(true)
    await waitFor(() => expect(container.querySelector('.slot img[src]')).toBeTruthy())
    resolveSecond(reply(contextFor('m2')))
  })

  it('replaces reader history for back, list, previous, and next navigation', async () => {
    globalThis.fetch = standardRoutes()
    const initialContext = { ...CONTEXT, previousId: 'm0' }
    const { container } = render(Reader, { params: { id: 'm1' }, initialContext })

    await screen.findByTestId('position')
    await fireEvent.click(screen.getByTestId('keyboard-chrome-toggle'))
    await waitFor(() => expect(screen.getByTestId('previous-item')).not.toBeDisabled())

    await fireEvent.click(container.querySelector('.topbar a'))
    await fireEvent.click(container.querySelector('.bottombar a'))
    await fireEvent.click(screen.getByTestId('previous-item'))
    await fireEvent.click(screen.getByTestId('next-item'))

    expect(router.replace.mock.calls.map(([path]) => path)).toEqual([
      '/series/s1',
      '/series/s1',
      '/read/m0',
      '/read/m2',
    ])
    expect(router.push).not.toHaveBeenCalled()
  })

  it('shows only the page until the reader surface is tapped', async () => {
    // A permanently visible chrome toggle was added on top of the source reader. On a
    // phone it occupied the top-right corner even while the bars were closed, and when
    // the bars opened it became a second settings-shaped control over the toolbar.
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' } })

    await waitFor(() => expect(container.querySelector('.scroll')).toBeInTheDocument())

    expect(screen.queryByTestId('toggle-chrome')).not.toBeInTheDocument()
    expect(screen.getByTestId('keyboard-chrome-toggle')).toHaveClass('visually-hidden')
    expect(container.querySelector('.topbar')).not.toBeInTheDocument()
    expect(container.querySelector('.bottombar')).not.toBeInTheDocument()
  })

  it('announces the position once, as live text', async () => {
    // The base put the page number in every image's alt attribute — a position rather
    // than a description, and noise repeated on every page for a screen reader.
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    // Rendered only once the count is known, so finding it at all is part of the claim.
    const position = await screen.findByTestId('position')
    expect(position).toHaveAttribute('aria-live', 'polite')
    expect(position.textContent).toContain('3')
  })

  it('keeps the live position out of the visual reading surface', async () => {
    // The live region is useful to assistive technology, but rendering it as a fixed
    // pill put a permanent page counter over the comic. The visible counter belongs
    // in the bottom bar and therefore appears only after a tap.
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    expect(await screen.findByTestId('position')).toHaveClass('visually-hidden')
  })

  it('takes the total from the analyzed count, not from how many pages were delivered', async () => {
    // The discriminating case for the field name. Every other test here has as many delivered
    // pages as the item claims, so `media.pageCount` and `pages.length` are the same number and a
    // misspelled read of the first silently falls through to the second and still looks right.
    // Here they disagree: the item was analyzed as five pages and delivery returned three.
    //
    // Five is the honest total — it is how long the item is — and it is also what the loader
    // prioritises against. Three would be reporting a delivery outcome as the item's length.
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 5 } }
    globalThis.fetch = routes([
      ['/media-items/m1/reader-context', reply({ ...CONTEXT, item })],
    ])
    render(Reader, { params: { id: 'm1' } })

    const position = await screen.findByTestId('position')
    expect(position.textContent).toContain('5')
    expect(position.textContent).not.toContain('3')
  })

  it('marks page images as decorative', async () => {
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' } })

    await waitFor(() => expect(container.querySelectorAll('.slot img').length).toBeGreaterThan(0))
    for (const image of container.querySelectorAll('.slot img')) {
      expect(image.getAttribute('alt')).toBe('')
      expect(image).toHaveAttribute('role', 'presentation')
    }
  })

  it('retries only the failed page inside its reserved slot', async () => {
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' }, initialContext: CONTEXT })

    await waitFor(() => expect(container.querySelector('.slot img')?.getAttribute('src')).toBeTruthy())
    const first = container.querySelectorAll('.slot img')[0]
    await fireEvent.error(first)
    await waitFor(() => expect(first.getAttribute('src')).toBeTruthy())
    await fireEvent.error(first)

    const retry = await screen.findByTestId('retry-page-1')
    const slots = container.querySelectorAll('.slot')
    expect(slots[0].getAttribute('style')).toContain('aspect-ratio')
    const unrelated = slots[1].querySelector('img')
    await waitFor(() => expect(unrelated.getAttribute('src')).toBeTruthy())
    const unrelatedSource = unrelated.getAttribute('src')

    await fireEvent.click(retry)

    expect(slots[1].querySelector('img')).toBe(unrelated)
    expect(unrelated.getAttribute('src')).toBe(unrelatedSource)
    expect(slots[0].querySelector('img')).toBeTruthy()
  })

  it('names each inline retry control with its failed page number', async () => {
    const pages = PAGES.slice(0, 2)
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 2 } }
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages },
    })

    const first = await waitFor(() => {
      const image = container.querySelectorAll('.slot img')[0]
      expect(image?.getAttribute('src')).toBeTruthy()
      return image
    })
    await fireEvent.error(first)
    await waitFor(() => expect(first.getAttribute('src')).toBeTruthy())
    await fireEvent.error(first)
    const firstRetry = await screen.findByTestId('retry-page-1')

    const second = await waitFor(() => {
      const image = container.querySelectorAll('.slot')[1].querySelector('img')
      expect(image?.getAttribute('src')).toBeTruthy()
      return image
    })
    await fireEvent.error(second)
    await waitFor(() => expect(second.getAttribute('src')).toBeTruthy())
    await fireEvent.error(second)
    const secondRetry = await screen.findByTestId('retry-page-2')

    expect(firstRetry).toHaveAccessibleName(/1/)
    expect(secondRetry).toHaveAccessibleName(/2/)
    expect(firstRetry).not.toHaveAccessibleName(secondRetry.textContent)
  })

  it('offers a keyboard path to the chrome', async () => {
    // The base toggled the bar from an onclick on a div, so there was no way to reach it
    // by keyboard at all: arrow keys paged but nothing opened it.
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    const toggle = await screen.findByTestId('keyboard-chrome-toggle')
    expect(toggle.tagName).toBe('BUTTON')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    await fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('open-settings')).toBeInTheDocument()
  })

  it('keeps both chrome bars inside the horizontal safe area', () => {
    // Vitest's jsdom does not apply Svelte's scoped CSS, so exercise the compiled
    // stylesheet that the browser receives and inspect the complete cascade for each bar.
    const source = readFileSync(join(process.cwd(), 'src/reader/Reader.svelte'), 'utf8')
    const css = compile(source, { filename: 'src/reader/Reader.svelte' }).css.code
    const declarationsFor = (className) =>
      [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
        .filter(([, selectors]) => selectors.includes(`.${className}`))
        .map(([, , declarations]) => declarations)
        .join('\n')

    for (const className of ['topbar', 'bottombar']) {
      const declarations = declarationsFor(className)
      expect(declarations).toContain(
        'padding-left: max(var(--space-3), calc(var(--inset-left) + var(--space-2)))',
      )
      expect(declarations).toContain(
        'padding-right: max(var(--space-3), calc(var(--inset-right) + var(--space-2)))',
      )
    }
  })

  it('closes the chrome on Escape', async () => {
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    const toggle = await screen.findByTestId('keyboard-chrome-toggle')
    await fireEvent.click(toggle)
    await fireEvent.keyDown(window, { key: 'Escape' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('puts the settings panel in the shared dialog', async () => {
    // The base's settings panel was a plain fixed div with no role, no aria-modal and no
    // Escape, while another dialog in the same codebase did all three.
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    await fireEvent.click(await screen.findByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('open-settings'))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
  })

  it('splits a spread and orders the halves by direction', async () => {
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' } })

    await fireEvent.click(await screen.findByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('open-settings'))
    // split-scroll, not split: `split` is one-page-at-a-time with spreads cut, so it
    // renders a single slot. Only a scrolling mode lays every slot out at once.
    await fireEvent.click(screen.getByTestId('mode-split-scroll'))

    // Page 2 is landscape, so it becomes two slots and the total is four.
    await waitFor(() => expect(container.querySelectorAll('.slot').length).toBe(4))
    const halves = [...container.querySelectorAll('.slot.half')]
    expect(halves).toHaveLength(2)
    expect(halves[0].classList.contains('right')).toBe(false)

    await fireEvent.click(screen.getByTestId('direction-rtl'))
    await waitFor(() => {
      const reordered = [...container.querySelectorAll('.slot.half')]
      expect(reordered[0].classList.contains('right')).toBe(true)
    })
  })

  it('reserves each slot’s aspect ratio before the image loads', async () => {
    // Without this every slot has no height, so all of them are inside the viewport at
    // once and lazy loading has nothing left to defer.
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' } })

    await waitFor(() => expect(container.querySelector('.slot')).toBeTruthy())
    expect(container.querySelector('.slot').getAttribute('style')).toContain('aspect-ratio')
  })

  it('remembers the layout choice against the series, not globally', async () => {
    // Per series because one shelf holds both Japanese manga and a webtoon, which want
    // opposite layouts; a single global setting is wrong for one of them on every open.
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    await fireEvent.click(await screen.findByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('open-settings'))
    await fireEvent.click(screen.getByTestId('mode-paged'))

    await waitFor(() => expect(localStorage.getItem('xoboro.pref.anonymous.s1.mode')).toBe('paged'))
    // The old global key is what made every series share one answer.
    expect(localStorage.getItem('xoboro.reader.mode')).toBeNull()
  })

  it('restores the layout stored for this series and ignores another series', async () => {
    // Both halves matter and each fails differently. Reading the series' own value is
    // what the old global-only code could not do; ignoring s9's is what breaks if the
    // series ever drops out of the key.
    localStorage.setItem('xoboro.pref.anonymous.s1.direction', 'rtl')
    localStorage.setItem('xoboro.pref.anonymous.s9.mode', 'paged')
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    await fireEvent.click(await screen.findByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('open-settings'))

    await waitFor(() =>
      expect(screen.getByTestId('direction-rtl').getAttribute('aria-pressed')).toBe('true'),
    )
    expect(screen.getByTestId('mode-scroll').getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByTestId('mode-paged').getAttribute('aria-pressed')).toBe('false')
  })

  it('falls back to the pre-upgrade global layout for a series with none stored', async () => {
    // The upgrade must not reset readers who had already chosen: the old global key is
    // still read, below the per-series value.
    localStorage.setItem('xoboro.reader.mode', 'paged')
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    await fireEvent.click(await screen.findByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('open-settings'))

    expect(screen.getByTestId('mode-paged').getAttribute('aria-pressed')).toBe('true')
  })

  it('sets both axes from the Japanese manga preset', async () => {
    // The gap this closes: reading manga meant knowing to pick `paged` *and* `rtl`, and
    // nothing on the sheet said the two went together.
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    await fireEvent.click(await screen.findByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('open-settings'))
    await fireEvent.click(screen.getByTestId('preset-manga'))

    await waitFor(() => {
      expect(screen.getByTestId('mode-paged').getAttribute('aria-pressed')).toBe('true')
      expect(screen.getByTestId('direction-rtl').getAttribute('aria-pressed')).toBe('true')
    })
    expect(localStorage.getItem('xoboro.pref.anonymous.s1.direction')).toBe('rtl')
  })

  it('disables navigation at the end of the series rather than wrapping', async () => {
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    await screen.findByTestId('position')
    await fireEvent.click(screen.getByTestId('keyboard-chrome-toggle'))
    // No previous item: the route answered 404, which means the start of the series.
    await waitFor(() => expect(screen.getByTestId('previous-item')).toBeDisabled())
    await waitFor(() => expect(screen.getByTestId('next-item')).not.toBeDisabled(), {
      timeout: 3_000,
    })
  })

  it('does not interrupt reading when the server already has newer progress', async () => {
    // A stale response can be a delayed write from this same browser. The server has
    // already protected its newer row, so turning that transport detail into a modal
    // asks the reader a question they did not initiate.
    globalThis.fetch = routes([
      ['/media-items/m1/reader-context', reply(CONTEXT)],
      ['/media-items/m1/progress', reply({ code: 'stale_progress', message: 'stale' }, 409)],
    ])

    render(Reader, { params: { id: 'm1' } })
    await screen.findByTestId('position')

    // Move, which schedules a debounced write that will be refused.
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    await new Promise((resolve) => setTimeout(resolve, 900))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('position').textContent).toContain('2')
  })

  it('shows progress failures without shifting layout and clears them after a successful write', async () => {
    let writes = 0
    const fetchImpl = standardRoutes([
      [
        '/media-items/m1/progress',
        () => {
          writes += 1
          return writes === 1
            ? reply({ code: 'internal_error', message: 'failed' }, 500)
            : reply({ page: 3, completed: true, readAtMillis: 2, updatedAtMillis: 2 })
        },
      ],
    ])
    globalThis.fetch = fetchImpl
    localStorage.setItem('xoboro.reader.mode', 'paged')
    render(Reader, { params: { id: 'm1' }, initialContext: CONTEXT })

    await screen.findByTestId('position')
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    const progressError = await screen.findByTestId('progress-error', {}, { timeout: 2_000 })
    expect(getComputedStyle(progressError).position).toBe('fixed')

    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    await waitFor(() => expect(screen.queryByTestId('progress-error')).toBeNull(), { timeout: 2_000 })
  })

  it('records a one-page paged comic after its first image is actually shown', async () => {
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 1 } }
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    localStorage.setItem('xoboro.reader.mode', 'paged')
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages: PAGES.slice(0, 1) },
    })

    const image = await waitFor(() => {
      const candidate = container.querySelector('.stage img[src]')
      expect(candidate).toBeTruthy()
      return candidate
    })
    await fireEvent.load(image)
    window.dispatchEvent(new Event('pagehide'))

    const writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(writes).toHaveLength(1)
    expect(JSON.parse(writes[0][1].body)).toMatchObject({ page: 1, completed: true })
  })

  it('completes an initially resumed final paged view after its image is shown', async () => {
    const item = {
      ...ITEM,
      progress: { page: 3, completed: false, readAtMillis: 1, updatedAtMillis: 1 },
    }
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    localStorage.setItem('xoboro.reader.mode', 'paged')
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item },
    })

    const image = await waitFor(() => {
      const candidate = container.querySelector('.stage img[src*="/pages/3"]')
      expect(candidate).toBeTruthy()
      return candidate
    })
    await fireEvent.load(image)
    window.dispatchEvent(new Event('pagehide'))

    const writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(writes).toHaveLength(1)
    expect(JSON.parse(writes[0][1].body)).toMatchObject({ page: 3, completed: true })
  })

  it('flushes the newest pending progress with keepalive on pagehide', async () => {
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    localStorage.setItem('xoboro.reader.mode', 'paged')
    render(Reader, { params: { id: 'm1' }, initialContext: CONTEXT })

    await screen.findByTestId('position')
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    window.dispatchEvent(new Event('pagehide'))

    const writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(writes).toHaveLength(1)
    expect(writes[0][1].keepalive).toBe(true)
    expect(JSON.parse(writes[0][1].body)).toMatchObject({ page: 3, completed: true })
  })

  it('keeps an ordinary in-flight snapshot available for a pagehide keepalive retry', async () => {
    let finishOrdinary
    const writes = []
    globalThis.fetch = routes([
      [
        '/media-items/m1/progress',
        (_url, init) => {
          writes.push(init)
          if (writes.length === 1) {
            return new Promise((resolve) => {
              finishOrdinary = () =>
                resolve(reply({ page: 2, completed: false, readAtMillis: 1, updatedAtMillis: 1 }))
            })
          }
          return reply({ page: 2, completed: false, readAtMillis: 2, updatedAtMillis: 2 })
        },
      ],
    ])
    localStorage.setItem('xoboro.reader.mode', 'paged')
    render(Reader, { params: { id: 'm1' }, initialContext: CONTEXT })

    await screen.findByTestId('position')
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    await waitFor(() => expect(writes).toHaveLength(1), { timeout: 2_000 })

    window.dispatchEvent(new Event('pagehide'))

    await waitFor(() => expect(writes).toHaveLength(2))
    expect(writes[0].keepalive).not.toBe(true)
    expect(writes[1].keepalive).toBe(true)
    expect(JSON.parse(writes[1].body)).toMatchObject({ page: 2, completed: false })

    finishOrdinary()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByTestId('progress-error')).toBeNull()
  })

  it('re-sends the newest unconfirmed progress with keepalive when the page becomes hidden', async () => {
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    localStorage.setItem('xoboro.reader.mode', 'paged')
    render(Reader, { params: { id: 'm1' }, initialContext: CONTEXT })

    await screen.findByTestId('position')
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))

    const writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(writes).toHaveLength(1)
    expect(writes[0][1].keepalive).toBe(true)
    expect(JSON.parse(writes[0][1].body)).toMatchObject({ page: 2, completed: false })
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
  })

  it('ignores a delayed progress rejection from the item replaced by a route change', async () => {
    let rejectOldWrite
    globalThis.fetch = vi.fn((url, init = {}) => {
      if (url.includes('/media-items/m1/progress') && init.method === 'PUT') {
        return new Promise((resolve, reject) => {
          rejectOldWrite = reject
        })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    localStorage.setItem('xoboro.reader.mode', 'paged')
    const nextContext = {
      ...CONTEXT,
      item: { ...ITEM, id: 'm2', title: 'Replacement' },
      previousId: 'm1',
      nextId: null,
      pages: [{ ...PAGES[0] }],
    }
    const { container, rerender } = render(Reader, {
      params: { id: 'm1' },
      initialContext: CONTEXT,
    })

    await screen.findByTestId('position')
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    await rerender({ params: { id: 'm2' }, initialContext: nextContext })
    await waitFor(() =>
      expect(container.querySelector('.stage img')?.getAttribute('src')).toContain(
        '/media-items/m2/pages/1',
      ),
    )

    rejectOldWrite(new Error('old item failed late'))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByTestId('progress-error')).toBeNull()
  })

  it('advances on the physical left key when reading right to left', async () => {
    localStorage.setItem('xoboro.reader.direction', 'rtl')
    localStorage.setItem('xoboro.reader.mode', 'paged')
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    render(Reader, { params: { id: 'm1' } })

    await screen.findByTestId('position')
    await fireEvent.keyDown(window, { key: 'ArrowLeft' })

    // Page 2 of 3, reached by pressing left — which is forward in a right-to-left comic.
    await waitFor(() =>
      expect(screen.getByTestId('position').textContent).toContain('2'),
    )
  })

  it('maps right-to-left swipes to the same logical actions as taps and keys', async () => {
    localStorage.setItem('xoboro.reader.direction', 'rtl')
    localStorage.setItem('xoboro.reader.mode', 'paged')
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' }, initialContext: CONTEXT })

    await screen.findByTestId('position')
    const stage = container.querySelector('.stage')
    await tap(stage, { moveX: 80 })
    await waitFor(() => expect(screen.getByTestId('position').textContent).toContain('2'))

    await tap(stage, { x: 200, moveX: -80 })
    await waitFor(() => expect(screen.getByTestId('position').textContent).toContain('1'))
  })

  it('tracks a page taller than two viewports at the real viewport centre and recalculates on resize', async () => {
    const tallPages = [PAGES[0], { ...PAGES[1], width: 400, height: 3000 }, PAGES[2]]
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, pages: tallPages },
    })

    await waitFor(() => expect(container.querySelectorAll('.slot')).toHaveLength(3))
    await new Promise((resolve) => setTimeout(resolve, 20))
    const slots = container.querySelectorAll('.slot')
    place(slots[0], { top: -900, bottom: 0 })
    place(slots[1], { top: 0, bottom: 2_000 })
    place(slots[2], { top: 2_000, bottom: 2_900 })

    await fireEvent.scroll(window)
    await waitFor(() => expect(screen.getByTestId('position').textContent).toContain('2'))

    place(slots[1], { top: -1_600, bottom: 400 })
    place(slots[2], { top: 400, bottom: 1_300 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 1_000 })
    window.dispatchEvent(new Event('resize'))

    await waitFor(() => expect(screen.getByTestId('position').textContent).toContain('3'))
  })

  it('does not let history scroll restoration overwrite the last read page on exit', async () => {
    const writes = []
    globalThis.fetch = routes([
      [
        '/media-items/m1/progress',
        (_url, init) => {
          const progress = JSON.parse(init.body)
          writes.push(progress)
          return reply({ ...progress, readAtMillis: 1, updatedAtMillis: 1 })
        },
      ],
    ])
    const { container, unmount } = render(Reader, {
      params: { id: 'm1' },
      initialContext: CONTEXT,
    })

    await waitFor(() => expect(container.querySelectorAll('.slot')).toHaveLength(3))
    await new Promise((resolve) => setTimeout(resolve, 20))
    const slots = container.querySelectorAll('.slot')
    place(slots[0], { top: -900, bottom: 0 })
    place(slots[1], { top: 0, bottom: 1_500 })
    place(slots[2], { top: 1_500, bottom: 2_400 })

    await fireEvent.scroll(window)
    await waitFor(() => expect(screen.getByTestId('position').textContent).toContain('2'))
    await waitFor(() => expect(writes.at(-1)?.page).toBe(2), { timeout: 2_000 })
    const confirmedWrites = writes.length

    // A real browser dispatches popstate while the old reading scroll position is still
    // visible, then restores the destination history entry's document scroll to zero.
    window.dispatchEvent(new PopStateEvent('popstate'))
    place(slots[0], { top: 0, bottom: 1_200 })
    place(slots[1], { top: 1_200, bottom: 2_700 })
    place(slots[2], { top: 2_700, bottom: 3_600 })
    await fireEvent.scroll(window)
    await new Promise((resolve) => setTimeout(resolve, 20))
    unmount()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(writes).toHaveLength(confirmedWrites)
    expect(writes.at(-1)).toMatchObject({ page: 2, completed: false })
  })

  it('freezes visible-page tracking before toolbar route replacement', async () => {
    const writes = []
    globalThis.fetch = routes([
      [
        '/media-items/m1/progress',
        (_url, init) => {
          const progress = JSON.parse(init.body)
          writes.push(progress)
          // Keep the navigation flush in flight while the component is destroyed. A
          // second freeze must not issue a duplicate request for the same snapshot.
          return new Promise(() => {})
        },
      ],
    ])
    const { container, unmount } = render(Reader, {
      params: { id: 'm1' },
      initialContext: CONTEXT,
    })

    await waitFor(() => expect(container.querySelectorAll('.slot')).toHaveLength(3))
    await new Promise((resolve) => setTimeout(resolve, 20))
    const slots = container.querySelectorAll('.slot')
    place(slots[0], { top: -900, bottom: 0 })
    place(slots[1], { top: 0, bottom: 1_500 })
    place(slots[2], { top: 1_500, bottom: 2_400 })
    await fireEvent.scroll(window)
    await waitFor(() => expect(screen.getByTestId('position').textContent).toContain('2'))

    await fireEvent.click(screen.getByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(container.querySelector('.topbar a'))
    expect(router.replace).toHaveBeenCalledWith('/series/s1')
    expect(writes).toHaveLength(1)
    expect(writes[0]).toMatchObject({ page: 2, completed: false })

    place(slots[0], { top: 0, bottom: 1_200 })
    place(slots[1], { top: 1_200, bottom: 2_700 })
    place(slots[2], { top: 2_700, bottom: 3_600 })
    await fireEvent.scroll(window)
    await new Promise((resolve) => setTimeout(resolve, 20))
    unmount()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(writes).toHaveLength(1)
    expect(writes.at(-1)).toMatchObject({ page: 2, completed: false })
  })

  it('keeps the first half of the final split page incomplete until its second half', async () => {
    localStorage.setItem('xoboro.reader.mode', 'split')
    const finalSpread = [PAGES[0], { ...PAGES[1], number: 2 }]
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 2 } }
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages: finalSpread },
    })

    await screen.findByTestId('position')
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    await new Promise((resolve) => setTimeout(resolve, 900))
    let writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(JSON.parse(writes.at(-1)[1].body)).toMatchObject({ page: 2, completed: false })

    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    await new Promise((resolve) => setTimeout(resolve, 900))
    writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(JSON.parse(writes.at(-1)[1].body)).toMatchObject({ page: 2, completed: true })
  })

  it('marks document scrolling complete at bottom and keeps a later observer write complete', async () => {
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 2 } }
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages: PAGES.slice(0, 2) },
    })

    await waitFor(() => expect(container.querySelectorAll('.slot')).toHaveLength(2))
    await new Promise((resolve) => setTimeout(resolve, 20))
    const slots = container.querySelectorAll('.slot')
    place(slots[0], { top: -900, bottom: 0 })
    place(slots[1], { top: 0, bottom: 1_500 })
    await fireEvent.scroll(window)
    await new Promise((resolve) => setTimeout(resolve, 900))
    let writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(JSON.parse(writes.at(-1)[1].body)).toMatchObject({ page: 2, completed: false })

    Object.defineProperties(document.documentElement, {
      scrollTop: { configurable: true, value: 2000 },
    })
    await fireEvent.scroll(window)
    await new Promise((resolve) => setTimeout(resolve, 900))
    writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(JSON.parse(writes.at(-1)[1].body)).toMatchObject({ page: 2, completed: true })

    await fireEvent.scroll(window)
    await new Promise((resolve) => setTimeout(resolve, 900))
    writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(JSON.parse(writes.at(-1)[1].body)).toMatchObject({ page: 2, completed: true })
  })

  it('keeps the short final scrolling view authoritative at document bottom', async () => {
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 2 } }
    const fetchImpl = standardRoutes()
    globalThis.fetch = fetchImpl
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages: PAGES.slice(0, 2) },
    })

    await waitFor(() => expect(container.querySelectorAll('.slot')).toHaveLength(2))
    await new Promise((resolve) => setTimeout(resolve, 20))
    const slots = container.querySelectorAll('.slot')
    place(slots[0], { top: 0, bottom: 800 })
    place(slots[1], { top: 800, bottom: 1_000 })
    Object.defineProperty(document.documentElement, 'scrollTop', {
      configurable: true,
      value: 2_000,
    })

    await fireEvent.scroll(window)
    await new Promise((resolve) => setTimeout(resolve, 900))

    const writes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(JSON.parse(writes.at(-1)[1].body)).toMatchObject({ page: 2, completed: true })
  })

  it('uses the same manifest-aware page request in scrolling and paged modes', async () => {
    localStorage.setItem('xoboro.reader.width', '400')
    Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: 3 })
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 400 })
    const pages = [{ number: 1, mediaType: 'image/jpeg', width: 1600, height: 2400 }]
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 1 } }
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages },
    })

    const scrollingImage = await waitFor(() => {
      const image = container.querySelector('.scroll img[src]')
      expect(image).toBeTruthy()
      return image
    })
    expect(scrollingImage.getAttribute('src')).toContain('maxWidth=1200')
    const scrollingSource = scrollingImage.getAttribute('src')

    await fireEvent.click(screen.getByTestId('keyboard-chrome-toggle'))
    await fireEvent.click(screen.getByTestId('open-settings'))
    await fireEvent.click(screen.getByTestId('mode-paged'))
    const pagedImage = await waitFor(() => {
      const image = container.querySelector('.stage img[src]')
      expect(image).toBeTruthy()
      return image
    })
    expect(pagedImage.getAttribute('src')).toBe(scrollingSource)
  })

  it('sizes a tall paged image by its height-constrained rendered width', async () => {
    localStorage.setItem('xoboro.reader.mode', 'paged')
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 400 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 })
    Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: 3 })
    const pages = [{ number: 1, mediaType: 'image/jpeg', width: 1600, height: 6400 }]
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 1 } }
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages },
    })

    await waitFor(() => expect(container.querySelector('.stage img[src]')).toBeTruthy())
    expect(container.querySelector('.stage img').getAttribute('src')).toContain('maxWidth=600')
  })

  it('sizes a fit-height scrolling image by viewport height and aspect ratio', async () => {
    localStorage.setItem('xoboro.reader.fit', 'height')
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 400 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 })
    Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: 3 })
    const pages = [{ number: 1, mediaType: 'image/jpeg', width: 1600, height: 6400 }]
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 1 } }
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages },
    })

    await waitFor(() => expect(container.querySelector('.scroll img[src]')).toBeTruthy())
    expect(container.querySelector('.scroll img').getAttribute('src')).toContain('maxWidth=600')
    expect(container.querySelector('.scroll .slot')).toHaveStyle({ width: '200px' })
    expect(getComputedStyle(container.querySelector('.scroll img')).width).toBe('100%')
  })

  it('doubles the height-constrained visible half when requesting a split spread', async () => {
    localStorage.setItem('xoboro.reader.mode', 'split')
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1000 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 600 })
    Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: 2 })
    const pages = [{ number: 1, mediaType: 'image/jpeg', width: 1600, height: 1500 }]
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 1 } }
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages },
    })

    await waitFor(() => expect(container.querySelector('.stage img[src]')).toBeTruthy())
    expect(container.querySelector('.stage img').getAttribute('src')).toContain('maxWidth=1280')
    expect(container.querySelector('.stage .slot')).toHaveStyle({ width: '320px' })
    expect(getComputedStyle(container.querySelector('.stage img')).width).toBe('200%')
    expect(getComputedStyle(container.querySelector('.stage img')).maxWidth).toBe('none')
  })

  it('recalculates rendered and requested dimensions after an orientation resize', async () => {
    localStorage.setItem('xoboro.reader.fit', 'height')
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 400 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 })
    Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: 2 })
    const pages = [{ number: 1, mediaType: 'image/jpeg', width: 1600, height: 6400 }]
    const item = { ...ITEM, media: { ...ITEM.media, pageCount: 1 } }
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, {
      params: { id: 'm1' },
      initialContext: { ...CONTEXT, item, pages },
    })

    await waitFor(() =>
      expect(container.querySelector('.scroll img[src]').getAttribute('src')).toContain(
        'maxWidth=400',
      ),
    )
    expect(container.querySelector('.scroll .slot')).toHaveStyle({ width: '200px' })

    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 900 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 600 })
    window.dispatchEvent(new Event('resize'))

    await waitFor(() => {
      expect(container.querySelector('.scroll .slot')).toHaveStyle({ width: '150px' })
      expect(container.querySelector('.scroll img[src]').getAttribute('src')).toContain(
        'maxWidth=300',
      )
    })
  })

  it('does not page when a key comes from a control', async () => {
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    // Captured after loading, so the comparison is about the key press and not about
    // the item still arriving.
    const position = await screen.findByTestId('position')
    const before = position.textContent
    await fireEvent.keyDown(screen.getByTestId('keyboard-chrome-toggle'), { key: 'ArrowRight' })
    expect(screen.getByTestId('position').textContent).toBe(before)
  })

  /**
   * Resuming where the reader stopped.
   *
   * Two halves, and only the first was ever observable from the suite: the page number
   * the reader resumes *at*, and the scroll that puts that page in front of them. The
   * second is a `scrollIntoView` call, which the setup stubs to a no-op so jsdom does
   * not crash — so it ran, nothing watched it, and a restore that scrolled to the wrong
   * page or to no page at all would have passed.
   */
  /**
   * Routes for an item carrying read progress.
   *
   * Written out rather than handed to `standardRoutes` as an override: that helper
   * matches on `url.includes`, so a `/media-items/m1` entry placed first also answers
   * `/media-items/m1/pages` — the page list comes back as the item, no slots render,
   * and a scroll assertion then fails for a reason that has nothing to do with the
   * restore. The specific paths have to come before the general one.
   */
  function withProgress(progress) {
    const item = { ...ITEM, progress }
    return routes([
      ['/media-items/m1/reader-context', reply({ ...CONTEXT, item })],
      [
        '/media-items/m1/progress',
        reply({ page: 1, completed: false, readAtMillis: 1, updatedAtMillis: 1 }),
      ],
    ])
  }

  function progressed(page) {
    return withProgress({ page, completed: false, readAtMillis: 1, updatedAtMillis: 1 })
  }

  /** Records which element each restore scrolled to, without changing what runs. */
  function watchScrolls() {
    const scrolled = []
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = function scrollIntoView(...args) {
      scrolled.push(this)
      return original?.apply(this, args)
    }
    return {
      scrolled,
      restore: () => {
        Element.prototype.scrollIntoView = original
      },
    }
  }

  it('resumes at the stored page', async () => {
    globalThis.fetch = progressed(2)
    render(Reader, { params: { id: 'm1' } })

    await waitFor(() =>
      expect(screen.getByTestId('position').textContent).toContain('2'),
    )
  })

  it('scrolls the resumed page into view, not just the first one', async () => {
    const watch = watchScrolls()
    try {
      globalThis.fetch = progressed(3)
      render(Reader, { params: { id: 'm1' } })

      await waitFor(() => expect(watch.scrolled.length).toBeGreaterThan(0))
      const target = watch.scrolled.at(-1)
      expect(target.getAttribute('data-page')).toBe('3')
    } finally {
      watch.restore()
    }
  })

  /**
   * A finished item starts again at the first page. Resuming at the last one would be
   * offering to re-read the final page forever, and the scroll has to agree with the
   * page number rather than restore the old position underneath it.
   */
  it('starts a completed item at the beginning', async () => {
    const watch = watchScrolls()
    try {
      globalThis.fetch = withProgress({
        page: 3,
        completed: true,
        readAtMillis: 1,
        updatedAtMillis: 1,
      })
      render(Reader, { params: { id: 'm1' } })

      await waitFor(() => expect(watch.scrolled.length).toBeGreaterThan(0))
      expect(watch.scrolled.at(-1).getAttribute('data-page')).toBe('1')
      expect(screen.getByTestId('position').textContent).toContain('1')
    } finally {
      watch.restore()
    }
  })

  /**
   * Tapping the page in the scrolling modes.
   *
   * The pointer handlers lived only on the paged stage, so in `scroll` — the shipped
   * default, and the one a webtoon is read in — tapping the page did nothing at all.
   * The only way to the bar was a 40px button in one corner, which is not how a reader
   * holding a phone in one hand reaches for it.
   *
   * Every other test in this file opens the chrome through that button, so nothing
   * covered the surface a reader actually taps.
   */
  async function tap(node, { x = 200, y = 300, moveX = 0, moveY = 0 } = {}) {
    await fireEvent.pointerDown(node, { clientX: x, clientY: y })
    await fireEvent.pointerUp(node, { clientX: x + moveX, clientY: y + moveY })
  }

  /**
   * The surface as it exists once the item has arrived.
   *
   * `{#key loadedId}` destroys and recreates this block when the identifier settles, so
   * a node read before then is detached by the time an event reaches it — the handler
   * never runs and the assertion fails for a reason that has nothing to do with the
   * behaviour under test. Waiting for the position, which is only rendered once the page
   * count is known, is what makes the node the one a reader is looking at.
   */
  async function loadedScrollSurface(container) {
    await screen.findByTestId('position')
    return container.querySelector('.scroll')
  }

  it('opens the chrome when the page is tapped in a scrolling mode', async () => {
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' } })

    const toggle = await screen.findByTestId('keyboard-chrome-toggle')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    await tap(await loadedScrollSurface(container))

    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('open-settings')).toBeInTheDocument()
  })

  it('closes the chrome on a second tap', async () => {
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' } })

    const toggle = await screen.findByTestId('keyboard-chrome-toggle')
    const surface = await loadedScrollSurface(container)
    await tap(surface)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    await tap(surface)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  /**
   * A drag is how a reader scrolls, so it must not also be how they open the bar.
   * Without a movement threshold the chrome would appear on every flick.
   */
  it('does not toggle the chrome when the pointer was dragged', async () => {
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' } })

    const toggle = await screen.findByTestId('keyboard-chrome-toggle')
    await tap(await loadedScrollSurface(container), { moveY: 120 })

    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  /**
   * The left and right thirds page the paged stage. In a scrolling mode they must not:
   * scrolling is the navigation there, and a tap near an edge is a reader reaching for
   * the bar rather than asking for the next page.
   */
  it('does not page from the edges in a scrolling mode', async () => {
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' } })

    const surface = await loadedScrollSurface(container)
    const before = screen.getByTestId('position').textContent
    await tap(surface, { x: 10 })

    expect(screen.getByTestId('position').textContent).toBe(before)
    expect(screen.getByTestId('keyboard-chrome-toggle')).toHaveAttribute('aria-expanded', 'true')
  })
})
