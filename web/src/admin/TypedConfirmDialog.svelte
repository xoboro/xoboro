<script>
  /**
   * Confirmation for something unrecoverable.
   *
   * The resource name must be typed. A checkbox is not friction — it is a reflex —
   * and these actions destroy catalog rows or touch files on disk.
   *
   * The blast radius comes in as [summary] and must be a number the **server**
   * supplied. "Empty trash" means nothing; "destroy 412 trashed entries in Synthetic
   * Library" is something an operator can decide about.
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
  const matches = $derived(typed === expected)
</script>

<Dialog {title} danger {onclose}>
  {#snippet children()}
    <p class="summary">{summary}</p>
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
