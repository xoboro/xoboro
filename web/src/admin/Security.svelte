<script>
  /**
   * External login configuration, and the authentication log.
   *
   * The provider section is **read-only, and says why**. Registration lives in
   * environment variables so client secrets never enter the database — the one
   * artifact that gets backed up, copied elsewhere to debug, and restored onto other
   * hosts. What was missing was never the ability to change the configuration;
   * anyone who can set environment variables already can. It was the ability to see
   * what the running process resolved without shell access to the host.
   *
   * No client id, secret or endpoint is shown, because publishing those would turn a
   * configuration display into a credential disclosure.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { listAuthenticationActivity, readExternalLogin } from '../lib/api/admin.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import DataTable from './DataTable.svelte'

  let config = $state(null)
  let activity = $state(null)
  let error = $state(null)
  let activityBusy = $state(false)

  async function loadConfig() {
    try {
      config = await readExternalLogin()
      error = null
    } catch (caught) {
      error = caught
    }
  }

  async function loadActivity(page = 0) {
    activityBusy = true
    try {
      activity = await listAuthenticationActivity({ page })
      error = null
    } catch (caught) {
      error = caught
    } finally {
      activityBusy = false
    }
  }

  onMount(() => {
    loadConfig()
    loadActivity(0)
  })

  function when(millis) {
    return millis ? new Date(millis).toLocaleString() : ''
  }
</script>

<h1>{$_('admin.nav.security')}</h1>

<ErrorNotice {error} onretry={() => { loadConfig(); loadActivity(0) }} />

<section data-testid="external-login-section">
  <h2>{$_('admin.security.externalLogin')}</h2>
  <p class="hint" data-testid="readonly-reason">{$_('admin.security.readOnlyReason')}</p>

  {#if config}
    <dl>
      <div class="entry">
        <dt>{$_('admin.security.accountCreation')}</dt>
        <dd>{config.accountCreationEnabled ? $_('admin.security.on') : $_('admin.security.off')}</dd>
      </div>
      <div class="entry">
        <dt>{$_('admin.security.emailVerification')}</dt>
        <dd>
          {config.oidcEmailVerificationRequired
            ? $_('admin.security.required')
            : $_('admin.security.notRequired')}
        </dd>
      </div>
      <div class="entry">
        <dt>{$_('admin.security.accountLinking')}</dt>
        <dd>
          <span data-testid="account-linking">{config.accountLinking}</span>
          <span class="hint">{$_(`admin.security.linking.${config.accountLinking}`)}</span>
        </dd>
      </div>
    </dl>

    <h3>{$_('admin.security.providers')}</h3>
    {#if config.providers?.length}
      <ul>
        {#each config.providers as provider (provider.registrationId)}
          <li>
            <span>{provider.name}</span>
            <span class="mono">{provider.registrationId}</span>
          </li>
        {/each}
      </ul>
    {:else}
      <p class="hint">{$_('admin.security.noProviders')}</p>
    {/if}
  {/if}
</section>

<section>
  <h2>{$_('admin.security.authenticationActivity')}</h2>
  <p class="hint">{$_('admin.security.activityHint')}</p>

  <DataTable
    caption={$_('admin.security.authenticationActivity')}
    name="authentication-activity"
    columns={[
      $_('admin.security.when'),
      $_('session.email'),
      $_('admin.security.outcome'),
      $_('admin.security.source'),
      $_('admin.security.ip'),
    ]}
    page={activity}
    onpage={loadActivity}
    busy={activityBusy}
    emptyLabel={$_('admin.security.noActivity')}
  >
    {#snippet row(entry)}
      <tr>
        <th scope="row">{when(entry.dateTimeMillis)}</th>
        <!-- Kept even when no account matched: "someone is trying this address" is the
             value of a failure row. -->
        <td>{entry.email ?? ''}</td>
        <td>
          {#if entry.success}
            <span>{$_('admin.security.accepted')}</span>
          {:else}
            <span class="failure">{$_('admin.security.rejected')}</span>
            {#if entry.error}<span class="mono">{entry.error}</span>{/if}
          {/if}
        </td>
        <td>{entry.source ?? ''}</td>
        <td class="mono">{entry.ip ?? ''}</td>
      </tr>
    {/snippet}
  </DataTable>
</section>

<style>
  h1 {
    margin: 0 0 var(--space-4);
    font-size: var(--font-xl);
  }
  h2 {
    margin: 0 0 var(--space-2);
    font-size: var(--font-lg);
  }
  h3 {
    margin: var(--space-4) 0 var(--space-2);
    font-size: var(--font-md);
  }
  section {
    margin-bottom: var(--space-6);
  }
  .hint {
    margin: 0 0 var(--space-3);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  dl {
    margin: 0;
  }
  .entry {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-2) 0;
    border-top: 1px solid var(--line-subtle);
  }
  dt {
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  dd {
    display: flex;
    flex-wrap: wrap;
    margin: 0;
    gap: var(--space-2);
    font-size: var(--font-sm);
  }
  ul {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  ul li {
    display: flex;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-2) 0;
    border-top: 1px solid var(--line-subtle);
    font-size: var(--font-sm);
  }
  .mono {
    color: var(--text-muted);
    font-family: ui-monospace, monospace;
    font-size: var(--font-xs);
  }
  .failure {
    color: var(--danger);
  }
</style>
