<script>
  /**
   * The filters the **series** listing accepts, and only those.
   *
   * Every choice list here comes from `/facets` rather than from a list written down
   * in this file. A hardcoded genre list is wrong for every library but the one it was
   * copied from, and it fails in the direction nobody checks: it offers values that
   * match nothing, so the screen looks like it works and the catalog looks empty.
   *
   * A facet with no values renders no group at all. The alternative — an empty
   * fieldset — states that a filter exists and has nothing in it, which is only half
   * the reason a group can be empty; the other half is that the facet could not be
   * read, and the screen says that separately so the two are never confused.
   *
   * Four of the eight facets the server enumerates are deliberately absent:
   * `bookTag`, `ageRating`, `sharingLabel` and `releaseYear` have no series filter
   * parameter, so a control for one would change the request and not the results. See
   * `UNFILTERABLE_FACETS` in `lib/api/catalogSearch.js`.
   */
  import { _ } from '../lib/i18n.js'
  import { ONE_SHOT_CHOICES, SERIES_FACET_FILTERS } from '../lib/api/catalogSearch.js'
  import FacetGroup from './FacetGroup.svelte'
  import LibraryFilter from './LibraryFilter.svelte'

  let {
    /** Libraries visible to this reader. */
    libraries,
    /** The active criteria: `libraryId`, the four facet filters, and `oneShot`. */
    criteria,
    /** Facet values keyed by listing parameter, as `readSeriesFilterChoices` returns. */
    choices,
    /** Called with a partial criteria change to merge. */
    onchange,
  } = $props()

  /** The facet-backed filters that actually have something to offer. */
  const populated = $derived(
    SERIES_FACET_FILTERS.filter(({ parameter }) => (choices[parameter] ?? []).length > 0),
  )

  function toggle(parameter, value, checked) {
    const current = criteria[parameter] ?? []
    onchange({
      [parameter]: checked ? [...current, value] : current.filter((each) => each !== value),
    })
  }
</script>

<LibraryFilter {libraries} selected={criteria.libraryId} onchange={(libraryId) => onchange({ libraryId })} />

{#each populated as filter (filter.parameter)}
  <FacetGroup
    parameter={filter.parameter}
    label={$_(`search.filters.${filter.parameter}`)}
    values={choices[filter.parameter]}
    selected={criteria[filter.parameter] ?? []}
    ontoggle={(value, checked) => toggle(filter.parameter, value, checked)}
  />
{/each}

{#if populated.length === 0}
  <p class="hint" data-testid="no-filter-choices">{$_('search.filters.noChoices')}</p>
{/if}

<p class="one-shot">
  <label for="search-one-shot">{$_('search.filters.oneShot')}</label>
  <select
    id="search-one-shot"
    data-testid="filter-one-shot"
    value={criteria.oneShot}
    onchange={(event) => onchange({ oneShot: event.currentTarget.value })}
  >
    {#each ONE_SHOT_CHOICES as choice (choice)}
      <option value={choice}>{$_(`search.filters.oneShotChoices.${choice}`)}</option>
    {/each}
  </select>
</p>

<style>
  .hint {
    margin: 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .one-shot {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    margin: 0;
  }
  .one-shot label {
    display: block;
    min-height: 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  select {
    min-height: var(--touch-target);
    padding: 0 var(--space-2);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--text);
    font: inherit;
    /* 16px floor: iOS Safari zooms the viewport on focus below it, and the
       resulting layout jump cannot be undone in CSS. */
    font-size: var(--font-input);
  }
</style>
