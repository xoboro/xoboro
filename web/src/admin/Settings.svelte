<script>
  /**
   * Server settings.
   *
   * The restart-required rendering is the point of this screen. The API reports no
   * boolean "restart required": for `serverPort`, `serverContextPath` and
   * `kepubifyPath` it returns `databaseSource` and `effectiveValue`, and their
   * difference *is* the answer. So both values are shown inline on the field, and
   * there is deliberately no global banner computed on the client — a derived flag
   * could disagree with the two values it came from, which is the disagreement the
   * API design avoided.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import {
    RESTART_REQUIRED_SETTINGS,
    awaitingRestart,
    readServerSettings,
    writeServerSettings,
  } from '../lib/api/admin.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'

  let settings = $state(null)
  let error = $state(null)
  let busy = $state(false)
  let saved = $state(false)

  // Only these are edited here. The multi-source ones are read-only on this screen:
  // changing a bound port from the UI that is served over it is a way to lock
  // yourself out, and it belongs with the environment configuration.
  let historyRetentionDays = $state(0)
  let authenticationActivityRetentionDays = $state(0)
  let taskPoolSize = $state(1)
  let rememberMeDurationDays = $state(1)

  async function load() {
    try {
      settings = await readServerSettings()
      historyRetentionDays = settings.historyRetentionDays ?? 0
      authenticationActivityRetentionDays = settings.authenticationActivityRetentionDays ?? 0
      taskPoolSize = settings.taskPoolSize ?? 1
      rememberMeDurationDays = settings.rememberMeDurationDays ?? 1
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(load)

  async function save(event) {
    event.preventDefault()
    if (busy) return
    busy = true
    saved = false
    error = null
    try {
      // Only the edited fields. Absent fields are left unchanged by this endpoint,
      // unlike the library PUT which is a full replacement.
      await writeServerSettings({
        historyRetentionDays: Number(historyRetentionDays),
        authenticationActivityRetentionDays: Number(authenticationActivityRetentionDays),
        taskPoolSize: Number(taskPoolSize),
        rememberMeDurationDays: Number(rememberMeDurationDays),
      })
      saved = true
      await load()
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }

  function shown(value) {
    return value === null || value === undefined ? $_('common.none') : String(value)
  }
</script>

<h1>{$_('admin.nav.settings')}</h1>

<ErrorNotice {error} onretry={load} />

{#if saved}
  <p class="saved" role="status" aria-live="polite">{$_('admin.settings.saved')}</p>
{/if}

{#if settings}
  <form onsubmit={save}>
    <fieldset>
      <legend>{$_('admin.settings.retention')}</legend>
      <!-- 0 means keep forever and is the default. Retention deletes audit rows, so
           an operator who has not chosen a policy has not asked for pruning. -->
      <p class="hint">{$_('admin.settings.retentionHint')}</p>

      <label for="history-retention">{$_('admin.settings.historyRetention')}</label>
      <input
        id="history-retention"
        type="number"
        min="0"
        data-testid="history-retention"
        bind:value={historyRetentionDays}
      />

      <label for="auth-retention">{$_('admin.settings.authRetention')}</label>
      <input
        id="auth-retention"
        type="number"
        min="0"
        data-testid="auth-retention"
        bind:value={authenticationActivityRetentionDays}
      />
      <p class="hint">{$_('admin.settings.sweepHint')}</p>
    </fieldset>

    <fieldset>
      <legend>{$_('admin.settings.runtime')}</legend>

      <label for="task-pool">{$_('admin.settings.taskPoolSize')}</label>
      <input id="task-pool" type="number" min="1" bind:value={taskPoolSize} />
      <p class="hint">{$_('admin.settings.taskPoolHint')}</p>

      <label for="remember-me">{$_('admin.settings.rememberMeDays')}</label>
      <input id="remember-me" type="number" min="1" bind:value={rememberMeDurationDays} />
    </fieldset>

    <button class="primary" type="submit" data-testid="save-settings" disabled={busy}>
      {busy ? $_('common.saving') : $_('common.save')}
    </button>
  </form>

  <section class="read-only">
    <h2>{$_('admin.settings.startupOnly')}</h2>
    <p class="hint">{$_('admin.settings.startupOnlyHint')}</p>

    <dl>
      {#each RESTART_REQUIRED_SETTINGS as key (key)}
        {@const setting = settings[key]}
        {@const pending = awaitingRestart(setting)}
        <div class="entry" data-testid={`setting-${key}`}>
          <dt>{key}</dt>
          <dd>
            <span class="running">
              {$_('admin.settings.running', { values: { value: shown(setting?.effectiveValue) } })}
            </span>
            {#if pending}
              <!-- Both values, stated. This is the whole reason the API returns two
                   fields instead of a flag. -->
              <span class="stored" data-testid={`pending-${key}`}>
                {$_('admin.settings.stored', {
                  values: { value: shown(setting?.databaseSource) },
                })}
              </span>
              <span class="restart">{$_('admin.settings.restartRequired')}</span>
            {/if}
          </dd>
        </div>
      {/each}
    </dl>
  </section>
{/if}

<style>
  h1 {
    margin: 0 0 var(--space-4);
    font-size: var(--font-xl);
  }
  h2 {
    margin: 0 0 var(--space-2);
    font-size: var(--font-md);
  }
  form {
    display: flex;
    max-width: 480px;
    flex-direction: column;
    gap: var(--space-2);
  }
  fieldset {
    margin: 0 0 var(--space-4);
    padding: 0;
    border: 0;
  }
  legend {
    margin-bottom: var(--space-2);
    font-size: var(--font-md);
    font-weight: 700;
  }
  label {
    display: block;
    margin: var(--space-2) 0 var(--space-1);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  input {
    width: 100%;
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-raised);
    color: var(--text);
  }
  .hint {
    margin: var(--space-1) 0 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .primary {
    align-self: flex-start;
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
  .read-only {
    margin-top: var(--space-6);
    padding: var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  dl {
    margin: var(--space-3) 0 0;
  }
  .entry {
    padding: var(--space-2) 0;
    border-top: 1px solid var(--line-subtle);
  }
  dt {
    color: var(--text-muted);
    font-size: var(--font-xs);
    font-family: ui-monospace, monospace;
  }
  dd {
    display: flex;
    flex-wrap: wrap;
    margin: var(--space-1) 0 0;
    gap: var(--space-2);
    font-size: var(--font-sm);
  }
  .stored {
    color: var(--warning);
  }
  .restart {
    color: var(--warning);
    font-weight: 700;
  }
  .saved {
    margin: 0 0 var(--space-3);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
</style>
