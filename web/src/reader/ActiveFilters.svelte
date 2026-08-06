<script>
  /**
   * What the search is currently narrowed by, next to the results it narrowed.
   *
   * A filter that is applied but off screen is worse than no filter at all: the results are
   * genuinely wrong for the query the reader remembers typing, and nothing visible explains
   * why. That is the state the screen was in whenever the controls were scrolled past or
   * collapsed - which, once the facet groups became collapsible, is most of the time.
   *
   * So this restates the criteria as chips beside the answer rather than inside the form.
   * Each chip removes exactly its own value, because the alternative on offer was "clear
   * all", and a reader who wants four of five filters should not have to rebuild four.
   *
   * The chips read from `criteria` rather than keeping their own copy, so there is one
   * answer to what is applied and it is the one the request was built from.
   */
  import { X } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'

  let {
    /** The active criteria, exactly as the search sends them. */
    criteria,
    /** Libraries visible to this reader, used to name a library rather than show its id. */
    libraries = [],
    /** Called with a partial criteria change that drops one value. */
    onremove,
  } = $props()

  /** Criteria that hold a list of chosen values; each value becomes its own chip. */
  const LIST_PARAMETERS = ['libraryId', 'genre', 'tag', 'publisher', 'language']

  /** Criteria that are on or off; present as a chip only when on. */
  const FLAG_PARAMETERS = ['onDeck', 'keepReading']

  // An id is what the request carries and a name is what the reader chose, so the chip shows
  // the name and falls back to the id only when the library list could not be read.
  const libraryNames = $derived(
    new Map((libraries ?? []).map((library) => [library.id, library.name])),
  )

  const applied = $derived([
    ...LIST_PARAMETERS.flatMap((parameter) =>
      (criteria?.[parameter] ?? []).map((value) => ({
        id: `${parameter}-${value}`,
        label: parameter === 'libraryId' ? (libraryNames.get(value) ?? value) : value,
        patch: {
          [parameter]: (criteria[parameter] ?? []).filter((each) => each !== value),
        },
      })),
    ),
    ...FLAG_PARAMETERS.filter((parameter) => criteria?.[parameter]).map((parameter) => ({
      id: parameter,
      label: $_(`search.filters.${parameter}`),
      patch: { [parameter]: false },
    })),
    // `any` is the absence of this filter rather than a third choice, so it is not a chip.
    ...(criteria?.oneShot && criteria.oneShot !== 'any'
      ? [
          {
            id: `one-shot-${criteria.oneShot}`,
            label: $_(`search.filters.oneShotChoices.${criteria.oneShot}`),
            patch: { oneShot: 'any' },
          },
        ]
      : []),
  ])
</script>

{#if applied.length > 0}
  <ul class="applied" data-testid="active-filters" aria-label={$_('search.filters.active')}>
    {#each applied as filter (filter.id)}
      <li>
        <button
          type="button"
          data-testid={`active-filter-${filter.id}`}
          aria-label={$_('search.filters.remove', { values: { value: filter.label } })}
          onclick={() => onremove(filter.patch)}
        >
          <span>{filter.label}</span>
          <X size={14} aria-hidden="true" />
        </button>
      </li>
    {/each}
  </ul>
{/if}

<style>
  .applied {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1) var(--space-2);
    margin: 0 0 var(--space-3);
    padding: 0;
    list-style: none;
  }
  button {
    display: inline-flex;
    align-items: center;
    /* The 44px touch floor is the row's, not each chip's: chips are read as a group, and
       applying it per chip makes a wrapped row as tall as the column this replaced. */
    gap: var(--space-1);
    min-height: 2rem;
    padding: 0 var(--space-2);
    border: 1px solid var(--accent);
    border-radius: var(--radius-pill);
    background: var(--accent);
    color: var(--accent-contrast);
    font-size: var(--font-sm);
    line-height: 1;
    cursor: pointer;
  }
  button:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
</style>
