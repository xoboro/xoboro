<script>
  /**
   * API keys.
   *
   * Self-service only — no route accepts a user identifier, so nobody can select or
   * act on another account's key. This screen lives in the console because that is
   * where an administrator looks for it, not because it is administrator-scoped.
   *
   * The plaintext key is in the response **once**. Xoboro stores a digest, so it
   * cannot be retrieved again; the UI shows it after creation and says plainly that
   * this is the only time. Anything less and the operator closes the dialog and has a
   * key they cannot use.
   *
   * An expired key is still listed, with its expiry in the past, so its owner can see
   * which one stopped working rather than guessing.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { createApiKey, deleteApiKey, listApiKeys } from '../lib/api/admin.js'
  import { session } from '../lib/session.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Dialog from '../components/Dialog.svelte'
  import TypedConfirmDialog from './TypedConfirmDialog.svelte'

  let keys = $state([])
  let error = $state(null)
  let busy = $state(false)
  let creating = $state(false)
  let comment = $state('')
  let scopes = $state([])
  let expiresAt = $state('')
  /** The one-time plaintext token, held only until the dialog is dismissed. */
  let issued = $state(null)
  let deleting = $state(null)

  // A scope can only narrow, never grant: naming a role the caller does not hold is
  // rejected at creation, so the choices are exactly the caller's own roles.
  const availableScopes = $derived($session.user?.roles ?? [])

  async function load() {
    try {
      keys = await listApiKeys()
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(load)

  function toggleScope(role) {
    scopes = scopes.includes(role) ? scopes.filter((entry) => entry !== role) : [...scopes, role]
  }

  async function create(event) {
    event.preventDefault()
    if (busy) return
    busy = true
    error = null
    try {
      const created = await createApiKey({
        comment: comment.trim(),
        scopes,
        // An absolute instant in epoch milliseconds, not a duration.
        expiresAtMillis: expiresAt ? new Date(expiresAt).getTime() : null,
      })
      issued = created
      creating = false
      comment = ''
      scopes = []
      expiresAt = ''
      await load()
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }

  async function confirmDelete() {
    busy = true
    try {
      await deleteApiKey(deleting.id)
      deleting = null
      await load()
    } catch (caught) {
      error = caught
      deleting = null
    } finally {
      busy = false
    }
  }

  function expiry(key) {
    if (!key.expiresAtMillis) return $_('admin.apiKeys.noExpiry')
    const at = new Date(key.expiresAtMillis)
    const past = key.expiresAtMillis <= Date.now()
    return past
      ? $_('admin.apiKeys.expiredAt', { values: { at: at.toLocaleString() } })
      : at.toLocaleString()
  }
</script>

<header class="screen">
  <h1>{$_('admin.nav.apiKeys')}</h1>
  <button class="primary" type="button" data-testid="add-key" onclick={() => (creating = true)}>
    {$_('admin.apiKeys.create')}
  </button>
</header>

<ErrorNotice {error} onretry={load} />

<div class="scroller">
  <table>
    <caption class="visually-hidden">{$_('admin.nav.apiKeys')}</caption>
    <thead>
      <tr>
        <th scope="col">{$_('admin.apiKeys.comment')}</th>
        <th scope="col">{$_('admin.apiKeys.scopes')}</th>
        <th scope="col">{$_('admin.apiKeys.expiry')}</th>
        <th scope="col">{$_('admin.libraries.actions')}</th>
      </tr>
    </thead>
    <tbody>
      {#each keys as key (key.id)}
        {@const expired = key.expiresAtMillis && key.expiresAtMillis <= Date.now()}
        <tr>
          <th scope="row">{key.comment}</th>
          <td>
            {#if key.scopes?.length}
              <span class="mono">{key.scopes.join(', ')}</span>
            {:else}
              <!-- An empty scope is not "no access": the key is as capable as its
                   owner, which is how every key predating scoping behaves. -->
              <span>{$_('admin.apiKeys.fullScope')}</span>
            {/if}
          </td>
          <td class:expired>{expiry(key)}</td>
          <td>
            <button
              class="quiet-danger"
              type="button"
              disabled={busy}
              data-testid={`delete-key-${key.id}`}
              onclick={() => (deleting = key)}
            >
              {$_('admin.libraries.delete')}
            </button>
          </td>
        </tr>
      {/each}
      {#if keys.length === 0}
        <tr><td colspan="4" class="none">{$_('admin.apiKeys.none')}</td></tr>
      {/if}
    </tbody>
  </table>
</div>

{#if creating}
  <Dialog title={$_('admin.apiKeys.create')} onclose={() => (creating = false)}>
    {#snippet children()}
      <form id="key-form" onsubmit={create} novalidate>
        <label for="key-comment">{$_('admin.apiKeys.comment')}</label>
        <input id="key-comment" data-testid="key-comment" bind:value={comment} required />

        <fieldset>
          <legend>{$_('admin.apiKeys.scopes')}</legend>
          <p class="hint">{$_('admin.apiKeys.scopeHint')}</p>
          {#each availableScopes as role (role)}
            <label class="check">
              <input
                type="checkbox"
                checked={scopes.includes(role)}
                onchange={() => toggleScope(role)}
              />
              <span class="mono">{role}</span>
            </label>
          {/each}
        </fieldset>

        <label for="key-expiry">{$_('admin.apiKeys.expiry')}</label>
        <input id="key-expiry" type="datetime-local" bind:value={expiresAt} />
        <p class="hint">{$_('admin.apiKeys.expiryHint')}</p>
      </form>
    {/snippet}
    {#snippet footer()}
      <button type="button" onclick={() => (creating = false)}>{$_('common.cancel')}</button>
      <button class="primary" type="submit" form="key-form" data-testid="key-save" disabled={busy}>
        {busy ? $_('common.saving') : $_('common.save')}
      </button>
    {/snippet}
  </Dialog>
{/if}

{#if issued}
  <Dialog title={$_('admin.apiKeys.issuedTitle')} onclose={() => (issued = null)}>
    {#snippet children()}
      <p class="once" data-testid="shown-once">{$_('admin.apiKeys.shownOnce')}</p>
      <code data-testid="issued-token">{issued.token}</code>
    {/snippet}
  </Dialog>
{/if}

{#if deleting}
  <TypedConfirmDialog
    title={$_('admin.apiKeys.deleteTitle')}
    expected={deleting.comment}
    summary={$_('admin.apiKeys.deleteSummary', { values: { comment: deleting.comment } })}
    actionLabel={$_('admin.libraries.delete')}
    {busy}
    onconfirm={confirmDelete}
    onclose={() => (deleting = null)}
  />
{/if}

<style>
  .screen {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    margin-bottom: var(--space-4);
  }
  h1 {
    margin: 0;
    font-size: var(--font-xl);
  }
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
  th,
  td {
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--line-subtle);
    text-align: left;
  }
  thead th {
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .expired {
    color: var(--warning);
  }
  form {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  label {
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  input:not([type='checkbox']) {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-raised);
    color: var(--text);
  }
  fieldset {
    margin: var(--space-2) 0 0;
    padding: 0;
    border: 0;
  }
  legend {
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  .check {
    display: flex;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    font-weight: 400;
  }
  .hint {
    margin: var(--space-1) 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .once {
    margin: 0 0 var(--space-3);
    color: var(--warning);
    font-weight: 600;
  }
  code {
    display: block;
    padding: var(--space-3);
    border-radius: var(--radius-sm);
    background: var(--surface-raised);
    font-family: ui-monospace, monospace;
    font-size: var(--font-sm);
    overflow-wrap: anywhere;
    user-select: all;
  }
  .mono {
    font-family: ui-monospace, monospace;
    font-size: var(--font-xs);
  }
  .none {
    color: var(--text-muted);
  }
  .primary {
    min-height: var(--touch-target);
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--accent);
    color: var(--accent-contrast);
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }
  .quiet-danger {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--danger);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--danger);
    font: inherit;
    font-size: var(--font-sm);
    cursor: pointer;
  }
</style>
