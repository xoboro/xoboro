import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import Search from '../src/reader/Search.svelte'
import { SCOPES } from '../src/lib/api/catalogSearch.js'

/**
 * The reader's search screen.
 *
 * Assertions are on roles, accessible names and test hooks rather than on copy: the
 * suite runs in Korean, the catalog is translated, and a test that names a label
 * breaks when the wording improves. Fixtures are synthetic throughout — no real
 * publication or series names, per `docs/testing.md`.
 */
function reply(body, status = 200) {
  const text = JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => text,
    json: async () => JSON.parse(text),
  }
}

function envelope(items = [], { page = 0, totalPages = 1, totalItems = items.length } = {}) {
  return {
    items,
    page,
    size: 24,
    totalItems,
    totalPages,
    hasPrevious: page > 0,
    hasNext: page < totalPages - 1,
  }
}

const seriesRow = (id) => ({ id, title: `Synthetic series ${id}`, mediaItemCount: 2 })
const itemRow = (id) => ({ id, title: `Synthetic item ${id}`, seriesTitle: 'Synthetic series' })

const LIBRARIES = [
  { id: 'lib-one', name: 'Synthetic library one' },
  { id: 'lib-two', name: 'Synthetic library two' },
]

const FACET_VALUES = {
  genre: ['gen-a', 'gen-b'],
  seriesTag: ['tag-a'],
  publisher: ['pub-a'],
  language: ['la'],
}

/**
 * A `fetch` that answers each route and records every URL.
 *
 * `facetStatus` fails every facet read; `listingStatus` fails the search itself. They
 * are separate because the screen has to keep working when only one of them breaks.
 */
function server({
  listing = envelope([seriesRow('s1')]),
  listingStatus = 200,
  facetStatus = 200,
  libraryStatus = 200,
} = {}) {
  const urls = []
  const fetch = vi.fn(async (url) => {
    urls.push(url)
    if (url.includes('/facets')) {
      if (facetStatus !== 200) return reply({ code: 'internal_error', message: 'no' }, facetStatus)
      const facet = new URL(url, 'http://localhost').searchParams.get('facet')
      return reply(FACET_VALUES[facet] ?? [])
    }
    if (url.includes('/libraries')) {
      if (libraryStatus !== 200) {
        return reply({ code: 'internal_error', message: 'no' }, libraryStatus)
      }
      return reply(LIBRARIES)
    }
    if (listingStatus !== 200) {
      return reply({ code: 'internal_error', message: 'no' }, listingStatus)
    }
    return reply(typeof listing === 'function' ? listing(url) : listing)
  })
  return { fetch, urls }
}

/** A `fetch` whose listing requests are resolved by hand, so one can be left in flight. */
function deferredServer() {
  const urls = []
  const pending = []
  const fetch = vi.fn(async (url) => {
    urls.push(url)
    if (url.includes('/facets')) {
      const facet = new URL(url, 'http://localhost').searchParams.get('facet')
      return reply(FACET_VALUES[facet] ?? [])
    }
    if (url.includes('/libraries')) return reply(LIBRARIES)
    return new Promise((resolve) => pending.push({ url, resolve }))
  })

  /** Resolves the oldest outstanding listing request whose URL contains `match`. */
  function settle(match, body) {
    const index = pending.findIndex((entry) => entry.url.includes(match))
    if (index < 0) throw new Error(`no outstanding request matching ${match} in ${urls}`)
    const [entry] = pending.splice(index, 1)
    entry.resolve(reply(body))
  }

  return { fetch, urls, settle }
}

const listings = (urls, collection) => urls.filter((url) => url.includes(`/${collection}?`))
const parameters = (url) => new URL(url, 'http://localhost').searchParams
const lastListing = (urls, collection) => parameters(listings(urls, collection).at(-1))

describe('reader search', () => {
  it('labels the query input, so it is reachable without seeing the placeholder', async () => {
    globalThis.fetch = server().fetch
    render(Search)

    const input = screen.getByTestId('search-query')
    // The accessible name comes from a real `<label for>`, not a placeholder — a
    // placeholder is not a name and disappears the moment anything is typed.
    expect(input.labels).toHaveLength(1)
    expect(input.labels[0].textContent.trim()).not.toBe('')
    // And it is not a raw key path, which is what a missing catalog entry renders as.
    expect(input.labels[0].textContent).not.toContain('search.')
  })

  /**
   * The facets enumerate whatever the library contains, so the filter column's height is the
   * catalog's business rather than the form's. One real library's genres and tags came to
   * roughly a hundred rows, and the results sat below all of it: the search screen showed its
   * controls and none of its answers. Capping each group shortened the column; leaving it
   * expanded by default still puts several groups, a sort control and a clear button ahead of
   * the first result.
   *
   * So the column starts closed and the results start at the top. Closed is `hidden`, which is
   * a UA `display: none` the desktop media query overrides in the ordinary cascade - the script
   * never learns the viewport, so there is no breakpoint duplicated between CSS and JS.
   */
  it('starts with the filter column closed, so the results are what is on screen', async () => {
    globalThis.fetch = server().fetch
    render(Search)

    expect(screen.getByTestId('search-filters')).toHaveAttribute('hidden')
    expect(screen.getByTestId('toggle-filters')).toHaveAttribute('aria-expanded', 'false')
  })

  it('opens the filter column when asked', async () => {
    globalThis.fetch = server().fetch
    render(Search)

    await fireEvent.click(screen.getByTestId('toggle-filters'))

    expect(screen.getByTestId('search-filters')).not.toHaveAttribute('hidden')
    expect(screen.getByTestId('toggle-filters')).toHaveAttribute('aria-expanded', 'true')
  })

  /**
   * A closed column would otherwise hide that anything is applied at all, which is the failure
   * the chips beside the results exist to prevent - but the count is what makes the closed
   * control itself honest, before any chip is read.
   */
  it('says how many filters are on while the column is closed', async () => {
    const backend = server()
    globalThis.fetch = backend.fetch
    render(Search)

    await waitFor(() => expect(screen.getByTestId('filter-genre-gen-a')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('filter-genre-gen-a'))

    await waitFor(() =>
      expect(screen.getByTestId('applied-count').textContent).toContain('1'),
    )
    expect(screen.getByTestId('active-filter-genre-gen-a')).toBeInTheDocument()
  })

  it('renders no raw key path, in either scope, for any key it computes', async () => {
    // The i18n suite reads literal `$_('…')` calls out of the source and cannot see a
    // computed one. This screen builds four: `search.scope.${name}`,
    // `search.filters.${parameter}`, `search.filters.oneShotChoices.${choice}` and
    // `search.sort.${scope}.${field}`. A missing entry for any of them renders the key
    // path itself — a visible label, an accessible name, and wrong — with nothing
    // failing. Both scopes are walked, because each renders controls the other does not.
    const { fetch, urls } = server()
    globalThis.fetch = fetch
    const { container } = render(Search)

    await waitFor(() => expect(screen.getByTestId('filter-genre-gen-a')).toBeInTheDocument())
    const seriesText = container.textContent
    // Asserted non-empty first: a selector that found nothing compares an empty string
    // and reports success, which is the trap this check would otherwise fall into.
    expect(seriesText.length).toBeGreaterThan(0)
    expect(seriesText).not.toMatch(/search\.[a-z]/i)
    expect(seriesText).not.toMatch(/common\.[a-z]/i)

    await fireEvent.click(screen.getByTestId('scope-mediaItems'))
    await waitFor(() => expect(listings(urls, 'media-items')).toHaveLength(1))

    const itemText = container.textContent
    expect(itemText.length).toBeGreaterThan(0)
    expect(itemText).not.toMatch(/search\.[a-z]/i)
  })

  it('asks the series listing for the words that were typed', async () => {
    const { fetch, urls } = server()
    globalThis.fetch = fetch
    render(Search)

    // The screen opens on a browse listing: an empty query means "everything", which
    // is what the server does with no `query` parameter.
    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(1))
    expect(parameters(listings(urls, 'series')[0]).has('query')).toBe(false)

    await fireEvent.input(screen.getByTestId('search-query'), {
      target: { value: 'lantern' },
    })
    await fireEvent.click(screen.getByTestId('search-submit'))

    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(2))
    expect(lastListing(urls, 'series').get('query')).toBe('lantern')
  })

  it('asks once for a burst of typing rather than once per keystroke', async () => {
    const { fetch, urls } = server()
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(1))

    const input = screen.getByTestId('search-query')
    for (const value of ['l', 'la', 'lan', 'lant']) {
      await fireEvent.input(input, { target: { value } })
    }

    // Waiting for the debounced request to land, then counting synchronously. Waiting
    // for the *count* would be weaker: with no debounce at all the count passes through
    // 2 on its way to 5, and `waitFor` succeeds the moment its callback does.
    await waitFor(() => expect(lastListing(urls, 'series').get('query')).toBe('lant'), {
      timeout: 2000,
    })
    expect(listings(urls, 'series')).toHaveLength(2)
  })

  it('keeps the newest answer when an older one arrives after it', async () => {
    const { fetch, urls, settle } = deferredServer()
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(1))

    const input = screen.getByTestId('search-query')
    await fireEvent.input(input, { target: { value: 'aa' } })
    await fireEvent.click(screen.getByTestId('search-submit'))
    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(2))

    await fireEvent.input(input, { target: { value: 'bb' } })
    await fireEvent.click(screen.getByTestId('search-submit'))
    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(3))

    // The newest answer lands first, with three matches.
    settle('query=bb', envelope([seriesRow('b1'), seriesRow('b2'), seriesRow('b3')]))
    await waitFor(() => expect(screen.getByTestId('result-summary').textContent).toContain('3'))

    // Then the superseded one lands with one match. Without the sequence guard this
    // overwrites the results for "bb" with the results for "aa" — a screen showing
    // matches for a query the reader has already replaced, and no way to tell.
    settle('query=aa', envelope([seriesRow('a1')]))

    // Flushed by turning the event loop over rather than by waiting on an assertion:
    // the property being checked already holds, so a `waitFor` would succeed on its
    // first attempt and say nothing at all about what the late reply did.
    await new Promise((resolve) => setTimeout(resolve, 0))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(screen.getByTestId('result-summary').textContent).toContain('3')
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
  })

  it('pages with the envelope the server sent, not with a count of the rows on screen', async () => {
    const { fetch, urls } = server({
      listing: (url) =>
        envelope([seriesRow('s1'), seriesRow('s2')], {
          page: Number(parameters(url).get('page')),
          totalPages: 3,
          totalItems: 6,
        }),
    })
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(screen.getByTestId('search-page-next')).toBeEnabled())
    // Two rows on screen, six matches. `items.length` is the size of this page, and
    // showing it as the total tells a reader with six matches that there are two.
    expect(screen.getByTestId('result-summary').textContent).toContain('6')
    expect(screen.getByTestId('search-page-previous')).toBeDisabled()

    await fireEvent.click(screen.getByTestId('search-page-next'))

    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(2))
    expect(lastListing(urls, 'series').get('page')).toBe('1')
    // hasPrevious came back true for page 1, so the button follows the envelope.
    await waitFor(() => expect(screen.getByTestId('search-page-previous')).toBeEnabled())
  })

  it('says nothing matched instead of showing an empty grid', async () => {
    const { fetch } = server({ listing: envelope([]) })
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(screen.getByTestId('no-results')).toBeInTheDocument())
    expect(screen.getByTestId('result-summary').textContent).not.toBe('')
    // No paging controls for a result set with no pages to walk.
    expect(screen.queryByTestId('search-page-next')).toBeNull()
  })

  it('shows a failed search rather than an empty one', async () => {
    const { fetch } = server({ listingStatus: 500 })
    globalThis.fetch = fetch
    render(Search)

    // An error, not an empty state. A search that failed and a search that matched
    // nothing call for different reactions, and only one of them is worth retrying.
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(screen.queryByTestId('no-results')).toBeNull()
    expect(screen.queryByTestId('result-summary')).toBeNull()
  })

  it('retries a failed search from the notice', async () => {
    let status = 500
    const urls = []
    globalThis.fetch = vi.fn(async (url) => {
      urls.push(url)
      if (url.includes('/facets')) return reply([])
      if (url.includes('/libraries')) return reply(LIBRARIES)
      if (status !== 200) return reply({ code: 'internal_error', message: 'no' }, status)
      return reply(envelope([seriesRow('s1')]))
    })
    render(Search)

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    const before = listings(urls, 'series').length

    status = 200
    // The retry lives inside the alert. Reached through the alert rather than by name,
    // so the assertion does not depend on translated copy.
    await fireEvent.click(screen.getByRole('alert').querySelector('button'))

    await waitFor(() => expect(listings(urls, 'series').length).toBeGreaterThan(before))
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })

  it('builds the filter choices from the facets rather than from a list of its own', async () => {
    const { fetch, urls } = server()
    globalThis.fetch = fetch
    render(Search)

    // Every value offered came from `/facets`, one request per filter.
    await waitFor(() => expect(screen.getByTestId('filter-genre-gen-a')).toBeInTheDocument())
    const facetsAsked = urls
      .filter((url) => url.includes('/facets'))
      .map((url) => parameters(url).get('facet'))
      .sort()
    expect(facetsAsked).toEqual(['genre', 'language', 'publisher', 'seriesTag'])

    await fireEvent.click(screen.getByTestId('filter-genre-gen-a'))

    await waitFor(() => expect(lastListing(urls, 'series').getAll('genre')).toEqual(['gen-a']))
  })

  it('sends the seriesTag facet value as the tag parameter the listing accepts', async () => {
    const { fetch, urls } = server()
    globalThis.fetch = fetch
    render(Search)

    // The two names differ. `bookTag` is a facet with values and no series parameter,
    // so a tag group populated from it would offer values that match nothing.
    await waitFor(() => expect(screen.getByTestId('filter-tag-tag-a')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('filter-tag-tag-a'))

    await waitFor(() => expect(lastListing(urls, 'series').getAll('tag')).toEqual(['tag-a']))
  })

  it('offers no filter group for a facet with nothing in it', async () => {
    const urls = []
    globalThis.fetch = vi.fn(async (url) => {
      urls.push(url)
      if (url.includes('/facets')) return reply([])
      if (url.includes('/libraries')) return reply(LIBRARIES)
      return reply(envelope([seriesRow('s1')]))
    })
    render(Search)

    // An empty fieldset would claim a filter exists with nothing in it. It says so once
    // instead, and the failure case says something different — see the next test.
    await waitFor(() => expect(screen.getByTestId('no-filter-choices')).toBeInTheDocument())
    expect(screen.queryByTestId('filter-choices-failed')).toBeNull()
  })

  it('says the filter choices could not be read, instead of showing no filters', async () => {
    const { fetch, urls } = server({ facetStatus: 500 })
    globalThis.fetch = fetch
    render(Search)

    // Without this notice a dead facet is indistinguishable from an empty one: both
    // render as no group at all, and one of them is a fault nobody would report.
    await waitFor(() => expect(screen.getByTestId('filter-choices-failed')).toBeInTheDocument())
    // And the search itself still ran, so a broken facet does not cost the results.
    expect(listings(urls, 'series').length).toBeGreaterThan(0)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('says so when the library list could not be read either', async () => {
    const { fetch } = server({ libraryStatus: 500 })
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(screen.getByTestId('filter-choices-failed')).toBeInTheDocument())
    // The library filter is missing rather than empty, which is the same trap.
    expect(screen.queryByTestId('filter-library-lib-one')).toBeNull()
  })

  it('sends the chosen library once per identifier', async () => {
    const { fetch, urls } = server()
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(screen.getByTestId('filter-library-lib-one')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('filter-library-lib-one'))
    await waitFor(() => expect(lastListing(urls, 'series').getAll('libraryId')).toEqual(['lib-one']))

    await fireEvent.click(screen.getByTestId('filter-library-lib-two'))
    // Repeated, not comma-joined: one `libraryId=a,b` names a library that does not
    // exist and the listing answers an empty page rather than refusing.
    await waitFor(() =>
      expect(lastListing(urls, 'series').getAll('libraryId')).toEqual(['lib-one', 'lib-two']),
    )
  })

  it('returns to the first page when the filters change', async () => {
    const { fetch, urls } = server({
      listing: (url) =>
        envelope([seriesRow('s1')], {
          page: Number(parameters(url).get('page')),
          totalPages: 3,
          totalItems: 3,
        }),
    })
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(screen.getByTestId('search-page-next')).toBeEnabled())
    await fireEvent.click(screen.getByTestId('search-page-next'))
    await waitFor(() => expect(lastListing(urls, 'series').get('page')).toBe('1'))

    await fireEvent.click(screen.getByTestId('filter-genre-gen-a'))

    // Page 2 of a narrower result set is usually past its end, and the server answers
    // that with an empty page — which reads as "nothing matched" for a filter that
    // matches plenty.
    await waitFor(() => expect(lastListing(urls, 'series').get('page')).toBe('0'))
    expect(lastListing(urls, 'series').getAll('genre')).toEqual(['gen-a'])
  })

  it('sends the chosen sort as the field and direction the listing accepts', async () => {
    const { fetch, urls } = server()
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(1))
    expect(lastListing(urls, 'series').get('sort')).toBe('title,asc')

    await fireEvent.change(screen.getByTestId('sort-field'), {
      target: { value: 'mediaItemCount' },
    })
    await waitFor(() => expect(lastListing(urls, 'series').get('sort')).toBe('mediaItemCount,asc'))

    await fireEvent.change(screen.getByTestId('sort-direction'), { target: { value: 'desc' } })
    await waitFor(() =>
      expect(lastListing(urls, 'series').get('sort')).toBe('mediaItemCount,desc'),
    )
  })

  it('offers exactly the sorts the chosen scope accepts, each with a translated label', async () => {
    const { fetch, urls } = server()
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(1))
    const optionsFor = () =>
      [...screen.getByTestId('sort-field').options].map((option) => ({
        value: option.value,
        label: option.textContent.trim(),
      }))

    const seriesOptions = optionsFor()
    expect(seriesOptions.map((option) => option.value)).toEqual([...SCOPES.series.sorts])
    for (const option of seriesOptions) {
      // A missing catalog entry renders as the key path itself, which is a visible
      // label and a wrong one. The i18n suite cannot see these — the key is computed
      // from the scope and the field — so they are checked here.
      expect(option.label, `series sort ${option.value}`).not.toContain('search.sort')
      expect(option.label).not.toBe('')
    }

    await fireEvent.click(screen.getByTestId('scope-mediaItems'))
    await waitFor(() => expect(listings(urls, 'media-items')).toHaveLength(1))

    const itemOptions = optionsFor()
    expect(itemOptions.map((option) => option.value)).toEqual([...SCOPES.mediaItems.sorts])
    for (const option of itemOptions) {
      expect(option.label, `media-item sort ${option.value}`).not.toContain('search.sort')
      expect(option.label).not.toBe('')
    }
  })

  it('moves the sort to a field the new scope accepts when the scope changes', async () => {
    const { fetch, urls } = server({ listing: envelope([itemRow('m1')]) })
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(1))
    await fireEvent.change(screen.getByTestId('sort-field'), {
      target: { value: 'mediaItemCount' },
    })
    await waitFor(() => expect(lastListing(urls, 'series').get('sort')).toBe('mediaItemCount,asc'))

    await fireEvent.click(screen.getByTestId('scope-mediaItems'))

    // `mediaItemCount` is a series sort. Carried over, it would make the very first
    // media-item request `400 invalid_query`, so the field moves with the scope.
    await waitFor(() => expect(listings(urls, 'media-items')).toHaveLength(1))
    const [field] = lastListing(urls, 'media-items').get('sort').split(',')
    expect(SCOPES.mediaItems.sorts).toContain(field)
  })

  it('shows the media-item filters, and none of the ones that listing ignores', async () => {
    const { fetch, urls } = server({ listing: envelope([itemRow('m1')]) })
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(screen.getByTestId('filter-genre-gen-a')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('scope-mediaItems'))
    await waitFor(() => expect(listings(urls, 'media-items')).toHaveLength(1))

    // `/media-items` accepts `genre` and `oneShot` in the URL and applies neither, so
    // the controls are gone rather than present and inert.
    expect(screen.queryByTestId('filter-genre-gen-a')).toBeNull()
    expect(screen.queryByTestId('filter-one-shot')).toBeNull()
    expect(screen.getByTestId('filter-on-deck')).toBeInTheDocument()

    await fireEvent.click(screen.getByTestId('filter-on-deck'))
    await waitFor(() => expect(lastListing(urls, 'media-items').get('onDeck')).toBe('true'))
    expect(lastListing(urls, 'media-items').has('genre')).toBe(false)
  })

  it('warns that the two progress filters cannot both hold', async () => {
    const { fetch, urls } = server({ listing: envelope([]) })
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(listings(urls, 'series')).toHaveLength(1))
    await fireEvent.click(screen.getByTestId('scope-mediaItems'))
    await waitFor(() => expect(screen.getByTestId('filter-on-deck')).toBeInTheDocument())

    await fireEvent.click(screen.getByTestId('filter-on-deck'))
    expect(screen.queryByTestId('progress-conflict-hint')).toBeNull()

    await fireEvent.click(screen.getByTestId('filter-keep-reading'))

    // Both are still sent — the empty answer is the server's correct answer to a
    // contradictory question. What the reader cannot work out alone is that the
    // emptiness comes from the pair rather than from the library.
    await waitFor(() => expect(screen.getByTestId('progress-conflict-hint')).toBeInTheDocument())
    expect(lastListing(urls, 'media-items').get('onDeck')).toBe('true')
    expect(lastListing(urls, 'media-items').get('keepReading')).toBe('true')
  })

  it('clears the filters back to what the listing does with none of them', async () => {
    const { fetch, urls } = server()
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() => expect(screen.getByTestId('filter-genre-gen-a')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('filter-genre-gen-a'))
    await waitFor(() => expect(lastListing(urls, 'series').getAll('genre')).toEqual(['gen-a']))

    await fireEvent.click(screen.getByTestId('clear-filters'))

    await waitFor(() => expect(lastListing(urls, 'series').has('genre')).toBe(false))
    expect(screen.getByTestId('filter-genre-gen-a')).not.toBeChecked()
  })

  it('links a series result to its series screen and an item result to the reader', async () => {
    const { fetch, urls } = server({
      listing: (url) =>
        url.includes('/media-items?')
          ? envelope([itemRow('m1')])
          : envelope([seriesRow('s1')]),
    })
    globalThis.fetch = fetch
    render(Search)

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /Synthetic series s1/ })).toHaveAttribute(
        'href',
        '#/series/s1',
      ),
    )

    await fireEvent.click(screen.getByTestId('scope-mediaItems'))

    // The two collections link to different screens: a series opens its listing, an
    // item opens the reader. Handing an item id to `#/series/…` would look right and
    // answer 404 on lookup.
    await waitFor(() =>
      expect(screen.getByRole('link', { name: /Synthetic item m1/ })).toHaveAttribute(
        'href',
        '#/read/m1',
      ),
    )
    expect(listings(urls, 'media-items')).toHaveLength(1)
  })
})
