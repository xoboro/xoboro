<script>
  /**
   * The signed-in surface.
   *
   * This is the seam the reader and administrator shells mount into. It currently
   * shows who is signed in, whether the event stream is live, and the way out —
   * which is genuinely everything the foundation can answer. Screens are added
   * behind it, not by replacing it.
   */
  import { Languages, LogOut } from 'lucide-svelte'
  import { _, applyLocale, locale } from '../lib/i18n.js'
  import { isAdministrator, signOut } from '../lib/session.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import StreamIndicator from '../components/StreamIndicator.svelte'

  let { user, streamStatus, resyncReason = null } = $props()

  let error = $state(null)
  const administrator = $derived(isAdministrator(user))

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
    <span class="email">{user.email}</span>
    {#if administrator}<span class="role">ADMIN</span>{/if}
  </div>
  <div class="actions">
    <StreamIndicator status={streamStatus} />
    <button type="button" onclick={toggleLanguage} aria-label={$_('common.language')}>
      <Languages size={18} aria-hidden="true" />
    </button>
    <button type="button" onclick={leave} aria-label={$_('common.signOut')}>
      <LogOut size={18} aria-hidden="true" />
    </button>
  </div>
</header>

<!-- polite: a resync is context, not a failure the user must act on now, so it
     waits for a pause rather than interrupting whatever is being read. -->
<div class="announcements" role="status" aria-live="polite">
  {#if resyncReason}<p>{$_('stream.resynced')}</p>{/if}
</div>

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
    font-size: var(--font-md);
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
  .actions button {
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
  .announcements p {
    margin: 0;
    padding: var(--space-2) var(--gutter-left);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  main {
    padding: var(--space-4) var(--gutter-right) var(--space-5) var(--gutter-left);
  }
</style>
