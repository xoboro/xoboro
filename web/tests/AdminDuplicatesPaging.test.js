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
 * `load` now sets the `busy` shared by both `DataTable`s for as long as either fetch is
 * outstanding — both tables reload together, so a request in flight for one is a request in
 * flight for the other. That closes the window this file is about at its source: a click on
 * the *other* table can no longer fire while one is loading, rather than firing and having to
 * be reconciled afterwards. The `candidatePage`/`decisionPage`/`loadToken` bookkeeping below
 * stays as the second line of defence for any load not reached through these buttons.
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
  it('blocks a click on the other listing while a load they share is outstanding', async () => {
    // Both tables share one `busy`, because they share one `load()`: a click on either
    // starts a fetch for both, so a request in flight for candidates is a request in flight
    // for decisions too. Before the fix, that combined fetch was not reflected in either
    // button's disabled state, so clicking decisions while candidates was loading fired
    // immediately and read decisions' neighbour off a committed envelope about to change.
    // Now the second click cannot fire at all until the shared load settles.
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
    // committed page and the requested page used to disagree. `load()` reads both listings
    // together, so this click already re-requests decisions too, at its unchanged page 0.
    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    await waitFor(() => expect(pagesAsked(CANDIDATES)).toEqual([0, 1]))
    expect(pagesAsked(DECISIONS)).toEqual([0, 0])
    expect(screen.getByTestId('page-next-decisions')).toBeDisabled()

    // Decisions forward while that load is still outstanding: blocked, so no new request —
    // the count stays exactly what the shared load already produced above.
    await fireEvent.click(screen.getByTestId('page-next-decisions'))
    expect(pagesAsked(DECISIONS)).toEqual([0, 0])

    settle(CANDIDATES, envelope(1))
    settle(DECISIONS, envelope(0))
    await waitFor(() => expect(screen.getByTestId('page-next-decisions')).toBeEnabled())

    // Once the shared load settles, decisions pages forward correctly on its own
    // committed page (0, still unmoved) — untouched by what candidates asked for
    // meanwhile. This third load also re-requests candidates at its own committed page
    // (1), since `load()` still reads both listings together.
    await fireEvent.click(screen.getByTestId('page-next-decisions'))
    await waitFor(() => expect(pagesAsked(DECISIONS)).toEqual([0, 0, 1]))
    expect(pagesAsked(CANDIDATES)).toEqual([0, 1, 1])
  })

  it('blocks a click on candidates while a load decisions started is outstanding', async () => {
    // Symmetric to the previous test — the guard is not one-directional — and confirms
    // that the requested-page bookkeeping this screen already had (`candidatePage`,
    // `decisionPage`) still lines up once the load resolves: adding `busy` did not change
    // which page either table asks for next, only when it is allowed to ask.
    const { fetch, settle, pagesAsked } = deferredFetch()
    globalThis.fetch = fetch
    render(Duplicates)

    await waitFor(() => expect(pagesAsked(CANDIDATES)).toEqual([0]))
    settle(CANDIDATES, envelope(0))
    settle(DECISIONS, envelope(0))
    await waitFor(() => expect(screen.getByTestId('page-next-decisions')).toBeEnabled())

    await fireEvent.click(screen.getByTestId('page-next-decisions'))
    await waitFor(() => expect(pagesAsked(DECISIONS)).toEqual([0, 1]))
    // `load()` reads both listings together, so this click already re-requests candidates
    // too, at its unchanged page 0.
    expect(pagesAsked(CANDIDATES)).toEqual([0, 0])
    expect(screen.getByTestId('page-next-candidates')).toBeDisabled()

    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    expect(pagesAsked(CANDIDATES)).toEqual([0, 0])

    settle(DECISIONS, envelope(1))
    settle(CANDIDATES, envelope(0))
    await waitFor(() => expect(screen.getByTestId('page-next-candidates')).toBeEnabled())

    // Symmetric to the previous test's ending: candidates pages forward correctly on its
    // own committed page (0, still unmoved), and this third load also re-requests
    // decisions at its own committed page (1).
    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    await waitFor(() => expect(pagesAsked(CANDIDATES)).toEqual([0, 0, 1]))
    expect(pagesAsked(DECISIONS)).toEqual([0, 1, 1])
  })

  it('does not re-ask for the same page on a second click before the outstanding load answers', async () => {
    // The other half of the staleness this file is about: even a single listing clicked
    // twice in a row reads the neighbour off the committed envelope, which does not
    // change until the first click's load answers. Both DataTable instances share one
    // `busy`, set for the duration of `load()`, so the second click must be inert rather
    // than issuing a request for the page already asked for.
    const { fetch, settle, pagesAsked } = deferredFetch()
    globalThis.fetch = fetch
    render(Duplicates)

    await waitFor(() => expect(pagesAsked(CANDIDATES)).toEqual([0]))
    settle(CANDIDATES, envelope(0))
    settle(DECISIONS, envelope(0))
    await waitFor(() => expect(screen.getByTestId('page-next-candidates')).toBeEnabled())

    // First click starts a load and is left outstanding on purpose.
    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    await waitFor(() => expect(pagesAsked(CANDIDATES)).toEqual([0, 1]))
    expect(screen.getByTestId('page-next-candidates')).toBeDisabled()

    // Second click while busy: before the fix this asked candidates for page 1 again,
    // computed as `page.page + 1` off the envelope still reading page 0.
    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    expect(pagesAsked(CANDIDATES)).toEqual([0, 1])
    expect(pagesAsked(DECISIONS)).toEqual([0, 0])

    settle(`${CANDIDATES}page=1`, envelope(1))
    settle(`${DECISIONS}?page=0`, envelope(0))
    await waitFor(() => expect(screen.getByTestId('page-next-candidates')).toBeEnabled())

    // Busy clearing does not itself request anything further.
    expect(pagesAsked(CANDIDATES)).toEqual([0, 1])
  })
})
