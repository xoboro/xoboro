<script>
  /**
   * Lists collections or read lists, and administers them.
   *
   * Reading is open to any authenticated caller; every mutation is administrator-only,
   * so the create and delete affordances are shown to administrators and the server
   * refuses them regardless — the check that matters is the server's.
   *
   * `memberCount` is rendered as *visible* members, not as the grouping's size. A
   * restricted reader legitimately sees a smaller number than an administrator does
   * for the same collection, and presenting it as the total would make the two
   * disagree with no explanation.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { deleteGrouping, listGroupings } from '../lib/api/groupings.js'
  import { isAdministrator, session } from '../lib/session.js'
  import { eventHub } from '../lib/eventHub.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import TypedConfirmDialog from '../admin/TypedConfirmDialog.svelte'
  import GroupingForm from './GroupingForm.svelte'

  let { kind } = $props()

  let groupings = $state([])
  let error = $state(null)
  let busy = $state(false)
  let editing = $state(null)
  let deleting = $state(null)

  const administrator = $derived(isAdministrator($session.user))

  async function load() {
    try {
      groupings = await listGroupings(kind)
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(() => {
    load()
    const events =
      kind === 'collection'
        ? ['collection.added', 'collection.changed', 'collection.removed']
        : ['read-list.added', 'read-list.changed', 'read-list.removed']
    return eventHub.on(events, load)
  })

  async function confirmDelete() {
    busy = true
    try {
      await deleteGrouping(kind, deleting.id)
      deleting = null
      await load()
    } catch (caught) {
      error = caught
      deleting = null
    } finally {
      busy = false
    }
  }
</script>

<header class="screen">
  <h1>{$_(`catalog.${kind}.title`)}</h1>
  {#if administrator}
    <button
      class="primary"
      type="button"
      data-testid="add-grouping"
      onclick={() => (editing = { grouping: null })}
    >
      {$_(`catalog.${kind}.createTitle`)}
    </button>
  {/if}
</header>

<ErrorNotice {error} onretry={load} />

<ul class="groupings">
  {#each groupings as grouping (grouping.id)}
    <li>
      <div class="identity">
        <span class="name">{grouping.name}</span>
        <span class="count">
          {$_('catalog.grouping.visibleCount', { values: { count: grouping.memberCount ?? 0 } })}
        </span>
        {#if grouping.ordered}
          <span class="badge">{$_('catalog.grouping.orderedBadge')}</span>
        {/if}
      </div>
      {#if administrator}
        <div class="actions">
          <button
            type="button"
            data-testid={`edit-grouping-${grouping.id}`}
            onclick={() => (editing = { grouping })}
          >
            {$_('admin.libraries.edit')}
          </button>
          <span class="separator" aria-hidden="true"></span>
          <button
            class="quiet-danger"
            type="button"
            data-testid={`delete-grouping-${grouping.id}`}
            onclick={() => (deleting = grouping)}
          >
            {$_('admin.libraries.delete')}
          </button>
        </div>
      {/if}
    </li>
  {/each}
  {#if groupings.length === 0}
    <li class="empty">{$_(`catalog.${kind}.none`)}</li>
  {/if}
</ul>

{#if editing}
  <GroupingForm
    {kind}
    grouping={editing.grouping}
    onclose={() => (editing = null)}
    onsaved={async () => {
      editing = null
      await load()
    }}
  />
{/if}

{#if deleting}
  <TypedConfirmDialog
    title={$_('catalog.grouping.deleteTitle')}
    expected={deleting.name}
    summary={$_('catalog.grouping.deleteSummary', { values: { name: deleting.name } })}
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
    padding: max(var(--space-4), calc(var(--inset-top) + var(--space-2)))
      var(--gutter-right) 0 var(--gutter-left);
  }
  h1 {
    margin: 0;
    font-size: var(--font-lg);
  }
  .groupings {
    margin: 0;
    padding: 0 var(--gutter-right) var(--space-5) var(--gutter-left);
    list-style: none;
  }
  .groupings li {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-3) 0;
    border-bottom: 1px solid var(--line-subtle);
  }
  .identity {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: var(--space-2);
  }
  .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .count {
    flex: 0 0 auto;
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  .badge {
    flex: 0 0 auto;
    padding: 2px var(--space-2);
    border-radius: var(--radius-pill);
    background: var(--surface-selected);
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .actions {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
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
    font-size: var(--font-sm);
    cursor: pointer;
  }
  .quiet-danger {
    border-color: var(--danger) !important;
    color: var(--danger) !important;
  }
  .separator {
    width: 1px;
    height: 24px;
    background: var(--line-strong);
  }
  .empty {
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
</style>
