<script>
  /**
   * User administration.
   *
   * Two server refusals need explaining rather than merely failing:
   * `cannot_delete_own_account`, and `last_administrator_protected` when a request
   * would remove or demote the only remaining administrator. Both are protections,
   * not errors the operator can retry their way out of, so they read as sentences
   * rather than as codes.
   *
   * Roles and library grants are replaced exactly as submitted. Unknown role names and
   * invalid library identifiers are rejected and change nothing — a mistyped role that
   * was silently dropped would grant fewer rights than asked for while answering with
   * success.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { deleteUser, listUsers } from '../lib/api/admin.js'
  import { session } from '../lib/session.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import TypedConfirmDialog from './TypedConfirmDialog.svelte'
  import UserForm from './UserForm.svelte'

  let users = $state([])
  let error = $state(null)
  let busy = $state(false)
  let editing = $state(null)
  let deleting = $state(null)

  const self = $derived($session.user)
  const administratorCount = $derived(
    users.filter((user) => user.roles?.includes('ADMIN')).length,
  )

  async function load() {
    try {
      users = await listUsers()
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(load)

  async function confirmDelete() {
    busy = true
    try {
      await deleteUser(deleting.id)
      deleting = null
      await load()
    } catch (caught) {
      error = caught
      deleting = null
    } finally {
      busy = false
    }
  }

  /**
   * Why deleting this account is not offered, or `null` when it is.
   *
   * The server enforces both; stating the reason up front is better than presenting a
   * button whose only outcome is a refusal.
   */
  function blockedReason(user) {
    if (user.id === self?.id) return $_('admin.users.cannotDeleteSelf')
    if (user.roles?.includes('ADMIN') && administratorCount <= 1) {
      return $_('admin.users.lastAdministrator')
    }
    return null
  }
</script>

<header class="screen">
  <h1>{$_('admin.nav.users')}</h1>
  <button
    class="primary"
    type="button"
    data-testid="add-user"
    onclick={() => (editing = { user: null })}
  >
    {$_('admin.users.create')}
  </button>
</header>

<ErrorNotice {error} onretry={load} />

<div class="scroller">
  <table>
    <caption class="visually-hidden">{$_('admin.nav.users')}</caption>
    <thead>
      <tr>
        <th scope="col">{$_('session.email')}</th>
        <th scope="col">{$_('admin.users.roles')}</th>
        <th scope="col">{$_('admin.users.libraries')}</th>
        <th scope="col">{$_('admin.libraries.actions')}</th>
      </tr>
    </thead>
    <tbody>
      {#each users as user (user.id)}
        {@const blocked = blockedReason(user)}
        <tr>
          <th scope="row">{user.email}</th>
          <td>
            {#if user.roles?.length}
              <span class="mono">{user.roles.join(', ')}</span>
            {:else}
              <span class="none">{$_('admin.users.noRoles')}</span>
            {/if}
          </td>
          <td>
            {#if user.sharesAllLibraries}
              {$_('admin.users.allLibraries')}
            {:else}
              {$_('admin.users.someLibraries', {
                values: { count: user.sharedLibraryIds?.length ?? 0 },
              })}
            {/if}
          </td>
          <td>
            <div class="actions">
              <button
                type="button"
                disabled={busy}
                data-testid={`edit-user-${user.id}`}
                onclick={() => (editing = { user })}
              >
                {$_('admin.libraries.edit')}
              </button>
              <span class="separator" aria-hidden="true"></span>
              {#if blocked}
                <!-- The reason, not a disabled button with no explanation. -->
                <span class="blocked" data-testid={`blocked-${user.id}`}>{blocked}</span>
              {:else}
                <button
                  class="quiet-danger"
                  type="button"
                  disabled={busy}
                  data-testid={`delete-user-${user.id}`}
                  onclick={() => (deleting = user)}
                >
                  {$_('admin.libraries.delete')}
                </button>
              {/if}
            </div>
          </td>
        </tr>
      {/each}
      {#if users.length === 0}
        <tr><td colspan="4" class="none">{$_('admin.users.none')}</td></tr>
      {/if}
    </tbody>
  </table>
</div>

{#if editing}
  <UserForm
    user={editing.user}
    onclose={() => (editing = null)}
    onsaved={async () => {
      editing = null
      await load()
    }}
  />
{/if}

{#if deleting}
  <TypedConfirmDialog
    title={$_('admin.users.deleteTitle')}
    expected={deleting.email}
    summary={$_('admin.users.deleteSummary', { values: { email: deleting.email } })}
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
  .actions {
    display: flex;
    flex-wrap: wrap;
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
  .blocked {
    color: var(--text-muted);
    font-size: var(--font-xs);
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
</style>
