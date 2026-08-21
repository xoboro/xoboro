<script>
  /**
   * Narrows the shelf to exactly one library.
   *
   * Distinct from LibraryFilter, which belongs to search: that one is a multi-select
   * over which libraries a query covers, this one is a single choice about what the
   * reader is browsing. Collapsing them into one control would mean either a search
   * that cannot span two libraries or a shelf that can show half of each, and neither
   * is what its screen is for.
   *
   * A horizontal scroller rather than a wrapping row, so adding a library never pushes
   * the shelf below the fold. The chosen pill is marked with `aria-current`, which is
   * what a set of navigation-like choices means, rather than `aria-pressed`.
   *
   * Rendered only when there is more than one library. With one, every option leads to
   * the same shelf, and a control that cannot change any result costs a reader the time
   * it takes to work that out.
   */
  import { _ } from '../lib/i18n.js'

  let {
    /** Libraries visible to this reader, as `/libraries` returns them. */
    libraries,
    /** The chosen library id. */
    selected,
    /** Called with the chosen library id. */
    onchange,
  } = $props()
</script>

{#if libraries.length > 1}
  <nav class="switcher" aria-label={$_('catalog.library.switcher')}>
    {#each libraries as library (library.id)}
      <button
        type="button"
        class:on={selected === library.id}
        data-testid={`library-${library.id}`}
        aria-current={selected === library.id ? 'true' : undefined}
        onclick={() => onchange(library.id)}
      >
        {library.name}
      </button>
    {/each}
  </nav>
{/if}

<style>
  .switcher {
    display: flex;
    gap: var(--space-2);
    padding: 0 var(--gutter-right) var(--space-3) var(--gutter-left);
    overflow-x: auto;
    /* The row scrolls; the page must not. */
    scrollbar-width: none;
  }
  .switcher::-webkit-scrollbar {
    display: none;
  }
  button {
    display: flex;
    min-height: var(--touch-target);
    flex: 0 0 auto;
    align-items: center;
    padding: 0 var(--space-4);
    border: 1px solid var(--line);
    border-radius: var(--radius-pill);
    background: var(--surface-raised);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    white-space: nowrap;
    cursor: pointer;
  }
  button.on {
    border-color: var(--accent);
    background: var(--accent-quiet);
    color: var(--accent-text);
    font-weight: 600;
  }
</style>
