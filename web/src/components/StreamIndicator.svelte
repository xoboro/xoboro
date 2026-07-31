<script>
  /**
   * Shows the live-connection state.
   *
   * Deliberately a label plus a shape, not a coloured dot: state that only a
   * colour communicates is invisible to a portion of users, and "is this console
   * showing me current data" is not optional information.
   */
  import { Radio, RadioTower, Loader, Unplug } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import { StreamStatus } from '../lib/sse.js'

  let { status } = $props()

  const LABEL_KEY = {
    [StreamStatus.LIVE]: 'stream.live',
    [StreamStatus.CONNECTING]: 'stream.connecting',
    [StreamStatus.RETRYING]: 'stream.retrying',
    [StreamStatus.SUPERSEDED]: 'stream.superseded',
    [StreamStatus.IDLE]: 'stream.idle',
  }

  const label = $derived($_(LABEL_KEY[status] ?? 'stream.idle'))
  const live = $derived(status === StreamStatus.LIVE)
</script>

<span class="indicator" class:live>
  {#if status === StreamStatus.LIVE}
    <RadioTower size={14} aria-hidden="true" />
  {:else if status === StreamStatus.CONNECTING}
    <Loader size={14} aria-hidden="true" />
  {:else if status === StreamStatus.RETRYING}
    <Radio size={14} aria-hidden="true" />
  {:else}
    <Unplug size={14} aria-hidden="true" />
  {/if}
  <span>{label}</span>
</span>

<style>
  .indicator {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-1) var(--space-2);
    border: 1px solid var(--line);
    border-radius: var(--radius-pill);
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .live {
    border-color: var(--accent);
    color: var(--accent-text);
  }
</style>
