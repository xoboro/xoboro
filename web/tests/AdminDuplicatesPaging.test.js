import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import Duplicates from '../src/admin/Duplicates.svelte'

/**
 * Paging two listings that share one loader.
 *
 * The duplicate-pages screen shows candidates and decisions, both server-paged, and one
 * `load` reads both. Each table's `onpage` used to pass the *other* table's committed page
 * as an argument — and a committed page is stale for as long as a load is outstanding. So
 * paging both tables in quick succession advanced the second and silently re-requested the
 * first at the page it held before the click still in flight. Nothing on screen indicated
 * the loss: `load` never set `busy`, and the paging buttons are gated only on
 * `hasPrevious`/`hasNext`.
 *
 * Two earlier attempts at this test failed to reproduce the defect, and both failed the
 * same way: they asserted the rendered outcome. What renders depends on which of two
 * in-flight loads commits last, which is a race the test cannot pin and is not the
 * invariant anyway.
 *
 * The invariant is about the **request**: a click on one listing must never ask the other
 * for a page it has already moved past. So this asserts on what was requested, which is
 * deterministic — the requests are issued synchronously from the click, before anything
 * resolves. `fetch` here hands back promises that are resolved by name, so a load can be
 * left outstanding on purpose.
 *
 * Not covered here, and found while writing this: `DataTable` computes the page to ask for
 * as `page.page + 1` from the **committed** envelope, so clicking Next twice before the
 * first load answers asks for the same page twice and the second click does nothing. That
 * is the same staleness one level up, in a component every admin screen shares, and it is
 * left for its own change rather than folded in here.
 */
function envelope(page, totalPages = 3) {
  return {
    items: [],
    page,
    size: 50,
    totalItems: totalPages * 50,
    totalPages,
    hasPrevious: page > 0,
    hasNext: page < totalPages - 1,
  }
}

/** A `fetch` that records every URL and resolves each request only when told to. */
function deferredFetch() {
  const pending = []
  const urls = []
  const fetch = vi.fn((url) => {
    urls.push(url)
    return new Promise((resolve) => {
      pending.push({ url, resolve })
    })
  })

  function settle(match, body) {
    const index = pending.findIndex((entry) => entry.url.includes(match))
    if (index < 0) throw new Error(`no outstanding request matching ${match} in ${urls}`)
    const [entry] = pending.splice(index, 1)
    const text = JSON.stringify(body)
    entry.resolve({
      ok: true,
      status: 200,
      headers: { get: () => null },
      text: async () => text,
      json: async () => JSON.parse(text),
    })
  }

  /** The `page` each request for `match` asked for, oldest first. */
  function pagesAsked(match) {
    return urls
      .filter((url) => url.includes(match))
      .map((url) => Number(new URL(url, 'http://localhost').searchParams.get('page')))
  }

  return { fetch, settle, pagesAsked, urls }
}

const CANDIDATES = 'duplicate-pages?'
const DECISIONS = 'duplicate-pages/decided'

describe('Duplicates paging', () => {
  it('never re-asks one listing for an earlier page when the other is paged', async () => {
    const { fetch, settle, pagesAsked } = deferredFetch()
    globalThis.fetch = fetch
    render(Duplicates)

    // The first load settles, so both tables have paging controls and both committed pages
    // are 0 — the state in which the stale read looked correct.
    await waitFor(() => expect(pagesAsked(CANDIDATES)).toEqual([0]))
    settle(CANDIDATES, envelope(0))
    settle(DECISIONS, envelope(0))
    await waitFor(() => expect(screen.getByTestId('page-next-candidates')).toBeEnabled())

    // Candidates forward. Deliberately left outstanding: this is the window in which the
    // committed page and the requested page disagree.
    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    await waitFor(() => expect(pagesAsked(CANDIDATES)).toEqual([0, 1]))

    // Decisions forward while that is still in flight.
    await fireEvent.click(screen.getByTestId('page-next-decisions'))
    await waitFor(() => expect(pagesAsked(DECISIONS).length).toBe(3))

    // The whole assertion. Before the fix the third candidates request asked for page 0
    // again, because it was read off an envelope that still said 0 — so the operator's
    // click was undone by a click on an unrelated table.
    expect(pagesAsked(CANDIDATES)).toEqual([0, 1, 1])
    expect(pagesAsked(DECISIONS)).toEqual([0, 0, 1])
  })

  it('discards a load that is no longer the newest', async () => {
    // Two loads in flight can land in either order, and the older one committing last
    // would put the screen on a page nobody asked for. The older is settled second here,
    // which is the ordering that used to decide what rendered.
    const { fetch, settle, pagesAsked } = deferredFetch()
    globalThis.fetch = fetch
    render(Duplicates)

    await waitFor(() => expect(pagesAsked(CANDIDATES)).toEqual([0]))
    settle(CANDIDATES, envelope(0))
    settle(DECISIONS, envelope(0))
    await waitFor(() => expect(screen.getByTestId('page-next-candidates')).toBeEnabled())

    // Load A asks candidates for 1 and decisions for 0; load B asks both for 1. So the
    // decisions request is what tells the two apart, and B is the only one whose answer
    // may reach the screen.
    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    await waitFor(() => expect(pagesAsked(DECISIONS)).toEqual([0, 0]))
    await fireEvent.click(screen.getByTestId('page-next-decisions'))
    await waitFor(() => expect(pagesAsked(DECISIONS)).toEqual([0, 0, 1]))

    // `settle` takes the oldest match, so the first `page=1` candidates reply belongs to A
    // and the second to B. Neither load completes until both of its halves are answered,
    // which is what makes the commit order here chosen rather than raced.
    settle(`${CANDIDATES}page=1`, envelope(1))
    settle(`${DECISIONS}?page=1`, envelope(1))
    settle(`${CANDIDATES}page=1`, envelope(1))
    // B has committed: decisions is on page 1, so it has a previous page.
    await waitFor(() => expect(screen.getByTestId('page-previous-decisions')).toBeEnabled())

    settle(`${DECISIONS}?page=0`, envelope(0))
    // A is answered last and carries decisions page 0. Committing it would send the
    // decisions table back to the first page with nothing on screen to say why.
    await waitFor(() => expect(pagesAsked(DECISIONS).length).toBe(3))
    expect(screen.getByTestId('page-previous-decisions')).toBeEnabled()
  })
})
