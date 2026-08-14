<script>
  /**
   * The scoped, filtered, sorted half of searching — everything except the text field.
   *
   * The field is deliberately not here. It belongs to whichever screen is hosting this, and the
   * screen that had its own field *and* navigated to a second screen with another one is the
   * defect this component exists to remove: a reader typing on the home screen pressed the
   * options control and the whole page was replaced by a different screen with a different
   * header, its own empty field and a back arrow. Nothing had been lost, but everything looked
   * lost, which is the same thing to whoever is looking. So the options open **where the reader
   * already is**, and the host passes down the words they have already typed.
   *
   * ## Two scopes rather than one merged list
   *
   * Series and media items are searched one at a time, chosen by the reader. It is tempting to
   * run both and show two sections, and it would be wrong here for three separate reasons:
   *
   * - The filters are not the same. `/media-items` accepts `publisher`, `genre`, `tag`,
   *   `language` and `oneShot` in the URL and applies none of them — verified against a
   *   running server, which answered with the whole catalog each time. One filter bar over
   *   both collections would silently apply to half the screen.
   * - The sorts are not the same either, and there the server refuses rather than ignores: a
   *   series field on the media-item listing is `400 invalid_query`.
   * - Each listing answers its own page envelope. Two envelopes cannot be driven by one pair of
   *   paging buttons without inventing a page number neither server sent.
   *
   * ## What a named feed cannot do
   *
   * There is no sort control on the home screen's shelves, and that is not an oversight: a
   * discovery feed rejects `sort` with `400 invalid_query` because its ordering is part of its
   * definition. Choosing an order is what the general listing is for, which is what this uses.
   */
  import { onMount } from 'svelte'
  import { ChevronDown } from '@lucide/svelte'
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
  import ActiveFilters from './ActiveFilters.svelte'
  import MediaItemFilters from './MediaItemFilters.svelte'
  import SearchResults from './SearchResults.svelte'
  import SearchSort from './SearchSort.svelte'
  import SeriesFilters from './SeriesFilters.svelte'

  let {
    /** The words the reader has typed, already debounced by the host. */
    query = '',
    /**
     * Whether the options themselves are showing.
     *
     * Bindable because both sides open it: the host's control, and submitting a search. The
     * results below are not behind it — narrowing is optional, and hiding the answers until
     * somebody expands a panel would make every search a two-step.
     */
    open = $bindable(false),
  } = $props()

  let scope = $state('series')
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
    query,
    page: pageIndex,
    size: DEFAULT_PAGE_SIZE,
    sort,
  })

  /**
   * How many filters are on, for the collapsed summary.
   *
   * Counted from `filters` rather than tracked alongside it, so it cannot drift from what the
   * request was built with. `oneShot` counts only when it is not `any`, because `any` is the
   * absence of that filter rather than a third choice.
   */
  const appliedCount = $derived(
    Object.entries(filters).reduce((total, [parameter, value]) => {
      if (Array.isArray(value)) return total + value.length
      if (parameter === 'oneShot') return total + (value !== 'any' ? 1 : 0)
      return total + (value ? 1 : 0)
    }, 0),
  )

  /** The filters that exist for a scope, at their neutral values. */
  function freshFilters(next) {
    return next === 'series'
      ? { libraryId: [], genre: [], tag: [], publisher: [], language: [], oneShot: 'any' }
      : { libraryId: [], onDeck: false, keepReading: false }
  }

  let sequence = 0
  let controller = null

  /**
   * Asks the server, and lets only the newest answer through.
   *
   * The criteria are passed in rather than read from state, so nothing this function assigns
   * can feed back into the effect that calls it.
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
      // An abort is this screen's own doing. Reporting it as a failed search would put an error
      // on screen every time somebody typed a second character.
      if (mine !== sequence || caught?.name === 'AbortError') return
      // Cleared as well as reported: leaving the previous page up under an error notice shows
      // matches for a query that is no longer the one being asked.
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
   * A new query is a new search, so it starts at its first page.
   *
   * Untracked from the request itself: this only has to reset paging when the words change, and
   * reading `criteria` here would make it run for a page turn as well - which would pin the
   * reader to page one and make the pager look broken.
   */
  let lastQuery
  $effect(() => {
    const current = query
    // The first run establishes the baseline rather than acting on it: the host may mount this
    // with words already typed, and treating that as a change would reset a page nobody turned.
    if (lastQuery === undefined || current === lastQuery) {
      lastQuery = current
      return
    }
    lastQuery = current
    pageIndex = 0
  })

  /**
   * Reads the library list and the facet-backed filter choices.
   *
   * Settled, so one failure does not cost the other. The failure is reported rather than
   * swallowed: a filter group with no choices renders as nothing at all, and "this library has
   * no genres" and "the genre list could not be read" look identical on screen unless the screen
   * says which it was.
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

    // A scan can add, change or remove exactly the things a result page is listing, so a stale
    // page is a real state rather than a cosmetic one.
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
    // `onDeck` and `keepReading` are read from this reader's progress, so progress moving
    // changes what those filters answer.
    const offProgress = eventHub.on(
      ['read-progress.changed', 'series-progress.changed'],
      () => run(scope, criteria),
    )
    const offResync = eventHub.onResync(() => run(scope, criteria))

    return () => {
      offCatalog()
      offProgress()
      offResync()
      controller?.abort()
    }
  })

  /**
   * Any change to what is being asked for returns to the first page.
   *
   * Page 3 of a narrower result set is usually past its end, and the server answers that with an
   * empty page — which reads as "nothing matched" for a filter that matches plenty.
   */
  function applyFilters(patch) {
    filters = { ...filters, ...patch }
    pageIndex = 0
  }

  function changeScope(next) {
    if (next === scope) return
    scope = next
    // The sort has to move with the scope: the collections accept different fields, and a stale
    // one would make the very next request `400 invalid_query`.
    sort = { ...SCOPES[next].defaultSort }
    // The library choice survives, because it means the same thing on both listings. Nothing
    // else does — the other scope's filters do not exist here.
    filters = { ...freshFilters(next), libraryId: filters.libraryId }
    pageIndex = 0
  }

  function changeSort(next) {
    sort = next
    pageIndex = 0
  }

  function clearFilters() {
    filters = freshFilters(scope)
    pageIndex = 0
  }
</script>

<fieldset class="scope">
  <legend>{$_('search.scope.legend')}</legend>
  <div class="segments">
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
  </div>
</fieldset>

<!-- A disclosure directly under the query, at every width, rather than a column beside the
     results. The facets enumerate whatever the library contains, so these controls are tall
     wherever they are put; given a column of their own they are tall and permanent, and the
     results lose the width for the whole session rather than for the moment somebody is
     choosing. Collapsed this costs one row, and open it is the full width the facet lists
     want. -->
<section class="controls" data-testid="search-controls">
  <button
    class="disclosure"
    type="button"
    data-testid="toggle-filters"
    aria-expanded={open}
    aria-controls="search-filters"
    onclick={() => (open = !open)}
  >
    <ChevronDown class="chevron" size={18} aria-hidden="true" />
    <span>{$_('search.filters.show')}</span>
    {#if appliedCount > 0}
      <span class="count" data-testid="applied-count">
        {$_('search.filters.appliedCount', { values: { count: appliedCount } })}
      </span>
    {/if}
  </button>

  <div id="search-filters" data-testid="search-filters" class="panel" hidden={!open}>
    {#if filtersIncomplete}
      <!-- Its own notice rather than the shared error one: the search itself may have answered
           perfectly, and reporting the whole screen as broken would be as wrong as reporting
           none of it. -->
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
  </div>
</section>

<section class="results">
  <ActiveFilters criteria={filters} {libraries} onremove={applyFilters} />

  <ErrorNotice {error} onretry={() => run(scope, criteria)} />

  {#if loading}
    <p class="waiting" role="status" data-testid="searching">{$_('search.searching')}</p>
  {/if}

  <SearchResults {scope} page={results} busy={loading} onpage={(next) => (pageIndex = next)} />
</section>

<style>
  /*
   * A segmented control rather than a bordered card holding two radios. Two mutually exclusive
   * choices with three-character labels filled a full-width panel and a touch-target-height row
   * each, which is a lot of screen for the smallest decision here - and it sat between the query
   * and the results, so it pushed the answers down for its whole height.
   *
   * Still two `<input type="radio">` in a `<fieldset>`, visually hidden. Arrow-key navigation,
   * the one-of-many announcement and the group's name all come from that, and `:checked` styles
   * the segment without any state mirrored in script.
   */
  .scope {
    display: flex;
    padding: 0;
    border: 0;
    margin: 0 0 var(--space-3);
  }
  .scope legend {
    /* Named for a screen reader, not drawn: the two segments say what the choice is. */
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .segments {
    display: inline-flex;
    padding: 2px;
    border: 1px solid var(--line);
    border-radius: var(--radius-pill);
    background: var(--surface-inset);
  }
  .segments label {
    display: inline-flex;
    min-height: 2rem;
    align-items: center;
    padding: 0 var(--space-4);
    border-radius: var(--radius-pill);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    line-height: 1;
    cursor: pointer;
  }
  .segments input {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    border: 0;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .segments label:has(input:checked) {
    background: var(--surface-raised);
    color: var(--text);
    font-weight: 600;
  }
  .segments label:has(input:focus-visible) {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .controls {
    padding: var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    margin: 0 0 var(--space-4);
    background: var(--surface-inset);
  }
  .panel {
    display: grid;
    gap: var(--space-3);
    margin-top: var(--space-3);
  }
  .disclosure {
    display: flex;
    width: 100%;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    padding: 0;
    border: 0;
    background: none;
    color: var(--text);
    font: inherit;
    font-size: var(--font-md);
    cursor: pointer;
  }
  /* Rotated from the closed state rather than swapped for a second icon, so open and closed
     cannot drift apart and the movement says which way it went. */
  .disclosure :global(.chevron) {
    color: var(--text-muted);
    transition: transform 120ms ease;
  }
  .disclosure[aria-expanded='true'] :global(.chevron) {
    transform: rotate(180deg);
  }
  @media (prefers-reduced-motion: reduce) {
    .disclosure :global(.chevron) {
      transition: none;
    }
  }
  .count {
    color: var(--accent-text);
    font-size: var(--font-xs);
  }
  /*
   * Open, the panel is as wide as the screen, so on a wide one the facet groups sit side by side
   * instead of in a single tall stack the reader has to scroll past to reach the results. The
   * track floor is what the longest facet value needs to stay on one line; `auto-fit` collapses
   * to one column on a narrow viewport without a second breakpoint being written down.
   */
  @media (min-width: 48rem) {
    .panel {
      grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
      align-items: start;
    }
    /* Neither is a filter group: one reports a failure about all of them, the other acts on all
       of them, and a column each would read as one more thing to choose. */
    .incomplete,
    .clear {
      grid-column: 1 / -1;
    }
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
  .waiting {
    margin: 0 0 var(--space-2);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
</style>
