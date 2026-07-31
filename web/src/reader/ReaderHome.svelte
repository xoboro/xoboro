<script>
  /**
   * The reader's landing surface.
   *
   * Shows who is signed in, whether the event stream is live, the way out, and — for
   * an administrator — the way into the console. The reading screens themselves are
   * tracked separately; this is the shell they mount into, not a placeholder for
   * them.
   */
  import { Languages, LogOut, Settings } from '@lucide/svelte'
  import { _, applyLocale, locale } from '../lib/i18n.js'
  import { isAdministrator, session, signOut } from '../lib/session.js'
  import { eventHub } from '../lib/eventHub.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import StreamIndicator from '../components/StreamIndicator.svelte'

  let error = $state(null)

  const streamStore = eventHub.status
  const user = $derived($session.user)
  const administrator = $derived(isAdministrator(user))
  const streamStatus = $derived($streamStore)

  function toggleLanguage() {
    applyLocale($locale === 'ko' ? 'en' : 'ko')
  }

  async function leave() {
    error = null
    try {
      await signOut()
    } catch (caught) {
      error = caught
    }
  }
</script>

<header>
  <div class="who">
    <span class="email">{user?.email ?? ''}</span>
    {#if administrator}<span class="role">ADMIN</span>{/if}
  </div>
  <div class="actions">
    <StreamIndicator status={streamStatus} />
    {#if administrator}
      <a class="icon" href="#/admin" aria-label={$_('admin.title')} title={$_('admin.title')}>
        <Settings size={18} aria-hidden="true" />
      </a>
    {/if}
    <button class="icon" type="button" onclick={toggleLanguage} aria-label={$_('common.language')}>
      <Languages size={18} aria-hidden="true" />
    </button>
    <button class="icon" type="button" onclick={leave} aria-label={$_('common.signOut')}>
      <LogOut size={18} aria-hidden="true" />
    </button>
  </div>
</header>

<main>
  <ErrorNotice {error} />
</main>

<style>
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: max(var(--space-4), calc(var(--inset-top) + var(--space-2)))
      var(--gutter-right) var(--space-2) var(--gutter-left);
    border-bottom: 1px solid var(--line-subtle);
  }
  .who {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: var(--space-2);
  }
  .email {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .role {
    flex: 0 0 auto;
    padding: 2px var(--space-2);
    border-radius: var(--radius-pill);
    background: var(--accent-quiet);
    color: var(--accent-text);
    font-size: var(--font-xs);
    font-weight: 700;
  }
  .actions {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: var(--space-1);
  }
  .icon {
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    place-items: center;
    border: 0;
    border-radius: var(--radius-sm);
    background: none;
    color: var(--text);
    cursor: pointer;
  }
  main {
    padding: var(--space-4) var(--gutter-right) var(--space-5) var(--gutter-left);
  }
</style>
