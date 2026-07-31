<script>
  /**
   * The administrator console shell.
   *
   * Loaded as its own chunk, so a reader who never opens it never downloads it.
   *
   * Desktop-first and dense, which is the one place the two shells legitimately
   * diverge: an administrator wants exact numbers and keyboard-driven editing on a
   * wide screen, and a reader wants a thumb-driven surface on a phone. The controls
   * inside still meet the touch minimum, so the console remains usable on a tablet
   * even though its tables are denser than one.
   *
   * Access is not enforced here. Every administration route answers `403` on the
   * server before it looks anything up, so a non-administrator who types the URL
   * sees screens that report a refusal — the check that matters is the server's, and
   * hiding the navigation is presentation, not protection.
   */
  import Router from 'svelte-spa-router'
  import { ArrowLeft } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import { isAdministrator, session } from '../lib/session.js'
  import { eventHub } from '../lib/eventHub.js'
  import StreamIndicator from '../components/StreamIndicator.svelte'
  import AdminNav from './AdminNav.svelte'
  import Overview from './Overview.svelte'
  import Libraries from './Libraries.svelte'
  import Tasks from './Tasks.svelte'
  import Trash from './Trash.svelte'
  import Users from './Users.svelte'
  import ApiKeys from './ApiKeys.svelte'
  import Security from './Security.svelte'
  import Duplicates from './Duplicates.svelte'
  import History from './History.svelte'
  import Backups from './Backups.svelte'
  import Settings from './Settings.svelte'
  import NotFound from '../routes/NotFound.svelte'

  const streamStore = eventHub.status
  const administrator = $derived(isAdministrator($session.user))

  const routes = {
    '/admin': Overview,
    '/admin/libraries': Libraries,
    '/admin/trash': Trash,
    '/admin/tasks': Tasks,
    '/admin/users': Users,
    '/admin/api-keys': ApiKeys,
    '/admin/security': Security,
    '/admin/duplicates': Duplicates,
    '/admin/history': History,
    '/admin/backups': Backups,
    '/admin/settings': Settings,
    '*': NotFound,
  }
</script>

<div class="console">
  <AdminNav />

  <div class="content">
    <header>
      <a class="back" href="#/" aria-label={$_('admin.backToReader')}>
        <ArrowLeft size={18} aria-hidden="true" />
        <span>{$_('admin.backToReader')}</span>
      </a>
      <StreamIndicator status={$streamStore} />
    </header>

    {#if !administrator}
      <!-- Presentation only. The server refuses these routes regardless, so this
           explains rather than protects. -->
      <p class="refused" role="status">{$_('admin.notAdministrator')}</p>
    {/if}

    <main>
      <Router {routes} />
    </main>
  </div>
</div>

<style>
  .console {
    display: grid;
    min-height: 100dvh;
    grid-template-columns: 1fr;
  }
  .content {
    min-width: 0;
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: max(var(--space-3), calc(var(--inset-top) + var(--space-2)))
      var(--gutter-right) var(--space-3) var(--gutter-left);
    border-bottom: 1px solid var(--line-subtle);
  }
  .back {
    display: inline-flex;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  .refused {
    margin: var(--space-4) var(--gutter-left) 0;
    padding: var(--space-3);
    border: 1px solid var(--warning);
    border-radius: var(--radius);
    color: var(--text);
    font-size: var(--font-sm);
  }
  main {
    padding: var(--space-4) var(--gutter-right) var(--space-6) var(--gutter-left);
  }

  @media (min-width: 1024px) {
    .console {
      grid-template-columns: 232px minmax(0, 1fr);
    }
  }
</style>
