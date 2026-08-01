<script>
  /**
   * The reader's search and filter surface.
   *
   * ## Two scopes rather than one merged list
   *
   * Series and media items are searched one at a time, chosen by the reader. It is
   * tempting to run both and show two sections, and it would be wrong here for three
   * separate reasons:
   *
   * - The filters are not the same. `/media-items` accepts `publisher`, `genre`, `tag`,
   *   `language` and `oneShot` in the URL and applies none of them — verified against a
   *   running server, which answered with the whole catalog each time. One filter bar
   *   over both collections would silently apply to half the screen.
   * - The sorts are not the same either, and there the server refuses rather than
   *   ignores: a series field on the media-item listing is `400 invalid_query`.
   * - Each listing answers its own page envelope. Two envelopes cannot be driven by one
   *   pair of paging buttons without inventing a page number neither server sent.
   *
   * ## Typing does not mean asking
   *
   * The query is debounced, and a request that is superseded is abandoned rather than
   * allowed to land. Both guards are needed and they cover different failures: the
   * debounce stops a request per keystroke, and the sequence token stops a slow reply
   * for "syn" from overwriting the reply for "synthetic" that arrived first. Aborting
   * alone would leave the in-flight flag to whichever promise settled last.
   *
   * ## What a named feed cannot do
   *
   * There is no sort control on the home screen's shelves, and that is not an
   * oversight here: a discovery feed rejects `sort` with `400 invalid_query` because
   * its ordering is part of its definition. Choosing an order is what the general
   * listing is for, which is what this screen uses.
   */
  import { onMount } from 'svelte'
  import { ArrowLeft, Search as SearchIcon } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import { listLibraries } from '../lib/api/libraries.js'
  import {
    DEFAULT_PAGE_SIZE,
    SCOPES,
    SCOPE_NAMES,
    readSeriesFilterChoices,
    searchCatalog,
  } from '../lib/api/catalogSearch.js'
  import { eventHub } from '../lib/eventHub.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import MediaItemFilters from './MediaItemFilters.svelte'
  import SearchResults from './SearchResults.svelte'
  import SearchSort from './SearchSort.svelte'
  import SeriesFilters from './SeriesFilters.svelte'

  /** Long enough that ordinary typing produces one request, short enough to feel live. */
  const DEBOUNCE_MILLIS = 250

  let scope = $state('series')
  /** What is in the input. */
  let text = $state('')
  /** What has actually been asked for. Lags {@link text} by the debounce. */
  let submitted = $state('')
  let pageIndex = $state(0)
  let sort = $state({ ...SCOPES.series.defaultSort })
  let filters = $state(freshFilters('series'))

  let results = $state(null)
  let error = $state(null)
  let loading = $state(false)
  let libraries = $state([])
  let choices = $state({})
  /** A filter group is missing because a choice list could not be read, not empty. */
  let filtersIncomplete = $state(false)

  const criteria = $derived({
    ...filters,
    query: submitted,
    page: pageIndex,
    size: DEFAULT_PAGE_SIZE,
    sort,
  })

  /** The filters that exist for a scope, at their neutral values. */
  function freshFilters(next) {
    return next === 'series'
      ? { libraryId: [], genre: [], tag: [], publisher: [], language: [], oneShot: 'any' }
      : { libraryId: [], onDeck: false, keepReading: false }
  }

  let sequence = 0
  let controller = null
  let debounce = null

  /**
   * Asks the server, and lets only the newest answer through.
   *
   * The criteria are passed in rather than read from state, so nothing this function
   * assigns can feed back into the effect that calls it.
   */
  async function run(currentScope, currentCriteria) {
    const mine = ++sequence
    controller?.abort()
    controller = new AbortController()
    loading = true
    try {
      const answer = await searchCatalog(currentScope, currentCriteria, {
        signal: controller.signal,
      })
      if (mine !== sequence) return
      results = answer
      error = null
    } catch (caught) {
      // An abort is this screen's own doing. Reporting it as a failed search would
      // put an error on screen every time somebody typed a second character.
      if (mine !== sequence || caught?.name === 'AbortError') return
      // Cleared as well as reported: leaving the previous page up under an error
      // notice shows matches for a query that is no longer the one being asked.
      results = null
      error = caught
    } finally {
      if (mine === sequence) loading = false
    }
  }

  $effect(() => {
    // Reads both so every change to either re-runs the search, including a page turn.
    const requestedScope = scope
    const requestedCriteria = criteria
    run(requestedScope, requestedCriteria)
  })

  /**
   * Reads the library list and the facet-backed filter choices.
   *
   * Settled, so one failure does not cost the other. The failure is reported rather
   * than swallowed: a filter group with no choices renders as nothing at all, and
   * "this library has no genres" and "the genre list could not be read" look identical
   * on screen unless the screen says which it was.
   */
  async function loadFilterChoices() {
    const [libraryResult, facetResult] = await Promise.allSettled([
      listLibraries(),
      readSeriesFilterChoices(),
    ])
    if (libraryResult.status === 'fulfilled') libraries = libraryResult.value ?? []
    if (facetResult.status === 'fulfilled') choices = facetResult.value.choices
    filtersIncomplete =
      libraryResult.status === 'rejected' ||
      facetResult.status === 'rejected' ||
      (facetResult.status === 'fulfilled' && facetResult.value.failed.length > 0)
  }

  onMount(() => {
    loadFilterChoices()

    // A scan can add, change or remove exactly the things a result page is listing, so
    // a stale page is a real state rather than a cosmetic one.
    const offCatalog = eventHub.on(
      [
        'series.added',
        'series.changed',
        'series.removed',
        'media-item.added',
        'media-item.changed',
        'media-item.removed',
      ],
      () => run(scope, criteria),
    )
    // `onDeck` and `keepReading` are read from this reader's progress, so progress
    // moving changes what those filters answer.
    const offProgress = eventHub.on(
      ['read-progress.changed', 'series-progress.changed'],
      () => run(scope, criteria),
    )
    const offResync = eventHub.onResync(() => run(scope, criteria))

    return () => {
      offCatalog()
      offProgress()
      offResync()
      clearTimeout(debounce)
      controller?.abort()
    }
  })

  /**
   * Any change to what is being asked for returns to the first page.
   *
   * Page 3 of a narrower result set is usually past its end, and the server answers
   * that with an empty page — which reads as "nothing matched" for a filter that
   * matches plenty.
   */
  function applyFilters(patch) {
    filters = { ...filters, ...patch }
    pageIndex = 0
  }

  function changeScope(next) {
    if (next === scope) return
    scope = next
    // The sort has to move with the scope: the collections accept different fields,
    // and a stale one would make the very next request `400 invalid_query`.
    sort = { ...SCOPES[next].defaultSort }
    // The library choice survives, because it means the same thing on both listings.
    // Nothing else does — the other scope's filters do not exist here.
    filters = { ...freshFilters(next), libraryId: filters.libraryId }
    pageIndex = 0
  }

  function changeSort(next) {
    sort = next
    pageIndex = 0
  }

  function typeQuery(value) {
    text = value
    clearTimeout(debounce)
    debounce = setTimeout(() => {
      submitted = text
      pageIndex = 0
    }, DEBOUNCE_MILLIS)
  }

  /**
   * Submitting flushes the debounce rather than forcing a second request.
   *
   * If the pending text is already what was asked, there is nothing new to ask, and
   * re-sending it would spend a request to redraw the same page. Re-reading after a
   * failure is the retry on the error notice, which asks unconditionally.
   */
  function submit(event) {
    event.preventDefault()
    clearTimeout(debounce)
    submitted = text
    pageIndex = 0
  }

  function clearFilters() {
    filters = freshFilters(scope)
    pageIndex = 0
  }
</script>

<header>
  <a class="icon" href="#/" aria-label={$_('common.back')}>
    <ArrowLeft size={18} aria-hidden="true" />
  </a>
  <h1>{$_('search.title')}</h1>
</header>

<form class="query" role="search" onsubmit={submit}>
  <label for="search-query">{$_('search.queryLabel')}</label>
  <div class="row">
    <input
      id="search-query"
      data-testid="search-query"
      type="search"
      autocomplete="off"
      value={text}
      oninput={(event) => typeQuery(event.currentTarget.value)}
    />
    <button class="primary" type="submit" data-testid="search-submit">
      <SearchIcon size={18} aria-hidden="true" />
      <span>{$_('search.submit')}</span>
    </button>
  </div>
</form>

<fieldset class="scope">
  <legend>{$_('search.scope.legend')}</legend>
  {#each SCOPE_NAMES as name (name)}
    <label>
      <input
        type="radio"
        name="search-scope"
        data-testid={`scope-${name}`}
        value={name}
        checked={scope === name}
        onchange={() => changeScope(name)}
      />
      <span>{$_(`search.scope.${name}`)}</span>
    </label>
  {/each}
</fieldset>

<section class="controls">
  <h2>{$_('search.filters.legend')}</h2>

  {#if filtersIncomplete}
    <!-- Its own notice rather than the shared error one: the search itself may have
         answered perfectly, and reporting the whole screen as broken would be as
         wrong as reporting none of it. -->
    <p class="incomplete" role="status" data-testid="filter-choices-failed">
      {$_('search.filters.choicesFailed')}
      <button type="button" onclick={() => loadFilterChoices()}>{$_('common.retry')}</button>
    </p>
  {/if}

  {#if scope === 'series'}
    <SeriesFilters {libraries} criteria={filters} {choices} onchange={applyFilters} />
  {:else}
    <MediaItemFilters {libraries} criteria={filters} onchange={applyFilters} />
  {/if}

  <SearchSort {scope} {sort} onchange={changeSort} />

  <button class="clear" type="button" data-testid="clear-filters" onclick={clearFilters}>
    {$_('search.filters.clear')}
  </button>
</section>

<section class="results">
  <ErrorNotice {error} onretry={() => run(scope, criteria)} />

  {#if loading}
    <p class="waiting" role="status" data-testid="searching">{$_('search.searching')}</p>
  {/if}

  <SearchResults
    {scope}
    page={results}
    busy={loading}
    onpage={(next) => (pageIndex = next)}
  />
</section>

<style>
  header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: max(var(--space-4), calc(var(--inset-top) + var(--space-2)))
      var(--gutter-right) var(--space-3) var(--gutter-left);
  }
  h1 {
    margin: 0;
    font-size: var(--font-lg);
  }
  .icon {
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    place-items: center;
    border-radius: var(--radius-sm);
    color: var(--text);
  }
  .query {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    padding: 0 var(--gutter-right) var(--space-4) var(--gutter-left);
  }
  .query label {
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .row {
    display: flex;
    gap: var(--space-2);
  }
  input[type='search'] {
    flex: 1 1 auto;
    min-width: 0;
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-raised);
    color: var(--text);
    font: inherit;
    /* 16px floor: iOS Safari zooms the viewport on focus below it, and the
       resulting layout jump cannot be undone in CSS. */
    font-size: var(--font-input);
  }
  .primary {
    display: flex;
    flex: 0 0 auto;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--accent);
    color: var(--accent-contrast);
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }
  .scope,
  .controls {
    margin: 0 var(--gutter-right) var(--space-4) var(--gutter-left);
    padding: var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-inset);
  }
  .scope {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-4);
  }
  .scope legend {
    padding: 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .scope label {
    display: flex;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    font-size: var(--font-sm);
  }
  .controls {
    display: grid;
    gap: var(--space-3);
  }
  .controls h2 {
    margin: 0;
    font-size: var(--font-md);
  }
  .incomplete {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    margin: 0;
    padding: var(--space-3);
    border: 1px solid var(--warning);
    border-radius: var(--radius);
    font-size: var(--font-sm);
  }
  .incomplete button,
  .clear {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--text);
    font: inherit;
    font-size: var(--font-sm);
    cursor: pointer;
  }
  .clear {
    justify-self: start;
  }
  .results {
    padding: 0 var(--gutter-right) max(var(--space-5), var(--inset-bottom))
      var(--gutter-left);
  }
  .waiting {
    margin: 0 0 var(--space-2);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
</style>
