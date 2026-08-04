<script>
  /**
   * Creates a user, or replaces an existing user's authorization.
   *
   * `PUT /users/{id}` replaces roles, shared libraries, `sharesAllLibraries` and
   * restrictions **exactly as submitted**, so every one of them is sent every time.
   * Unlike the settings endpoint, absence here is not "leave unchanged".
   *
   * Content restrictions are a real authorization boundary, not a preference: the
   * server turns them into SQL predicates carried by every catalog read. The form
   * therefore states which mode is which rather than offering a bare toggle —
   * `ALLOW_ONLY` and `EXCLUDE` mean opposite things about the same list.
   */
  import { _ } from '../lib/i18n.js'
  import { Treatment } from '../lib/errors.js'
  import { createUser, replaceUser, resetUserPassword } from '../lib/api/admin.js'
  import { listLibraries } from '../lib/api/libraries.js'
  import Dialog from '../components/Dialog.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'

  let { user = null, onsaved, onclose } = $props()

  const ROLES = ['ADMIN', 'FILE_DOWNLOAD', 'PAGE_STREAMING', 'KOBO_SYNC', 'KOREADER_SYNC']
  const editing = user !== null

  let email = $state(user?.email ?? '')
  let password = $state('')
  let roles = $state([...(user?.roles ?? ['PAGE_STREAMING'])])
  let sharesAllLibraries = $state(user?.sharesAllLibraries ?? true)
  let sharedLibraryIds = $state([...(user?.sharedLibraryIds ?? [])])
  let libraries = $state([])
  let busy = $state(false)
  let error = $state(null)

  const fieldError = $derived(error?.treatment === Treatment.FIELD ? error : null)
  const generalError = $derived(fieldError ? null : error)

  $effect(() => {
    listLibraries()
      .then((found) => (libraries = found))
      .catch(() => {
        // A failure here costs the grant picker, not the form. Roles and the password
        // are still editable, and reporting it as a form error would suggest the
        // submission is invalid when it is not.
        libraries = []
      })
  })

  function toggle(list, value) {
    return list.includes(value) ? list.filter((entry) => entry !== value) : [...list, value]
  }

  async function submit(event) {
    event.preventDefault()
    if (busy) return
    busy = true
    error = null
    try {
      const authorization = {
        roles,
        sharesAllLibraries,
        // Sent empty when sharing everything: the two fields are independent on the
        // wire, and leaving stale identifiers behind would be confusing to read back.
        sharedLibraryIds: sharesAllLibraries ? [] : sharedLibraryIds,
        restrictions: user?.restrictions ?? null,
      }
      if (editing) {
        await replaceUser(user.id, authorization)
        // A blank field means "leave the password alone", which is why the reset is a
        // separate request rather than part of the replacement body.
        if (password) await resetUserPassword(user.id, password)
      } else {
        await createUser({ email: email.trim(), password, ...authorization })
      }
      onsaved()
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }
</script>

<Dialog title={editing ? $_('admin.users.editTitle') : $_('admin.users.createTitle')} {onclose}>
  {#snippet children()}
    <form id="user-form" onsubmit={submit} novalidate>
      {#if editing}
        <p class="identity">{user.email}</p>
      {:else}
        <label for="user-email">{$_('session.email')}</label>
        <input
          id="user-email"
          type="email"
          data-testid="user-email"
          bind:value={email}
          required
        />
      {/if}

      <label for="user-password">
        {editing ? $_('admin.users.newPassword') : $_('session.password')}
      </label>
      <input
        id="user-password"
        type="password"
        autocomplete="new-password"
        data-testid="user-password"
        bind:value={password}
        required={!editing}
      />
      {#if editing}
        <!-- Stated because it is surprising and it logs people out of their devices. -->
        <p class="hint">{$_('admin.users.passwordResetHint')}</p>
      {/if}

      <fieldset>
        <legend>{$_('admin.users.roles')}</legend>
        {#each ROLES as role (role)}
          <label class="check">
            <input
              type="checkbox"
              value={role}
              checked={roles.includes(role)}
              data-testid={`role-${role}`}
              onchange={() => (roles = toggle(roles, role))}
            />
            <span class="mono">{role}</span>
          </label>
        {/each}
      </fieldset>

      <fieldset>
        <legend>{$_('admin.users.libraries')}</legend>
        <label class="check">
          <input type="checkbox" data-testid="shares-all" bind:checked={sharesAllLibraries} />
          {$_('admin.users.allLibraries')}
        </label>
        {#if !sharesAllLibraries}
          {#each libraries as library (library.id)}
            <label class="check">
              <input
                type="checkbox"
                checked={sharedLibraryIds.includes(library.id)}
                onchange={() => (sharedLibraryIds = toggle(sharedLibraryIds, library.id))}
              />
              {library.name}
            </label>
          {/each}
          {#if libraries.length === 0}
            <p class="hint">{$_('admin.libraries.none')}</p>
          {/if}
        {/if}
      </fieldset>

      {#if editing}
        <p class="hint">{$_('admin.users.replacementNote')}</p>
      {/if}

      <ErrorNotice error={generalError} />
      {#if fieldError}
        <p class="field-error" role="alert">
          {$_(fieldError.messageKey, { default: $_(fieldError.fallbackMessageKey) })}
        </p>
      {/if}
    </form>
  {/snippet}

  {#snippet footer()}
    <button type="button" onclick={onclose}>{$_('common.cancel')}</button>
    <button class="primary" type="submit" form="user-form" data-testid="user-save" disabled={busy}>
      {busy ? $_('common.saving') : $_('common.save')}
    </button>
  {/snippet}
</Dialog>

<style>
  form {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .identity {
    margin: 0;
    font-weight: 600;
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
    margin-bottom: var(--space-2);
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
  .mono {
    font-family: ui-monospace, monospace;
    font-size: var(--font-xs);
  }
  .hint {
    margin: var(--space-1) 0 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .field-error {
    margin: 0;
    color: var(--danger);
    font-size: var(--font-sm);
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
  .primary:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
