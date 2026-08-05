<script>
  /**
   * Where the reader is in a paged listing, and the two buttons that move them.
   *
   * Every number shown comes from the page envelope - `page`, `totalPages`,
   * `hasPrevious`, `hasNext`. None of it is counted from the rows on screen, because
   * `items.length` is the size of *this* page: presenting it as the size of the set told
   * a reader with 3,339 series that there were 100.
   *
   * That was not a display bug but an unreachable catalog. The reader's home page asked
   * for one page of 100 and rendered it as "all series", so a library of 3,339 showed its
   * first 100 and offered no way to the rest - while the listing route it called has
   * answered `page` since it existed.
   */
  import { ChevronLeft, ChevronRight } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'

  let {
    /** The native page envelope, or `null` before the first answer. */
    page,
    /** Called with the zero-based page to load. */
    onpage,
    /** True while a request is outstanding, which disables both buttons. */
    busy = false,
    /** Names the region for a screen reader; the listing supplies its own noun. */
    label,
    /** Distinguishes this pager's buttons when a screen carries more than one. */
    testIdPrefix = 'pager',
  } = $props()

  // One-based on screen. `page` is zero-based on the wire, and "page 0 of 5" is a leaked
  // implementation detail.
  const humanPage = $derived((page?.page ?? 0) + 1)
  const totalPages = $derived(Math.max(1, page?.totalPages ?? 1))
</script>

{#if page}
  <nav class="paging" aria-label={label}>
    <p class="position">
      {$_('common.paging.summary', {
        values: { page: humanPage, pages: totalPages, total: page.totalItems ?? 0 },
      })}
    </p>
    <div class="buttons">
      <button
        type="button"
        data-testid={`${testIdPrefix}-previous`}
        disabled={!page.hasPrevious || busy}
        aria-label={$_('common.paging.previous')}
        onclick={() => onpage(page.page - 1)}
      >
        <ChevronLeft size={18} aria-hidden="true" />
      </button>
      <button
        type="button"
        data-testid={`${testIdPrefix}-next`}
        disabled={!page.hasNext || busy}
        aria-label={$_('common.paging.next')}
        onclick={() => onpage(page.page + 1)}
      >
        <ChevronRight size={18} aria-hidden="true" />
      </button>
    </div>
  </nav>
{/if}

<style>
  .paging {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    margin: var(--space-4) 0 var(--space-6);
  }
  .position {
    margin: 0;
    color: var(--text-muted);
    font-size: var(--font-sm);
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
