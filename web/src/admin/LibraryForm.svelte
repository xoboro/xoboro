<script>
  /**
   * Creates a library, or replaces an existing one.
   *
   * The trap this component exists to avoid: `PUT` is a **full replacement**, not a
   * patch. A form that sent only the fields it displays would silently reset every
   * setting it does not — twenty-odd flags an operator had deliberately configured
   * would go back to defaults because someone corrected a typo in the name. So an
   * edit starts from the settings the server returned and overlays only what changed.
   *
   * Validation failures are rendered on the input that owns them. `library_root_overlap`
   * as a toast leaves the operator to guess which of two fields is wrong.
   */
  import { _ } from '../lib/i18n.js'
  import { Treatment } from '../lib/errors.js'
  import { createLibrary, replaceLibrary } from '../lib/api/libraries.js'
  import Dialog from '../components/Dialog.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'

  let {
    /** The library being edited, or `null` to create one. */
    library = null,
    onsaved,
    onclose,
  } = $props()

  const SCAN_INTERVALS = ['DISABLED', 'HOURLY', 'EVERY_6H', 'EVERY_12H', 'DAILY', 'WEEKLY']

  const editing = library !== null

  let name = $state(library?.name ?? '')
  let location = $state(library?.source?.location ?? '')
  let scanOnStartup = $state(library?.settings?.scanOnStartup ?? false)
  let scanInterval = $state(library?.settings?.scanInterval ?? 'EVERY_6H')
  let scanCbx = $state(library?.settings?.scanCbx ?? true)
  let scanPdf = $state(library?.settings?.scanPdf ?? true)
  let scanEpub = $state(library?.settings?.scanEpub ?? true)

  let busy = $state(false)
  let error = $state(null)

  const fieldError = $derived(error?.treatment === Treatment.FIELD ? error : null)
  const nameError = $derived(fieldError?.field === 'name' ? fieldError : null)
  const locationError = $derived(fieldError?.field === 'source.location' ? fieldError : null)
  // Anything that is not field-scoped still has to be shown somewhere.
  const generalError = $derived(fieldError ? null : error)

  function body() {
    return {
      name: name.trim(),
      source: {
        // Only the local provider exists today. It is sent explicitly rather than
        // omitted so the request says what it means.
        provider: library?.source?.provider ?? 'local',
        location: location.trim(),
      },
      settings: {
        // Spread first: this carries every setting the form does not show, which is
        // what stops a full replacement from resetting them.
        ...(library?.settings ?? {}),
        scanOnStartup,
        scanInterval,
        scanCbx,
        scanPdf,
        scanEpub,
      },
    }
  }

  async function submit(event) {
    event.preventDefault()
    if (busy) return
    busy = true
    error = null
    try {
      const saved = editing
        ? await replaceLibrary(library.id, body())
        : await createLibrary(body())
      onsaved(saved)
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }
</script>

<Dialog title={editing ? $_('admin.libraries.editTitle') : $_('admin.libraries.createTitle')} {onclose}>
  {#snippet children()}
    <form id="library-form" onsubmit={submit} novalidate>
      <label for="library-name">{$_('admin.libraries.name')}</label>
      <input
        id="library-name"
        data-testid="library-name"
        bind:value={name}
        aria-invalid={nameError ? 'true' : undefined}
        aria-describedby={nameError ? 'library-name-error' : undefined}
        required
      />
      {#if nameError}
        <p class="field-error" id="library-name-error" role="alert">
          {$_(nameError.messageKey, { default: $_('errors.unknown') })}
        </p>
      {/if}

      <label for="library-location">{$_('admin.libraries.location')}</label>
      <!-- The requirement is stated in a hint, not only in the placeholder. The local
           source rejects anything without a `file:` scheme, so a bare `/media/comics`
           answers 400 - and the server's message carries no `field`, so the field-level
           error below never fires for the most likely mistake and the operator gets a
           generic notice with no clue which input was wrong. A placeholder does not
           serve here either: it disappears the moment they start typing. -->
      <p class="hint" id="library-location-hint">{$_('admin.libraries.locationHint')}</p>
      <input
        id="library-location"
        data-testid="library-location"
        bind:value={location}
        aria-invalid={locationError ? 'true' : undefined}
        aria-describedby={locationError
          ? 'library-location-error library-location-hint'
          : 'library-location-hint'}
        placeholder="file:///media/comics"
        required
      />
      {#if locationError}
        <p class="field-error" id="library-location-error" role="alert">
          {$_(locationError.messageKey, { default: $_('errors.unknown') })}
        </p>
      {/if}

      <label for="library-interval">{$_('admin.libraries.scanInterval')}</label>
      <select id="library-interval" bind:value={scanInterval}>
        {#each SCAN_INTERVALS as interval (interval)}
          <option value={interval}>{$_(`admin.libraries.interval.${interval}`)}</option>
        {/each}
      </select>

      <fieldset>
        <legend>{$_('admin.libraries.scanning')}</legend>
        <label class="check">
          <input type="checkbox" bind:checked={scanOnStartup} />
          {$_('admin.libraries.scanOnStartup')}
        </label>
        <label class="check">
          <input type="checkbox" bind:checked={scanCbx} />
          {$_('admin.libraries.scanCbx')}
        </label>
        <label class="check">
          <input type="checkbox" bind:checked={scanPdf} />
          {$_('admin.libraries.scanPdf')}
        </label>
        <label class="check">
          <input type="checkbox" bind:checked={scanEpub} />
          {$_('admin.libraries.scanEpub')}
        </label>
      </fieldset>

      {#if editing}
        <p class="replacement-note">{$_('admin.libraries.replacementNote')}</p>
      {/if}

      <ErrorNotice error={generalError} />
    </form>
  {/snippet}

  {#snippet footer()}
    <button type="button" onclick={onclose}>{$_('common.cancel')}</button>
    <button
      class="primary"
      type="submit"
      form="library-form"
      data-testid="library-save"
      disabled={busy}
    >
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
  label {
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  /* Sits between the label and its input, so it is read before the field is filled in
     rather than after it has been got wrong. Muted, like every other hint. */
  .hint {
    margin: 0;
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  input:not([type='checkbox']),
  select {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-raised);
    color: var(--text);
  }
  input[aria-invalid='true'] {
    border-color: var(--danger);
  }
  .field-error {
    margin: 0;
    color: var(--danger);
    font-size: var(--font-sm);
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
  .replacement-note {
    margin: var(--space-2) 0 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
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
