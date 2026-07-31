<script>
  /**
   * Confirmation for something unrecoverable.
   *
   * The resource name must be typed. A checkbox is not friction — it is a reflex —
   * and these actions destroy catalog rows or touch files on disk.
   *
   * [summary] states the blast radius. Where the server can count what an action
   * destroys, that count belongs here and the caller is expected to have fetched it —
   * "empty trash" means nothing, "destroy 412 trashed entries in Synthetic Library" is
   * something an operator can decide about. Two callers legitimately have no number:
   * deleting a user (no endpoint counts one account's progress rows) and a forced
   * library delete whose count could not be read. Both say so in words instead. This
   * component cannot tell the difference between a missing number and a deliberate
   * absence, so it does not pretend to enforce one.
   */
  import { _ } from '../lib/i18n.js'
  import Dialog from '../components/Dialog.svelte'
  import { nextDialogId } from '../lib/dialogId.js'

  let {
    title,
    /** The exact string the operator has to type — normally the resource's name. */
    expected,
    /** One line stating what will be destroyed, in server-supplied numbers. */
    summary,
    actionLabel,
    onconfirm,
    onclose,
    busy = false,
    /**
     * Extra content between the summary and the field.
     *
     * Deliberately not called `children`: this component passes a snippet named
     * `children` to Dialog, and inside that snippet the local name shadows the prop,
     * so `{@render children()}` would render the snippet from within itself.
     */
    extra = null,
  } = $props()

  const fieldId = `typed-confirm-${nextDialogId()}`
  let typed = $state('')

  // Compared exactly. Trimming or lowercasing would make the field easier to satisfy
  // by accident, which is the one property it exists to prevent.
  //
  // A blank `expected` is unsatisfiable rather than satisfied-by-default. `'' === ''`
  // is true, so an empty expectation would enable the destructive button with nothing
  // typed — the whole gate gone. No caller passes one today (the one that could,
  // ApiKeys, relies on the server refusing blank comments), which is exactly the sort
  // of guarantee that holds until it quietly stops holding.
  const matches = $derived(Boolean(expected) && typed === expected)
</script>

<Dialog {title} danger {onclose}>
  {#snippet children()}
    <p class="summary" data-testid="confirm-summary">{summary}</p>
    {#if extra}{@render extra()}{/if}
    <p class="irreversible">{$_('admin.irreversible')}</p>

    <label for={fieldId}>
      {$_('admin.typeToConfirm', { values: { name: expected } })}
    </label>
    <input id={fieldId} bind:value={typed} autocomplete="off" spellcheck="false" />
  {/snippet}

  {#snippet footer()}
    <button type="button" onclick={onclose}>{$_('common.cancel')}</button>
    <button
      class="destructive"
      type="button"
      data-testid="typed-confirm"
      disabled={!matches || busy}
      onclick={onconfirm}
    >
      {busy ? $_('common.saving') : actionLabel}
    </button>
  {/snippet}
</Dialog>

<style>
  .summary {
    margin: 0 0 var(--space-3);
  }
  .irreversible {
    margin: 0 0 var(--space-4);
    color: var(--danger);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  label {
    display: block;
    margin-bottom: var(--space-2);
    color: var(--text-secondary);
    font-size: var(--font-sm);
  }
  input {
    width: 100%;
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    background: var(--surface-raised);
    color: var(--text);
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
</style>
