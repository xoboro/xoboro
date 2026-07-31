<script>
  /**
   * The durable task queue.
   *
   * The two discard routes stay two buttons. `unclaimed` throws away queued work no
   * worker has taken; `dead` removes rows that permanently failed so their attempt
   * budget starts over on the next enqueue. One button labelled "clear the queue"
   * would discard whichever the operator did not mean, and neither is recoverable.
   *
   * Both report the number removed, because for someone clearing a backlog that
   * count is the answer they came for.
   */
  import { onDestroy, onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { discardTasks, readTaskQueue } from '../lib/api/ops.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Dialog from '../components/Dialog.svelte'

  const TICK_MILLIS = 5_000

  let queue = $state(null)
  let error = $state(null)
  let pendingState = $state(null)
  let busy = $state(false)
  let outcome = $state(null)
  let ticker = null

  async function refresh() {
    try {
      queue = await readTaskQueue()
      error = null
    } catch (caught) {
      error = caught
    }
  }

  // Counted before the dialog opens, so the number in the confirmation is what the
  // server currently holds rather than whatever the screen last happened to show.
  const affected = $derived(
    pendingState === 'unclaimed' ? queue?.pending ?? 0 : queue?.dead ?? 0,
  )

  async function confirm() {
    if (busy) return
    busy = true
    try {
      const result = await discardTasks(pendingState)
      outcome = { state: pendingState, cleared: result.cleared }
      pendingState = null
      await refresh()
    } catch (caught) {
      error = caught
      pendingState = null
    } finally {
      busy = false
    }
  }

  onMount(() => {
    refresh()
    ticker = setInterval(refresh, TICK_MILLIS)
  })

  onDestroy(() => clearInterval(ticker))
</script>

<h1>{$_('admin.nav.tasks')}</h1>

<ErrorNotice {error} onretry={refresh} />

{#if outcome}
  <p class="outcome" role="status" aria-live="polite">
    {$_('admin.tasks.cleared', { values: { count: outcome.cleared } })}
  </p>
{/if}

{#if queue}
  <div class="cards">
    <div class="card">
      <p class="label">{$_('admin.tasks.pending')}</p>
      <p class="value">{queue.pending}</p>
    </div>
    <div class="card">
      <p class="label">{$_('admin.tasks.running')}</p>
      <p class="value">{queue.running}</p>
    </div>
    <div class="card">
      <p class="label">{$_('admin.tasks.dead')}</p>
      <p class="value">{queue.dead}</p>
    </div>
  </div>

  <section class="danger-zone">
    <h2>{$_('admin.dangerZone')}</h2>
    <p class="explain">{$_('admin.tasks.discardExplain')}</p>
    <div class="actions">
      <button
        type="button"
        data-testid="discard-unclaimed"
        disabled={queue.pending === 0}
        onclick={() => (pendingState = 'unclaimed')}
      >
        {$_('admin.tasks.discardUnclaimed')}
      </button>
      <button
        type="button"
        data-testid="discard-dead"
        disabled={queue.dead === 0}
        onclick={() => (pendingState = 'dead')}
      >
        {$_('admin.tasks.discardDead')}
      </button>
    </div>
  </section>
{/if}

{#if pendingState}
  <Dialog title={$_('admin.tasks.confirmTitle')} danger onclose={() => (pendingState = null)}>
    {#snippet children()}
      <p>
        {$_(
          pendingState === 'unclaimed'
            ? 'admin.tasks.confirmUnclaimed'
            : 'admin.tasks.confirmDead',
          { values: { count: affected } },
        )}
      </p>
      <p class="irreversible">{$_('admin.irreversible')}</p>
    {/snippet}
    {#snippet footer()}
      <button type="button" onclick={() => (pendingState = null)}>{$_('common.cancel')}</button>
      <button class="destructive" type="button" data-testid="confirm-discard" disabled={busy} onclick={confirm}>
        {busy ? $_('common.saving') : $_('admin.tasks.confirmAction')}
      </button>
    {/snippet}
  </Dialog>
{/if}

<style>
  h1 {
    margin: 0 0 var(--space-4);
    font-size: var(--font-xl);
  }
  h2 {
    margin: 0 0 var(--space-2);
    font-size: var(--font-md);
  }
  .cards {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(180px, 100%), 1fr));
    gap: var(--space-3);
  }
  .card {
    padding: var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-raised);
  }
  .label {
    margin: 0 0 var(--space-1);
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .value {
    margin: 0;
    font-size: var(--font-xl);
    font-variant-numeric: tabular-nums;
  }
  /* Separated from the ordinary controls, and labelled as what it is. A destructive
     action is never the primary affordance of the screen it lives on. */
  .danger-zone {
    margin-top: var(--space-6);
    padding: var(--space-3);
    border: 1px solid var(--danger);
    border-radius: var(--radius);
  }
  .explain {
    margin: 0 0 var(--space-3);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .actions button {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--text);
    font: inherit;
    cursor: pointer;
  }
  .actions button:disabled {
    opacity: 0.45;
    cursor: default;
  }
  .destructive {
    min-height: var(--touch-target);
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--danger);
    color: var(--danger-contrast);
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }
  .destructive:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .irreversible {
    margin: var(--space-3) 0 0;
    color: var(--danger);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  .outcome {
    margin: 0 0 var(--space-3);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
</style>
