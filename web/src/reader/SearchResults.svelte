<script>
  /**
   * One page of search results, and where the reader is in the whole set.
   *
   * Every number shown comes from the page envelope — `totalItems`, `page`,
   * `totalPages`, `hasPrevious`, `hasNext`. None of it is counted from the rows on
   * screen: `items.length` is the size of this page, and presenting it as the number
   * of matches would tell a reader with 200 matches that there are 24.
   *
   * Paging asks the server for the next page rather than slicing a full fetch. The
   * measured cost is not symmetric — on a 15,000-item catalog a series listing is
   * about 3.8 ms and a media-item listing roughly 25 ms — so "fetch it all and page in
   * the browser" is most expensive exactly where a reader would notice.
   */
  import { _ } from '../lib/i18n.js'
  import { artworkUrl } from '../lib/api/catalog.js'
  import Cover from '../components/Cover.svelte'
  import Pager from '../components/Pager.svelte'

  let {
    /** `'series'` or `'mediaItems'`; decides the artwork route and the link target. */
    scope,
    /** The native page envelope, or `null` before the first answer. */
    page,
    /** Called with the zero-based page to load. */
    onpage,
    /** True while a request is outstanding, which disables both paging buttons. */
    busy = false,
  } = $props()

  const items = $derived(page?.items ?? [])
  const kind = $derived(scope === 'series' ? 'series' : 'mediaItem')

  const href = (item) => (scope === 'series' ? `#/series/${item.id}` : `#/read/${item.id}`)
</script>

{#if page}
  {#if items.length === 0}
    <!-- One line, not two. The count for zero reads "nothing found", and the guidance below it
         opened with the same sentence again - the screen said the same thing twice and only the
         second half of it was any use. This carries the announcement as well, so a reader who
         cannot see the change is still told there was one. -->
    <p class="empty" role="status" aria-live="polite" data-testid="no-results">
      {$_('search.results.noResults')}
    </p>
  {:else}
    <!-- polite: the count follows a search the reader started, so it is worth
         announcing, but not worth interrupting whatever is being read out. -->
    <p class="summary" role="status" aria-live="polite" data-testid="result-summary">
      {$_('search.results.summary', { values: { total: page.totalItems } })}
    </p>
    <ul class="grid">
      {#each items as item (item.id)}
        <li>
          <a href={href(item)}>
            <Cover src={artworkUrl(kind, item.id)} />
            <span class="label">{item.title ?? item.name ?? item.id}</span>
            {#if scope === 'series'}
              <span class="sub">
                {$_('reader.items', { values: { count: item.mediaItemCount ?? 0 } })}
              </span>
            {:else if item.seriesTitle}
              <span class="sub">{item.seriesTitle}</span>
            {/if}
          </a>
        </li>
      {/each}
    </ul>

    <Pager
      {page}
      {busy}
      {onpage}
      label={$_('search.title')}
      testIdPrefix="search-page"
      summaryKey="search.paging.summary"
    />
  {/if}
{/if}

<style>
  .summary {
    margin: 0 0 var(--space-2);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-variant-numeric: tabular-nums;
  }
  .empty {
    margin: 0;
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  .grid {
    display: grid;
    margin: 0;
    padding: 0;
    gap: var(--space-3);
    grid-template-columns: repeat(auto-fill, minmax(min(120px, 100%), 1fr));
    list-style: none;
  }
  .label {
    display: block;
    overflow: hidden;
    padding-top: var(--space-1);
    font-size: var(--font-sm);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .sub {
    display: block;
    overflow: hidden;
    color: var(--text-muted);
    font-size: var(--font-xs);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
