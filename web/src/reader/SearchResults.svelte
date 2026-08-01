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
  import { ChevronLeft, ChevronRight } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import { artworkUrl } from '../lib/api/catalog.js'
  import Cover from '../components/Cover.svelte'

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
  // One-based on screen. `page` is zero-based on the wire, and "page 0 of 5" is a
  // leaked implementation detail.
  const humanPage = $derived((page?.page ?? 0) + 1)
  const totalPages = $derived(Math.max(1, page?.totalPages ?? 1))
  const kind = $derived(scope === 'series' ? 'series' : 'mediaItem')

  const href = (item) => (scope === 'series' ? `#/series/${item.id}` : `#/read/${item.id}`)
</script>

{#if page}
  <!-- polite: the count follows a search the reader started, so it is worth
       announcing, but not worth interrupting whatever is being read out. -->
  <p class="summary" role="status" aria-live="polite" data-testid="result-summary">
    {$_('search.results.summary', { values: { total: page.totalItems } })}
  </p>

  {#if items.length === 0}
    <p class="empty" data-testid="no-results">{$_('search.results.noResults')}</p>
  {:else}
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

    <nav class="paging" aria-label={$_('search.title')}>
      <p class="position">
        {$_('search.paging.summary', { values: { page: humanPage, pages: totalPages } })}
      </p>
      <div class="buttons">
        <button
          type="button"
          data-testid="search-page-previous"
          disabled={!page.hasPrevious || busy}
          aria-label={$_('search.paging.previous')}
          onclick={() => onpage(page.page - 1)}
        >
          <ChevronLeft size={18} aria-hidden="true" />
        </button>
        <button
          type="button"
          data-testid="search-page-next"
          disabled={!page.hasNext || busy}
          aria-label={$_('search.paging.next')}
          onclick={() => onpage(page.page + 1)}
        >
          <ChevronRight size={18} aria-hidden="true" />
        </button>
      </div>
    </nav>
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
  .paging {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    margin-top: var(--space-4);
  }
  .position {
    margin: 0;
    color: var(--text-muted);
    font-size: var(--font-sm);
    font-variant-numeric: tabular-nums;
  }
  .buttons {
    display: flex;
    gap: var(--space-2);
  }
  .buttons button {
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    place-items: center;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--text);
    cursor: pointer;
  }
  .buttons button:disabled {
    opacity: 0.4;
    cursor: default;
  }
</style>
