import { render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import Trash from '../src/admin/Trash.svelte'

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

function page(items, totalItems = items.length) {
  return {
    items,
    page: 0,
    size: 50,
    totalItems,
    totalPages: 1,
    hasPrevious: false,
    hasNext: false,
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

/**
 * The screen exists so an operator can see what emptying the trash would destroy.
 *
 * That makes agreement between this screen and the Libraries confirmation the property
 * worth testing: the confirmation counts trashed series *and* trashed media items, so a
 * screen that lists only series shows less than the number the operator is asked to
 * agree to, with nothing explaining the difference.
 */
describe('Trash', () => {
  it('lists trashed media items as well as trashed series', async () => {
    const fetchImpl = routes([
      ['/series?', reply(page([{ id: 's1', title: 'Synthetic Series', mediaItemCount: 12 }]))],
      ['/media-items?', reply(page([{ id: 'm1', title: 'Synthetic Item', seriesId: 's2' }]))],
      ['/libraries', reply([])],
    ])
    globalThis.fetch = fetchImpl
    render(Trash)

    // A trashed item under a series that is not itself trashed appears only in the
    // media-item listing, which is why both are asked for.
    await waitFor(() => expect(screen.getByText('Synthetic Series')).toBeInTheDocument())
    expect(screen.getByText('Synthetic Item')).toBeInTheDocument()

    const asked = fetchImpl.mock.calls.map(([url]) => url)
    expect(asked.some((url) => url.includes('/series?') && url.includes('trashed=true'))).toBe(true)
    expect(asked.some((url) => url.includes('/media-items?') && url.includes('trashed=true'))).toBe(
      true,
    )
  })

  it('does not present a series item count as a trashed count', async () => {
    // The listing carries the series' total item count, not how many of its items are
    // trashed. The column has to be named for the number it holds, or an operator reads
    // 12 as "12 items in the trash" when the trash may hold none of them.
    const fetchImpl = routes([
      ['/series?', reply(page([{ id: 's1', title: 'Synthetic Series', mediaItemCount: 12 }]))],
      ['/media-items?', reply(page([]))],
      ['/libraries', reply([])],
    ])
    globalThis.fetch = fetchImpl
    const { container } = render(Trash)

    await waitFor(() => expect(screen.getByText('Synthetic Series')).toBeInTheDocument())

    // Asserted structurally: the two tables are distinct regions with their own
    // captions, so the count sits under the series listing rather than being presented
    // as the trash's own total.
    const captions = [...container.querySelectorAll('caption')].map((node) => node.textContent)
    expect(new Set(captions).size).toBe(captions.length)
    expect(captions).toHaveLength(2)
  })

  it('offers no restore action', async () => {
    // Reconciliation clears the flag by itself when a later scan finds the files again.
    // A restore button would either do nothing or lie about having put a file back.
    globalThis.fetch = routes([
      ['/series?', reply(page([{ id: 's1', title: 'Synthetic Series', mediaItemCount: 1 }]))],
      ['/media-items?', reply(page([]))],
      ['/libraries', reply([])],
    ])
    const { container } = render(Trash)

    await waitFor(() => expect(screen.getByText('Synthetic Series')).toBeInTheDocument())
    // The paging controls are buttons, so the assertion is about the rows: no row offers
    // an action of any kind.
    expect(container.querySelectorAll('tbody button')).toHaveLength(0)
  })
})
