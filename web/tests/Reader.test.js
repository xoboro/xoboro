import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Reader from '../src/reader/Reader.svelte'
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

const ITEM = {
  id: 'm1',
  title: 'Synthetic Chapter',
  seriesId: 's1',
  // `pageCount` is the field the native API sends. This fixture said `pagesCount`, matching a
  // misspelling in the component, so the two agreed with each other and neither agreed with the
  // server - and the component's `?? pages.length` fallback made the mistake invisible because the
  // fallback happened to be the same number.
  media: { pageCount: 3, status: 'READY' },
  readProgress: null,
}

const PAGES = [
  { number: 1, mediaType: 'image/jpeg', width: 800, height: 1200 },
  { number: 2, mediaType: 'image/jpeg', width: 1600, height: 1200 },
  { number: 3, mediaType: 'image/jpeg', width: 800, height: 1200 },
]

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
    ['/media-items/m1/pages', reply(PAGES)],
    ['/media-items/m1/previous', reply({ code: 'media_item_not_found' }, 404)],
    ['/media-items/m1/next', reply({ id: 'm2' })],
    // `200` with the stored progress, which is what the server answers. This fake replied `204`,
    // copied from an OpenAPI description that was wrong about it; a fake that agrees with the
    // description rather than the server is not a test of the client's contract.
    [
      '/media-items/m1/progress',
      reply({ page: 1, completed: false, readAtMillis: 1, updatedAtMillis: 1 }),
    ],
    ['/media-items/m1', reply(ITEM)],
  ])
}

beforeEach(() => {
  resetProgressClock()
  localStorage.clear()
  // jsdom has no IntersectionObserver, and the reader uses it to follow the reader's
  // position while scrolling.
  globalThis.IntersectionObserver = class {
    observe() {}
    disconnect() {}
  }
  globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0)
})

describe('Reader', () => {
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

  it('takes the total from the analyzed count, not from how many pages were delivered', async () => {
    // The discriminating case for the field name. Every other test here has as many delivered
    // pages as the item claims, so `media.pageCount` and `pages.length` are the same number and a
    // misspelled read of the first silently falls through to the second and still looks right.
    // Here they disagree: the item was analyzed as five pages and delivery returned three.
    //
    // Five is the honest total — it is how long the item is — and it is also what the loader
    // prioritises against. Three would be reporting a delivery outcome as the item's length.
    globalThis.fetch = routes([
      ['/media-items/m1/pages', reply(PAGES)],
      ['/media-items/m1/previous', reply({ code: 'media_item_not_found' }, 404)],
      ['/media-items/m1/next', reply({ id: 'm2' })],
      ['/media-items/m1', reply({ ...ITEM, media: { ...ITEM.media, pageCount: 5 } })],
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

  it('offers a keyboard path to the chrome', async () => {
    // The base toggled the bar from an onclick on a div, so there was no way to reach it
    // by keyboard at all: arrow keys paged but nothing opened it.
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    const toggle = await screen.findByTestId('toggle-chrome')
    expect(toggle.tagName).toBe('BUTTON')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    await fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('open-settings')).toBeInTheDocument()
  })

  it('closes the chrome on Escape', async () => {
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    const toggle = await screen.findByTestId('toggle-chrome')
    await fireEvent.click(toggle)
    await fireEvent.keyDown(window, { key: 'Escape' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('puts the settings panel in the shared dialog', async () => {
    // The base's settings panel was a plain fixed div with no role, no aria-modal and no
    // Escape, while another dialog in the same codebase did all three.
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    await fireEvent.click(await screen.findByTestId('toggle-chrome'))
    await fireEvent.click(screen.getByTestId('open-settings'))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
  })

  it('splits a spread and orders the halves by direction', async () => {
    globalThis.fetch = standardRoutes()
    const { container } = render(Reader, { params: { id: 'm1' } })

    await fireEvent.click(await screen.findByTestId('toggle-chrome'))
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

    await fireEvent.click(await screen.findByTestId('toggle-chrome'))
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

    await fireEvent.click(await screen.findByTestId('toggle-chrome'))
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

    await fireEvent.click(await screen.findByTestId('toggle-chrome'))
    await fireEvent.click(screen.getByTestId('open-settings'))

    expect(screen.getByTestId('mode-paged').getAttribute('aria-pressed')).toBe('true')
  })

  it('sets both axes from the Japanese manga preset', async () => {
    // The gap this closes: reading manga meant knowing to pick `paged` *and* `rtl`, and
    // nothing on the sheet said the two went together.
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    await fireEvent.click(await screen.findByTestId('toggle-chrome'))
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
    await fireEvent.click(screen.getByTestId('toggle-chrome'))
    // No previous item: the route answered 404, which means the start of the series.
    await waitFor(() => expect(screen.getByTestId('previous-item')).toBeDisabled())
    await waitFor(() => expect(screen.getByTestId('next-item')).not.toBeDisabled(), {
      timeout: 3_000,
    })
  })

  it('asks rather than resolving a progress conflict on its own', async () => {
    // The refusal exists so a second device cannot silently rewind this reader's place,
    // so neither jumping nor staying happens without being chosen.
    // The item is read twice: once to open it, and again after the refusal to find out
    // what the newer position actually is. Only the second answer carries progress —
    // if the first did, the reader would already be on the last page and there would be
    // nothing to advance to.
    let itemReads = 0
    globalThis.fetch = routes([
      ['/media-items/m1/pages', reply(PAGES)],
      ['/media-items/m1/previous', reply({ code: 'media_item_not_found' }, 404)],
      ['/media-items/m1/next', reply({ code: 'media_item_not_found' }, 404)],
      ['/media-items/m1/progress', reply({ code: 'stale_progress', message: 'stale' }, 409)],
      [
        '/media-items/m1',
        () => {
          itemReads += 1
          return itemReads === 1
            ? reply(ITEM)
            : reply({ ...ITEM, readProgress: { page: 3, completed: false } })
        },
      ],
    ])

    render(Reader, { params: { id: 'm1' } })
    await screen.findByTestId('position')

    // Move, which schedules a debounced write that will be refused.
    await fireEvent.keyDown(window, { key: 'ArrowRight' })
    await new Promise((resolve) => setTimeout(resolve, 900))

    const body = await screen.findByTestId('conflict-body')
    expect(body.textContent).toContain('3')
    // Both choices offered, neither taken automatically.
    expect(screen.getByTestId('conflict-jump')).toBeInTheDocument()
    expect(screen.getByTestId('conflict-stay')).toBeInTheDocument()
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

  it('does not page when a key comes from a control', async () => {
    globalThis.fetch = standardRoutes()
    render(Reader, { params: { id: 'm1' } })

    // Captured after loading, so the comparison is about the key press and not about
    // the item still arriving.
    const position = await screen.findByTestId('position')
    const before = position.textContent
    await fireEvent.keyDown(screen.getByTestId('toggle-chrome'), { key: 'ArrowRight' })
    expect(screen.getByTestId('position').textContent).toBe(before)
  })
})
