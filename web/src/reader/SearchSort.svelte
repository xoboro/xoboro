<script>
  /**
   * Chooses the order of the results.
   *
   * The fields offered are read from `SCOPES[scope].sorts`, which is the list the
   * server accepts for that collection — the two collections do not share one. Sending
   * a field the other collection owns is `400 invalid_query`, so a single merged list
   * would make roughly half the menu a failed search.
   *
   * There is deliberately no sort control on the home screen's shelves. A named
   * discovery feed rejects `sort` rather than ignoring it, because the ordering is part
   * of what the feed is; this control belongs to the general listing, which is what
   * search uses.
   */
  import { _ } from '../lib/i18n.js'
  import { SCOPES } from '../lib/api/catalogSearch.js'

  let {
    /** `'series'` or `'mediaItems'`; decides which fields are on offer. */
    scope,
    /** `{ field, direction }`. */
    sort,
    /** Called with the new `{ field, direction }`. */
    onchange,
  } = $props()

  const fields = $derived(SCOPES[scope].sorts)
</script>

<div class="sort">
  <p>
    <label for="search-sort-field">{$_('search.sort.field')}</label>
    <select
      id="search-sort-field"
      data-testid="sort-field"
      value={sort.field}
      onchange={(event) => onchange({ field: event.currentTarget.value, direction: sort.direction })}
    >
      {#each fields as field (field)}
        <option value={field}>{$_(`search.sort.${scope}.${field}`)}</option>
      {/each}
    </select>
  </p>
  <p>
    <label for="search-sort-direction">{$_('search.sort.direction')}</label>
    <select
      id="search-sort-direction"
      data-testid="sort-direction"
      value={sort.direction}
      onchange={(event) => onchange({ field: sort.field, direction: event.currentTarget.value })}
    >
      <option value="asc">{$_('search.sort.asc')}</option>
      <option value="desc">{$_('search.sort.desc')}</option>
    </select>
  </p>
</div>

<style>
  .sort {
    display: grid;
    gap: var(--space-3);
    grid-template-columns: repeat(auto-fit, minmax(min(160px, 100%), 1fr));
  }
  p {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    margin: 0;
  }
  label {
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
    /* 16px floor: iOS Safari zooms the viewport on focus below it. */
    font-size: var(--font-input);
  }
</style>
