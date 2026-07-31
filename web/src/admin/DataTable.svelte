<script>
  /**
   * The console's one list pattern.
   *
   * Pages on the **server**. A screen that fetched everything and paged in the
   * browser would work on a demo library and fall over on a real one, and the
   * measured cost is not symmetric: on a 15,000-item catalog a series listing is
   * about 3.8 ms while a media-item listing is roughly 25 ms, so "just fetch it all"
   * is expensive in exactly the places it matters.
   *
   * The total always comes from the page envelope. "Showing 50" with no denominator
   * is the thing an administrator cannot act on — it does not say whether the next
   * click is the end.
   */
  import { ChevronLeft, ChevronRight } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'

  let {
    /** Column headers, in order. */
    columns,
    /** The native page envelope: `{ items, page, totalItems, hasPrevious, hasNext }`. */
    page,
    /** Rendered once per item, receiving the item. */
    row,
    /** Called with the zero-based page to load. */
    onpage,
    caption,
    /** Shown instead of rows when the page is empty. */
    emptyLabel,
  } = $props()

  const items = $derived(page?.items ?? [])
  // Displayed one-based: `page` is zero-based in the API, and showing "page 0 of 3"
  // to an operator is a leaked implementation detail.
  const humanPage = $derived((page?.page ?? 0) + 1)
  const totalPages = $derived(Math.max(1, page?.totalPages ?? 1))
</script>

<div class="scroller">
  <table>
    <caption class="visually-hidden">{caption}</caption>
    <thead>
      <tr>
        {#each columns as column (column)}
          <th scope="col">{column}</th>
        {/each}
      </tr>
    </thead>
    <tbody>
      {#each items as item, index (item.id ?? index)}
        {@render row(item)}
      {/each}
      {#if items.length === 0}
        <tr><td colspan={columns.length} class="empty">{emptyLabel}</td></tr>
      {/if}
    </tbody>
  </table>
</div>

{#if page}
  <nav class="paging" aria-label={caption}>
    <p class="total">
      {$_('admin.paging.summary', {
        values: { shown: items.length, total: page.totalItems, page: humanPage, pages: totalPages },
      })}
    </p>
    <div class="buttons">
      <button
        type="button"
        data-testid="page-previous"
        disabled={!page.hasPrevious}
        aria-label={$_('admin.paging.previous')}
        onclick={() => onpage(page.page - 1)}
      >
        <ChevronLeft size={18} aria-hidden="true" />
      </button>
      <button
        type="button"
        data-testid="page-next"
        disabled={!page.hasNext}
        aria-label={$_('admin.paging.next')}
        onclick={() => onpage(page.page + 1)}
      >
        <ChevronRight size={18} aria-hidden="true" />
      </button>
    </div>
  </nav>
{/if}

<style>
  /* Wide content scrolls inside its own container; the page body never scrolls
     sideways. */
  .scroller {
    overflow-x: auto;
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--font-sm);
  }
  :global(.scroller th),
  :global(.scroller td) {
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--line-subtle);
    text-align: left;
    vertical-align: middle;
  }
  thead th {
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  :global(.scroller tbody tr) {
    height: var(--table-row-height);
  }
  .empty {
    color: var(--text-muted);
  }
  .paging {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    margin-top: var(--space-3);
  }
  .total {
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
