<script>
  /**
   * Chooses whether a series lists its newest chapter first or its oldest.
   *
   * Two buttons rather than a `<select>`: there are exactly two states, and a select
   * costs a tap to open before the tap that chooses. `aria-pressed` carries which one
   * is active, so the choice is announced rather than only coloured.
   *
   * The default is newest-first, which is where a reader following a running series
   * looks. Reading a series from the beginning is served by the "from the start"
   * action instead, so neither reader has to change this to get going.
   */
  import { ArrowDownWideNarrow, ArrowUpNarrowWide } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import { SeriesOrder } from '../lib/api/catalog.js'

  let {
    /** The active order, one of {@link SeriesOrder}. */
    order,
    /** Called with the chosen order. */
    onchange,
  } = $props()

  const OPTIONS = [
    { value: SeriesOrder.NEWEST, key: 'newest', icon: ArrowDownWideNarrow },
    { value: SeriesOrder.OLDEST, key: 'oldest', icon: ArrowUpNarrowWide },
  ]
</script>

<div class="orders" role="group" aria-label={$_('reader.order.label')}>
  {#each OPTIONS as option (option.value)}
    <button
      type="button"
      class:on={order === option.value}
      data-testid={`series-order-${option.key}`}
      aria-pressed={order === option.value}
      onclick={() => onchange(option.value)}
    >
      <option.icon size={16} aria-hidden="true" />
      <span>{$_(`reader.order.${option.key}`)}</span>
    </button>
  {/each}
</div>

<style>
  .orders {
    display: flex;
    gap: var(--space-1);
  }
  button {
    display: flex;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-1);
    padding: 0 var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius-pill);
    background: var(--surface-raised);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    cursor: pointer;
  }
  button.on {
    border-color: var(--accent);
    background: var(--accent-quiet);
    color: var(--accent-text);
  }
</style>
