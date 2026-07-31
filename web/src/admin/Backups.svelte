<script>
  /**
   * Database backups.
   *
   * Restoring is deliberately absent from the API: it requires taking the live
   * database file offline, which conflicts with the running server's own lock. The
   * screen therefore **names the CLI command** instead of pretending the capability
   * does not exist — an operator who cannot find how to restore a backup has a
   * backup they cannot use.
   *
   * Creating is synchronous and answers `201`: it is one `VACUUM INTO` plus an
   * integrity check, not a filesystem walk, so unlike a library scan there is nothing
   * to enqueue and nothing to wait for afterwards.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { RESTORE_COMMAND, createBackup, deleteBackup, listBackups } from '../lib/api/ops.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import TypedConfirmDialog from './TypedConfirmDialog.svelte'

  let backups = $state([])
  let error = $state(null)
  let busy = $state(false)
  let deleting = $state(null)

  async function load() {
    try {
      backups = await listBackups()
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(load)

  async function create() {
    busy = true
    error = null
    try {
      await createBackup()
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
      await deleteBackup(deleting.id)
      deleting = null
      await load()
    } catch (caught) {
      error = caught
      deleting = null
    } finally {
      busy = false
    }
  }

  function megabytes(bytes) {
    return `${(bytes / 1_048_576).toFixed(1)} MB`
  }

  function when(millis) {
    return new Date(millis).toLocaleString()
  }
</script>

<header class="screen">
  <h1>{$_('admin.nav.backups')}</h1>
  <button class="primary" type="button" data-testid="create-backup" disabled={busy} onclick={create}>
    {busy ? $_('common.saving') : $_('admin.backups.create')}
  </button>
</header>

<ErrorNotice {error} onretry={load} />

<div class="scroller">
  <table>
    <caption class="visually-hidden">{$_('admin.nav.backups')}</caption>
    <thead>
      <tr>
        <th scope="col">{$_('admin.backups.created')}</th>
        <th scope="col">{$_('admin.backups.size')}</th>
        <th scope="col">{$_('admin.libraries.actions')}</th>
      </tr>
    </thead>
    <tbody>
      {#each backups as backup (backup.id)}
        <tr>
          <th scope="row">{when(backup.createdAtMillis)}</th>
          <td>{megabytes(backup.sizeBytes)}</td>
          <td>
            <button
              class="quiet-danger"
              type="button"
              disabled={busy}
              data-testid={`delete-backup-${backup.id}`}
              onclick={() => (deleting = backup)}
            >
              {$_('admin.libraries.delete')}
            </button>
          </td>
        </tr>
      {/each}
      {#if backups.length === 0}
        <tr><td colspan="3" class="empty">{$_('admin.backups.none')}</td></tr>
      {/if}
    </tbody>
  </table>
</div>

<section class="restore">
  <h2>{$_('admin.backups.restoreTitle')}</h2>
  <p>{$_('admin.backups.restoreExplain')}</p>
  <!-- The command, verbatim and selectable. Describing restore without naming how to
       do it would be worse than saying nothing. -->
  <code data-testid="restore-command">{RESTORE_COMMAND}</code>
</section>

{#if deleting}
  <TypedConfirmDialog
    title={$_('admin.backups.deleteTitle')}
    expected={deleting.id}
    summary={$_('admin.backups.deleteSummary', {
      values: { at: when(deleting.createdAtMillis), size: megabytes(deleting.sizeBytes) },
    })}
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
  h2 {
    margin: 0 0 var(--space-2);
    font-size: var(--font-md);
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
  .restore {
    margin-top: var(--space-5);
    padding: var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  .restore p {
    margin: 0 0 var(--space-2);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  code {
    display: block;
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-sm);
    background: var(--surface-raised);
    font-family: ui-monospace, monospace;
    font-size: var(--font-sm);
    user-select: all;
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
  .empty {
    color: var(--text-muted);
  }
</style>
