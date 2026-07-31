<script>
  /**
   * The only place a failure is turned into text.
   *
   * It renders the catalog entry for the native `code`, never the server's own
   * `message` — that field is English prose and this UI ships in Korean and
   * English. A code with no catalog entry falls back to the generic message
   * rather than printing a raw key path at a user, because the server is allowed
   * to add codes without that being a breaking change.
   */
  import { _ } from '../lib/i18n.js'
  import { Treatment } from '../lib/errors.js'

  let { error, onretry = null } = $props()

  const text = $derived(
    error ? $_(error.messageKey, { default: $_('errors.unknown') }) : '',
  )
  // Throttling is the one failure that carries a usable number, so it says when
  // rather than only what.
  const wait = $derived(
    error?.treatment === Treatment.THROTTLED && error.retryAfterSeconds !== null
      ? $_('session.throttled', { values: { seconds: error.retryAfterSeconds } })
      : '',
  )
</script>

{#if error}
  <!-- assertive: a failed action is the reason the user is waiting, so it is
       announced immediately rather than queued behind other updates. -->
  <p class="notice" role="alert" aria-live="assertive">
    <span>{text}</span>
    {#if wait}<span class="wait">{wait}</span>{/if}
    {#if onretry}
      <!-- Called with no arguments. Passing the handler directly as `onclick` handed it
           the MouseEvent as its first parameter, which is harmless for the handlers that
           take none and silently wrong for any that do: the duplicate-pages retry
           received the event as its page number and requested
           `page=%5Bobject+MouseEvent%5D`, so retrying could never succeed against a real
           server. Dropping the argument here fixes every call site at once, including
           ones written later. -->
      <button type="button" onclick={() => onretry()}>{$_('common.retry')}</button>
    {/if}
  </p>
{/if}

<style>
  .notice {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    margin: 0;
    padding: var(--space-3) var(--space-4);
    border: 1px solid var(--danger);
    border-radius: var(--radius);
    background: var(--danger-quiet);
    color: var(--text);
    font-size: var(--font-sm);
  }
  .wait {
    color: var(--text-muted);
  }
  button {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--text);
    font: inherit;
    cursor: pointer;
  }
</style>
