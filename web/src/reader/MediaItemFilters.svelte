<script>
  /**
   * The filters the **media-item** listing accepts, and only those.
   *
   * A much shorter list than the series one, and the difference is not an omission
   * here: `BookCatalogQuery` reads `libraryId`, `seriesId`, `query`, `onDeck` and
   * `keepReading` and nothing else. `publisher`, `genre`, `tag`, `language` and
   * `oneShot` are accepted by the route, ignored by the query, and answered with the
   * whole catalog — verified against a running server. Offering them here would put
   * five controls on screen that move nothing.
   *
   * `seriesId` is also absent, but for the opposite reason: it is supported, and the
   * series screen is where a reader already narrows to one series. A second way in
   * would need a series picker to be useful, which is the search surface again.
   */
  import { _ } from '../lib/i18n.js'
  import LibraryFilter from './LibraryFilter.svelte'

  let {
    /** Libraries visible to this reader. */
    libraries,
    /** The active criteria: `libraryId`, `onDeck`, `keepReading`. */
    criteria,
    /** Called with a partial criteria change to merge. */
    onchange,
  } = $props()
</script>

<LibraryFilter {libraries} selected={criteria.libraryId} onchange={(libraryId) => onchange({ libraryId })} />

<fieldset>
  <legend>{$_('search.filters.progress')}</legend>
  <label>
    <input
      type="checkbox"
      data-testid="filter-on-deck"
      checked={criteria.onDeck}
      onchange={(event) => onchange({ onDeck: event.currentTarget.checked })}
    />
    <span>{$_('search.filters.onDeck')}</span>
  </label>
  <label>
    <input
      type="checkbox"
      data-testid="filter-keep-reading"
      checked={criteria.keepReading}
      onchange={(event) => onchange({ keepReading: event.currentTarget.checked })}
    />
    <span>{$_('search.filters.keepReading')}</span>
  </label>
  {#if criteria.onDeck && criteria.keepReading}
    <!-- Said rather than prevented. The server answers the contradiction with an
         empty page, which is the correct answer; what a reader cannot work out on
         their own is that the emptiness comes from the pair and not from the
         library. Disabling one box instead would decide for them which they meant. -->
    <p class="hint" role="status" data-testid="progress-conflict-hint">
      {$_('search.filters.progressHint')}
    </p>
  {/if}
</fieldset>

<style>
  fieldset {
    margin: 0;
    padding: 0;
    border: 0;
  }
  legend {
    padding: 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  label {
    display: flex;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    font-size: var(--font-sm);
  }
  .hint {
    margin: 0;
    color: var(--warning);
    font-size: var(--font-xs);
  }
</style>
