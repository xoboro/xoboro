<script>
  /**
   * Operational overview.
   *
   * Live, but not by polling. The base this UI grew from polled on a hand-tuned
   * backoff ladder because Komga gave it nothing better; Xoboro publishes events, so
   * a refresh happens when something actually changed. The measured runtime queues
   * over ten thousand tasks on a 15,000-item catalog, which makes "ask again in five
   * seconds and see if the numbers moved" both expensive and a poor question.
   *
   * Task counts still have a slow tick behind them, because queue drain is not an
   * event — nothing is published when a worker finishes an item, so the only way to
   * watch a backlog shrink is to ask.
   */
  import { onDestroy, onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { readMetrics, readTaskQueue } from '../lib/api/ops.js'
  import { eventHub } from '../lib/eventHub.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'

  const QUEUE_TICK_MILLIS = 10_000

  let metrics = $state(null)
  let queue = $state(null)
  let error = $state(null)
  let ticker = null

  async function refresh() {
    try {
      const [snapshot, counts] = await Promise.all([readMetrics(), readTaskQueue()])
      metrics = snapshot
      queue = counts
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(() => {
    refresh()
    ticker = setInterval(refresh, QUEUE_TICK_MILLIS)

    // Any catalog change moves the numbers on this screen, so one subscription over
    // the whole set is enough — the payload is an invalidation signal, not something
    // to read field by field.
    const off = eventHub.on(
      ['library.added', 'library.changed', 'library.removed', 'media-item.added', 'series.added'],
      refresh,
    )
    // A resync means this view may be stale, and it is the only signal that does.
    const offResync = eventHub.onResync(refresh)

    return () => {
      off()
      offResync()
    }
  })

  onDestroy(() => clearInterval(ticker))

  function seconds(value) {
    return value === undefined || value === null ? '—' : `${Math.floor(value)}s`
  }
</script>

<h1>{$_('admin.nav.overview')}</h1>

<ErrorNotice {error} onretry={refresh} />

{#if metrics}
  <div class="cards">
    <div class="card">
      <p class="label">{$_('admin.overview.ready')}</p>
      <!-- Words, not a coloured dot: whether the server is serving is not optional
           information, and colour alone does not carry it for every reader. -->
      <p class="value">{metrics.ready ? $_('admin.overview.up') : $_('admin.overview.down')}</p>
    </div>
    <div class="card">
      <p class="label">{$_('admin.overview.uptime')}</p>
      <p class="value">{seconds(metrics.uptimeSeconds)}</p>
    </div>
    <div class="card">
      <p class="label">{$_('admin.overview.requests')}</p>
      <p class="value">{metrics.totalRequests}</p>
      <p class="detail">
        {#each Object.entries(metrics.requestsByStatusClass ?? {}) as [statusClass, count] (statusClass)}
          <span>{statusClass}: {count}</span>
        {/each}
      </p>
    </div>
    <div class="card">
      <p class="label">{$_('admin.overview.workers')}</p>
      <p class="value">{metrics.taskWorkerCount}</p>
    </div>
  </div>
{:else if !error}
  <p class="waiting" role="status">{$_('common.loading')}</p>
{/if}

{#if queue}
  <h2>{$_('admin.nav.tasks')}</h2>
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
{/if}

<style>
  h1 {
    margin: 0 0 var(--space-4);
    font-size: var(--font-xl);
  }
  h2 {
    margin: var(--space-6) 0 var(--space-3);
    font-size: var(--font-lg);
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
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }
  .value {
    margin: 0;
    font-size: var(--font-xl);
    font-variant-numeric: tabular-nums;
  }
  .detail {
    display: flex;
    margin: var(--space-2) 0 0;
    gap: var(--space-2);
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .waiting {
    color: var(--text-muted);
  }
</style>
