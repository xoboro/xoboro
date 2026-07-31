<script>
  /**
   * Library administration.
   *
   * Carries the console's destructive rules, and the one that matters most is the
   * refusal flow: the server answers `409 library_unavailable` when the storage is
   * unreachable, specifically so a catalog is not destroyed because a mount went
   * missing. The UI therefore offers **re-check availability** first, and only then a
   * separately-labelled override that says what it overrides. `force` is never a
   * pre-ticked box in the first dialog.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { Treatment } from '../lib/errors.js'
  import {
    countCatalog,
    countTrashed,
    deleteLibrary,
    listLibraries,
    recheckAvailability,
    triggerLibraryTask,
  } from '../lib/api/libraries.js'
  import { eventHub } from '../lib/eventHub.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Dialog from '../components/Dialog.svelte'
  import TypedConfirmDialog from './TypedConfirmDialog.svelte'
  import LibraryForm from './LibraryForm.svelte'

  let libraries = $state([])
  let error = $state(null)
  let notice = $state(null)
  let busy = $state(false)

  /** `{ library, counts }` while a trash confirmation is open. */
  let emptying = $state(null)
  /** `{ library, counts, refused }` while a delete confirmation is open. */
  let deleting = $state(null)
  /** The library whose refusal is being explained before an override is offered. */
  let refused = $state(null)
  /**
   * Whether a re-check has been run and still reported the storage unreachable.
   *
   * Forcing is gated on this. Offering re-check and force side by side let an operator
   * take the override without ever answering the question the refusal asks — and the
   * refusal exists because a mount that dropped out for a moment must not cost a
   * catalog.
   */
  let recheckedUnavailable = $state(false)
  /** `{ library }` while the create or edit form is open; `library` is null to create. */
  let editing = $state(null)

  async function refresh() {
    try {
      libraries = await listLibraries()
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(() => {
    refresh()
    return eventHub.on(['library.added', 'library.changed', 'library.removed'], refresh)
  })

  async function runTask(library, task) {
    busy = true
    notice = null
    try {
      await triggerLibraryTask(library.id, task)
      // "Accepted", not "done". These enqueue durable work, and claiming completion
      // here would be a claim the server never made.
      notice = $_('admin.libraries.taskAccepted', { values: { name: library.name, task } })
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }

  async function openEmptyTrash(library) {
    busy = true
    try {
      // Counted from the server before the dialog opens. A confirmation that cannot
      // say how much it destroys is not a confirmation.
      emptying = { library, counts: await countTrashed(library.id) }
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }

  async function confirmEmptyTrash() {
    busy = true
    try {
      await triggerLibraryTask(emptying.library.id, 'empty-trash')
      notice = $_('admin.libraries.trashEmptying', { values: { name: emptying.library.name } })
      emptying = null
    } catch (caught) {
      error = caught
      emptying = null
    } finally {
      busy = false
    }
  }

  async function openDelete(library) {
    busy = true
    try {
      // Counted before the dialog opens. "Removes every catalog entry" is not a blast
      // radius; "1,204 series and 15,050 items" is something an operator can weigh.
      deleting = { library, force: false, counts: await countCatalog(library.id) }
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }

  async function confirmDelete() {
    busy = true
    try {
      await deleteLibrary(deleting.library.id, { force: deleting.force })
      notice = $_('admin.libraries.deleted', { values: { name: deleting.library.name } })
      deleting = null
      await refresh()
    } catch (caught) {
      if (caught.treatment === Treatment.LIBRARY_UNAVAILABLE) {
        // Not shown as a generic failure. The refusal is the server protecting the
        // catalog, and the next step is to find out whether the mount is back — not
        // to try harder.
        refused = deleting.library
        recheckedUnavailable = false
        deleting = null
      } else {
        error = caught
        deleting = null
      }
    } finally {
      busy = false
    }
  }

  async function recheck() {
    busy = true
    try {
      const updated = await recheckAvailability(refused.id)
      await refresh()
      if (updated.unavailable) {
        // Still gone. Only now is an override worth offering, and it is offered as
        // its own decision rather than as a retry of the same button.
        refused = updated
        recheckedUnavailable = true
      } else {
        notice = $_('admin.libraries.availableAgain', { values: { name: updated.name } })
        refused = null
      }
    } catch (caught) {
      error = caught
      refused = null
      recheckedUnavailable = false
    } finally {
      busy = false
    }
  }

  async function forceDelete() {
    const library = refused
    refused = null
    recheckedUnavailable = false
    busy = true
    try {
      deleting = { library, force: true, counts: await countCatalog(library.id) }
    } catch (caught) {
      // The count is unavailable precisely because the storage is - but the catalog rows
      // are in the database, so this should still answer. If it does not, the override is
      // still offered without numbers rather than blocked, and says so.
      deleting = { library, force: true, counts: null }
    } finally {
      busy = false
    }
  }

  function unavailableSince(library) {
    if (!library.unavailableSinceMillis) return ''
    return new Date(library.unavailableSinceMillis).toLocaleString()
  }
</script>

<header class="screen">
  <h1>{$_('admin.nav.libraries')}</h1>
  <button class="primary" type="button" data-testid="add-library" onclick={() => (editing = { library: null })}>
    {$_('admin.libraries.create')}
  </button>
</header>

<ErrorNotice {error} onretry={refresh} />

{#if notice}
  <p class="notice" role="status" aria-live="polite" data-testid="notice">{notice}</p>
{/if}

<div class="scroller">
  <table>
    <caption class="visually-hidden">{$_('admin.nav.libraries')}</caption>
    <thead>
      <tr>
        <th scope="col">{$_('admin.libraries.name')}</th>
        <th scope="col">{$_('admin.libraries.storage')}</th>
        <th scope="col">{$_('admin.libraries.actions')}</th>
      </tr>
    </thead>
    <tbody>
      {#each libraries as library (library.id)}
        <tr>
          <th scope="row">{library.name}</th>
          <td>
            {#if library.unavailable}
              <!-- A word, not a red dot. State that only a colour communicates is
                   invisible to a portion of readers, and this state decides whether
                   deleting is even allowed. -->
              <span class="state unavailable" data-testid={`state-${library.id}`}>
                {$_('admin.libraries.unavailable')}
              </span>
              {#if library.unavailableSinceMillis}
                <span class="since">{unavailableSince(library)}</span>
              {/if}
            {:else}
              <span class="state">{$_('admin.libraries.available')}</span>
            {/if}
          </td>
          <td>
            <div class="row-actions">
              <button type="button" disabled={busy} onclick={() => runTask(library, 'scan')}>
                {$_('admin.libraries.scan')}
              </button>
              <button type="button" disabled={busy} onclick={() => runTask(library, 'analyze')}>
                {$_('admin.libraries.analyze')}
              </button>
              <button
                type="button"
                disabled={busy}
                onclick={() => runTask(library, 'metadata-refresh')}
              >
                {$_('admin.libraries.metadataRefresh')}
              </button>
              <button
                type="button"
                disabled={busy}
                data-testid={`edit-${library.id}`}
                onclick={() => (editing = { library })}
              >
                {$_('admin.libraries.edit')}
              </button>
              {#if library.unavailable}
                <button type="button" disabled={busy} onclick={() => (refused = library)}>
                  {$_('admin.libraries.recheck')}
                </button>
              {/if}
              <!-- Destructive actions sit apart from the ordinary ones and are never
                   the row's primary affordance. -->
              <span class="separator" aria-hidden="true"></span>
              <button
                class="quiet-danger"
                type="button"
                disabled={busy}
                data-testid={`empty-trash-${library.id}`}
                onclick={() => openEmptyTrash(library)}
              >
                {$_('admin.libraries.emptyTrash')}
              </button>
              <button
                class="quiet-danger"
                type="button"
                disabled={busy}
                data-testid={`delete-${library.id}`}
                onclick={() => openDelete(library)}
              >
                {$_('admin.libraries.delete')}
              </button>
            </div>
          </td>
        </tr>
      {/each}
      {#if libraries.length === 0}
        <tr><td colspan="3" class="empty">{$_('admin.libraries.none')}</td></tr>
      {/if}
    </tbody>
  </table>
</div>

{#if editing}
  <LibraryForm
    library={editing.library}
    onclose={() => (editing = null)}
    onsaved={async () => {
      editing = null
      await refresh()
    }}
  />
{/if}

{#if emptying}
  <TypedConfirmDialog
    title={$_('admin.libraries.emptyTrashTitle')}
    expected={emptying.library.name}
    summary={$_('admin.libraries.emptyTrashSummary', {
      values: {
        name: emptying.library.name,
        series: emptying.counts.series,
        mediaItems: emptying.counts.mediaItems,
      },
    })}
    actionLabel={$_('admin.libraries.emptyTrash')}
    {busy}
    onconfirm={confirmEmptyTrash}
    onclose={() => (emptying = null)}
  />
{/if}

{#if deleting}
  <TypedConfirmDialog
    title={deleting.force
      ? $_('admin.libraries.forceDeleteTitle')
      : $_('admin.libraries.deleteTitle')}
    expected={deleting.library.name}
    summary={$_('admin.libraries.deleteSummary', {
      values: {
        name: deleting.library.name,
        series: deleting.counts?.series ?? 0,
        mediaItems: deleting.counts?.mediaItems ?? 0,
      },
    })}
    actionLabel={deleting.force
      ? $_('admin.libraries.forceDeleteAction')
      : $_('admin.libraries.delete')}
    {busy}
    onconfirm={confirmDelete}
    onclose={() => (deleting = null)}
  >
    {#snippet extra()}
      {#if deleting.force}
        <p class="override" data-testid="force-warning">
          {$_('admin.libraries.forceDeleteWarning')}
        </p>
      {/if}
    {/snippet}
  </TypedConfirmDialog>
{/if}

{#if refused}
  <Dialog title={$_('admin.libraries.refusedTitle')} onclose={() => (refused = null)}>
    {#snippet children()}
      <p>{$_('admin.libraries.refusedExplain', { values: { name: refused.name } })}</p>
      {#if refused.unavailableSinceMillis}
        <p class="since-detail">
          {$_('admin.libraries.unavailableSince', { values: { at: unavailableSince(refused) } })}
        </p>
      {/if}
    {/snippet}
    {#snippet footer()}
      <button type="button" onclick={() => (refused = null)}>{$_('common.cancel')}</button>
      <!-- The re-check comes first and is the primary action: it answers "is the
           mount back?" directly, and a successful check is what makes an ordinary
           delete stop being refused. -->
      <button
        class="primary"
        type="button"
        data-testid="recheck"
        disabled={busy}
        onclick={recheck}
      >
        {$_('admin.libraries.recheck')}
      </button>
      {#if recheckedUnavailable}
        <!-- Only after a re-check has confirmed the storage is still gone. Before that
             there is nothing to override: the refusal might simply be stale. -->
        <button
          class="quiet-danger"
          type="button"
          data-testid="force-delete"
          disabled={busy}
          onclick={forceDelete}
        >
          {$_('admin.libraries.forceDeleteAction')}
        </button>
      {:else}
        <p class="gate" data-testid="force-gate">{$_('admin.libraries.recheckFirst')}</p>
      {/if}
    {/snippet}
  </Dialog>
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
  .notice {
    margin: 0 0 var(--space-3);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  /* Wide content scrolls inside its own container. The page body never scrolls
     sideways. */
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
    vertical-align: middle;
  }
  thead th {
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  tbody tr {
    height: var(--table-row-height);
  }
  tbody th {
    font-weight: 600;
  }
  .state {
    color: var(--text-secondary);
  }
  .unavailable {
    color: var(--warning);
    font-weight: 600;
  }
  .since {
    display: block;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .since-detail {
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  .row-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .row-actions button {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--text);
    font: inherit;
    font-size: var(--font-sm);
    cursor: pointer;
  }
  .row-actions button:disabled {
    opacity: 0.45;
    cursor: default;
  }
  .separator {
    width: 1px;
    height: 24px;
    background: var(--line-strong);
  }
  .quiet-danger {
    border-color: var(--danger) !important;
    color: var(--danger) !important;
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
  .gate {
    margin: 0;
    align-self: center;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .override {
    margin: 0 0 var(--space-3);
    padding: var(--space-3);
    border: 1px solid var(--warning);
    border-radius: var(--radius-sm);
    font-size: var(--font-sm);
  }
  .empty {
    color: var(--text-muted);
  }
</style>
